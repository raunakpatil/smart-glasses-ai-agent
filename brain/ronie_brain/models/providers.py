"""Provider implementations.

Gemini, Groq, Ollama, OpenAI and DeepSeek are called over HTTP (Groq, Ollama, OpenAI and
DeepSeek share the OpenAI-compatible chat completions API). Claude uses the Anthropic SDK.
"""
from __future__ import annotations

import re
import time
from typing import Callable

import httpx

from ..config import Keys, Settings
from .base import LLMRequest, LLMResult, Provider, ProviderError
from .gemini import GeminiProvider
from .providers_common import DEFAULT_RATE_LIMIT_WAIT, _messages, raise_for_status, retry_after_seconds, shared_client

def strip_thinking(text: str) -> str:
    """Some open models wrap their reasoning in <think> tags; only the answer should be spoken."""
    return re.sub(r"<think>.*?</think>", "", text, flags=re.S).strip()


class OpenAICompatProvider(Provider):
    """Groq, Ollama, OpenAI and DeepSeek all speak the OpenAI chat completions API."""

    def __init__(
        self,
        name: str,
        settings: Settings,
        keys: Keys,
        base_url: str | Callable[[], str],
        paid: bool,
        needs_key: bool = True,
    ) -> None:
        self.name, self.settings, self.keys = name, settings, keys
        self._base_url, self.paid, self.needs_key = base_url, paid, needs_key
        self._up, self._checked_at = False, float("-inf")
        self._blocked: dict[str, float] = {}  # model -> Unix time it can be tried again

    @property
    def base_url(self) -> str:
        return self._base_url() if callable(self._base_url) else self._base_url

    def available(self) -> bool:
        if not (self.settings["models"].get(self.name) or self.settings["model_chains"].get(self.name)):
            return False
        if self.needs_key:
            return bool(self.keys.get(self.name))
        return self._reachable()

    def _reachable(self) -> bool:
        """Keyless local servers (Ollama) count as ready only while they answer. Cached for 10 s."""
        now = time.monotonic()
        if now - self._checked_at > 10:
            try:
                httpx.get(f"{self.base_url.rstrip('/')}/models", timeout=0.5)
                self._up = True
            except httpx.HTTPError:
                self._up = False
            self._checked_at = now
        return self._up

    def chain(self, task: str) -> list[str]:
        """Models to try for this task: the provider's chain for the task type, or its single model."""
        chains = self.settings["model_chains"].get(self.name)
        if not chains:
            return [self.settings["models"][self.name]]
        kind = self.settings["task_chain"].get(task, "quick")
        return chains.get(kind, chains.get("quick", []))

    def payload(self, req: LLMRequest, model: str, extras: bool = True) -> dict:
        messages = _messages(req)
        if req.image_b64:
            last_user = max((i for i, m in enumerate(messages) if m["role"] == "user"), default=None)
            if last_user is not None:
                messages[last_user] = {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": messages[last_user]["content"]},
                        {"type": "image_url", "image_url": {"url": f"data:{req.mime_type};base64,{req.image_b64}"}},
                    ],
                }
        if req.system:
            messages = [{"role": "system", "content": req.system}, *messages]
        quick = req.task in self.settings["quick_tasks"]
        # Reasoning models spend tokens thinking before they answer, so give careful tasks more room.
        body = {"model": model, "messages": messages, "max_tokens": req.max_tokens if quick else max(req.max_tokens, 4096)}
        if extras and self.name == "groq":
            if model.startswith("openai/gpt-oss"):
                body["reasoning_effort"] = "low" if quick else "medium"
            elif "qwen3" in model:
                body["reasoning_format"] = "hidden"  # keep the model's thinking out of the spoken answer
        if extras and self.name == "openrouter":
            # Keep reasoning text out of the answer, and think less on quick requests.
            body["reasoning"] = {"exclude": True, **({"effort": "low"} if quick else {})}
        return body

    async def complete(self, req: LLMRequest) -> LLMResult:
        headers = {}
        if key := self.keys.get(self.name):
            headers["Authorization"] = f"Bearer {key}"
        url = f"{self.base_url.rstrip('/')}/chat/completions"
        notes: list[str] = []
        last_retry: float | None = None
        models = self.chain(req.task)
        if not models:
            raise ProviderError(f"{self.name}: no model for {req.task} requests")
        for model in models:
            if (until := self._blocked.get(model, 0)) > time.time():
                notes.append(f"{self.name} {model}: limit reached, back at {time.strftime('%H:%M', time.localtime(until))}")
                continue
            body = self.payload(req, model)
            timeout = req.timeout_s or self.settings["quick_timeout_s" if req.task in self.settings["quick_tasks"] else "careful_timeout_s"]
            try:
                r = await shared_client().post(url, headers=headers, json=body, timeout=timeout)
                if r.status_code == 400 and body != self.payload(req, model, extras=False):
                    r = await shared_client().post(url, headers=headers, json=self.payload(req, model, extras=False), timeout=timeout)
            except httpx.TimeoutException:
                notes.append(f"{self.name} {model}: too slow, skipped")
                continue
            except httpx.ConnectError as e:
                raise ProviderError(f"{self.name}: can't reach {self.base_url}") from e
            except httpx.HTTPError as e:
                raise ProviderError(f"{self.name}: {type(e).__name__}") from e
            if r.status_code == 429:
                last_retry = retry_after_seconds(r)
                self._blocked[model] = time.time() + last_retry
                notes.append(f"{self.name} {model}: limit reached")
                continue
            if r.status_code == 404:
                self._blocked[model] = time.time() + 24 * 3600
                notes.append(f"{self.name} {model}: not offered for this key")
                continue
            if r.status_code in (401, 403):
                raise ProviderError(f"{self.name}: key rejected ({r.status_code}). Check the key in mission control.")
            try:
                raise_for_status(self.name, r)
                data = r.json()
                text = strip_thinking(data["choices"][0]["message"].get("content") or "")
            except (httpx.HTTPError, KeyError, IndexError, ValueError) as e:
                notes.append(f"{self.name} {model}: {e}")
                continue
            if not text:
                notes.append(f"{self.name} {model}: empty answer")
                continue
            usage = data.get("usage") or {}
            return LLMResult(
                text=text,
                provider=self.name,
                model=model,
                input_tokens=usage.get("prompt_tokens", 0),
                output_tokens=usage.get("completion_tokens", 0),
                fallbacks=notes,
            )
        raise ProviderError(f"{self.name}: no model could answer ({'; '.join(notes)})", retry_after=last_retry if len(models) == 1 else None)


class ClaudeProvider(Provider):
    name = "claude"
    paid = True

    def __init__(self, settings: Settings, keys: Keys) -> None:
        self.settings, self.keys = settings, keys

    def available(self) -> bool:
        try:
            import anthropic  # noqa: F401
        except ImportError:
            return False
        return bool(self.keys.get("claude") and self.settings["models"]["claude"])

    async def complete(self, req: LLMRequest) -> LLMResult:
        import anthropic

        model = self.settings["models"]["claude"]
        # Claude thinks before answering on current models, so leave room for it; only used tokens are billed.
        kwargs: dict = {"model": model, "max_tokens": max(req.max_tokens, 16000), "messages": _messages(req)}
        if req.system:
            kwargs["system"] = req.system
        if req.task in self.settings["quick_tasks"]:
            kwargs["output_config"] = {"effort": "low"}  # faster spoken replies
        elif req.task in ("coding", "game"):
            kwargs["output_config"] = {"effort": "high"}
        try:
            async with anthropic.AsyncAnthropic(api_key=self.keys.get("claude")) as client:
                response = await client.messages.create(**kwargs)
        except anthropic.RateLimitError as e:
            raise ProviderError("claude: rate limited", retry_after=DEFAULT_RATE_LIMIT_WAIT) from e
        except anthropic.APIStatusError as e:
            raise ProviderError(f"claude: API error {e.status_code}") from e
        except anthropic.APIConnectionError as e:
            raise ProviderError("claude: connection failed") from e
        if response.stop_reason == "refusal":
            # The router falls back to the next provider in the route.
            raise ProviderError("claude: declined the request")
        text = "".join(block.text for block in response.content if block.type == "text")
        return LLMResult(
            text=text,
            provider=self.name,
            model=model,
            input_tokens=response.usage.input_tokens,
            output_tokens=response.usage.output_tokens,
        )


def build_providers(settings: Settings, keys: Keys) -> dict[str, Provider]:
    return {
        "gemini": GeminiProvider(settings, keys),
        "groq": OpenAICompatProvider("groq", settings, keys, "https://api.groq.com/openai/v1", paid=False),
        "openrouter": OpenAICompatProvider("openrouter", settings, keys, "https://openrouter.ai/api/v1", paid=False),
        "ollama": OpenAICompatProvider(
            "ollama", settings, keys, lambda: settings["ollama_url"], paid=False, needs_key=False
        ),
        "claude": ClaudeProvider(settings, keys),
        "openai": OpenAICompatProvider("openai", settings, keys, "https://api.openai.com/v1", paid=True),
        "deepseek": OpenAICompatProvider("deepseek", settings, keys, "https://api.deepseek.com", paid=True),
    }
