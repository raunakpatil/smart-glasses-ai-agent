"""Gemini with a fallback chain of models.

Each Gemini model has its own free-tier quota, so when one model hits its limit Ronie moves to the
next model in the chain for that kind of request. Daily use per model is counted locally, limits
are learned from Google's 429 replies, and a model that hit its limit is skipped until it resets.
"""
from __future__ import annotations

import json
import re
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

import httpx

from ..config import Keys, Settings, data_dir
from .base import LLMRequest, LLMResult, Provider, ProviderError
from .providers_common import _messages, retry_after_seconds, shared_client

URL = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"
UNAVAILABLE_WAIT = 24 * 3600  # a model Google doesn't serve (404) is skipped for a day


def pacific_day() -> str:
    """Google resets free-tier daily quotas at midnight Pacific time (UTC-8; daylight saving ignored)."""
    return (datetime.now(timezone.utc) - timedelta(hours=8)).date().isoformat()


class GeminiQuota:
    """Requests used today per model, learned daily limits, and models blocked until a reset time."""

    def __init__(self, settings: Settings, path: Path | None = None) -> None:
        self.settings = settings
        self.path = path or data_dir() / "gemini_quota.json"
        self.data: dict = {"day": pacific_day(), "used": {}, "blocked": {}, "limits": {}}
        if self.path.exists():
            try:
                self.data.update(json.loads(self.path.read_text("utf-8")))
            except ValueError:
                pass

    def _roll_day(self) -> None:
        if self.data["day"] != pacific_day():
            self.data["day"], self.data["used"] = pacific_day(), {}

    def _save(self) -> None:
        self.path.write_text(json.dumps(self.data, indent=2), "utf-8")

    def used(self, model: str) -> int:
        self._roll_day()
        return self.data["used"].get(model, 0)

    def limit(self, model: str) -> int | None:
        return self.settings["gemini_daily_limits"].get(model) or self.data["limits"].get(model)

    def blocked_until(self, model: str) -> float | None:
        until = self.data["blocked"].get(model)
        return until if until and until > time.time() else None

    def usable(self, model: str) -> str | None:
        """None if the model can be tried now, otherwise the reason it's skipped."""
        if until := self.blocked_until(model):
            return f"limit reached, back at {time.strftime('%H:%M', time.localtime(until))}"
        limit = self.limit(model)
        if limit is not None and self.used(model) >= limit:
            return f"used {limit}/{limit} today"
        return None

    def record_use(self, model: str) -> None:
        self._roll_day()
        self.data["used"][model] = self.data["used"].get(model, 0) + 1
        self._save()

    def block(self, model: str, seconds: float) -> None:
        self.data["blocked"][model] = time.time() + seconds
        self._save()

    def learn_limit(self, model: str, message: str) -> None:
        """Google's 429 says e.g. 'limit: 20 ... requests per day'; remember it so we stop before the next 429."""
        m = re.search(r"limit: (\d+)", message)
        if m and "per day" in message.lower():
            self.data["limits"][model] = int(m.group(1))
            self._save()

    def soonest_reset(self, models: list[str]) -> float | None:
        times = [t for m in models if (t := self.blocked_until(m))]
        return min(times) if times else None

    def snapshot(self) -> list[dict]:
        models = dict.fromkeys(m for chain in self.settings["gemini_chains"].values() for m in chain)
        return [
            {"model": m, "used": self.used(m), "limit": self.limit(m), "skipped": self.usable(m)}
            for m in models
        ]


class GeminiProvider(Provider):
    name = "gemini"

    def __init__(self, settings: Settings, keys: Keys, quota: GeminiQuota | None = None) -> None:
        self.settings, self.keys = settings, keys
        self.quota = quota or GeminiQuota(settings)

    def available(self) -> bool:
        return bool(self.keys.get("gemini"))

    def chain(self, task: str) -> list[str]:
        name = self.settings["task_chain"].get(task, "quick")
        chains = self.settings["gemini_chains"]
        return chains.get(name) or chains["quick"]

    def body(self, req: LLMRequest, model: str, quick_thinking: bool = True) -> dict:
        messages = _messages(req)
        system = req.system
        if system and model.startswith("gemma"):
            # Gemma models on the Gemini API don't accept a system instruction; put it in the first message.
            first = messages[0]
            messages = [{**first, "content": f"{system}\n\n{first['content']}"}, *messages[1:]]
            system = None
        contents = []
        for i, m in enumerate(messages):
            role = "model" if m["role"] == "assistant" else "user"
            parts: list[dict] = [{"text": m["content"]}]
            if req.image_b64 and i == len(messages) - 1 and role == "user":
                parts.insert(0, {"inlineData": {"mimeType": req.mime_type, "data": req.image_b64}})
            contents.append({"role": role, "parts": parts})
        body: dict = {
            "contents": contents,
            # Thinking counts toward the output limit, so careful tasks get more room.
            "generationConfig": {"maxOutputTokens": req.max_tokens if self.quick(req) else max(req.max_tokens, 4096)},
        }
        if system:
            body["systemInstruction"] = {"parts": [{"text": system}]}
        if quick_thinking and self.quick(req) and (thinking := self.thinking_for(model)):
            body["generationConfig"]["thinkingConfig"] = thinking
        return body

    def quick(self, req: LLMRequest) -> bool:
        return req.task in self.settings["quick_tasks"]

    def thinking_for(self, model: str) -> dict | None:
        """The quick-thinking setting for this model family, by longest matching name prefix."""
        matches = [p for p in self.settings["gemini_quick_thinking"] if model.startswith(p)]
        return self.settings["gemini_quick_thinking"][max(matches, key=len)] if matches else None

    async def complete(self, req: LLMRequest) -> LLMResult:
        headers = {"x-goog-api-key": self.keys.get("gemini") or ""}
        chain = self.chain(req.task)
        notes: list[str] = []
        client = shared_client()
        for model in chain:
            if reason := self.quota.usable(model):
                notes.append(f"gemini {model}: {reason}")
                continue
            body = self.body(req, model)
            timeout = req.timeout_s or self.settings["quick_timeout_s" if self.quick(req) else "careful_timeout_s"]
            post = {"headers": headers, "timeout": timeout}
            try:
                r = await client.post(URL.format(model=model), json=body, **post)
                if r.status_code == 400 and "thinkingConfig" in body["generationConfig"]:
                    r = await client.post(URL.format(model=model), json=self.body(req, model, False), **post)
            except httpx.TimeoutException:
                notes.append(f"gemini {model}: too slow, skipped")
                continue
            except httpx.HTTPError as e:
                raise ProviderError(f"gemini: can't reach Google ({type(e).__name__})") from e

            if r.status_code == 429:
                self.quota.learn_limit(model, r.text)
                self.quota.block(model, retry_after_seconds(r))
                notes.append(f"gemini {model}: free limit reached")
                continue
            if r.status_code == 404:
                self.quota.block(model, UNAVAILABLE_WAIT)
                notes.append(f"gemini {model}: not offered for this key")
                continue
            if r.status_code >= 500:
                notes.append(f"gemini {model}: Google error {r.status_code}")
                continue
            if r.status_code in (401, 403) or (r.status_code == 400 and "API key" in r.text):
                # A bad or restricted key fails the same way on every model.
                raise ProviderError(f"gemini: key rejected ({r.status_code}). Check the key in mission control.")
            if r.status_code == 400:
                notes.append(f"gemini {model}: request not accepted by this model")
                continue
            try:
                r.raise_for_status()
                data = r.json()
                parts = data["candidates"][0]["content"]["parts"]
                text = "".join(p.get("text", "") for p in parts if not p.get("thought"))
            except (httpx.HTTPError, KeyError, IndexError, ValueError) as e:
                notes.append(f"gemini {model}: unexpected reply ({type(e).__name__})")
                continue
            self.quota.record_use(model)
            if not text.strip():
                notes.append(f"gemini {model}: empty answer")
                continue
            usage = data.get("usageMetadata", {})
            return LLMResult(
                text=text,
                provider=self.name,
                model=model,
                input_tokens=usage.get("promptTokenCount", 0),
                output_tokens=usage.get("candidatesTokenCount", 0),
                fallbacks=notes,
            )

        # No retry_after here: other request types use other chains that may still have quota,
        # and skipping exhausted models above costs no network calls.
        reset = self.quota.soonest_reset(chain)
        when = f", first one back at {time.strftime('%H:%M', time.localtime(reset))}" if reset else ""
        raise ProviderError(f"gemini: every model for {req.task} is at its limit{when}")
