"""Settings and API keys.

Settings live in ~/.ronie/settings.json (override the folder with RONIE_HOME).
The phone is the source of truth and pushes changes with `settings.update`.
API keys are kept in the OS keyring, with environment variables as a fallback.
"""
from __future__ import annotations

import copy
import json
import os
from pathlib import Path
from typing import Any

KEYRING_SERVICE = "ronie"

PROVIDERS = ("gemini", "groq", "openrouter", "ollama", "claude", "openai", "deepseek")
# Keys for services that aren't AI models.
SERVICE_KEYS = ("tavily",)
PAID_PROVIDERS = ("claude", "openai", "deepseek")

ENV_KEYS = {
    "gemini": "GEMINI_API_KEY",
    "groq": "GROQ_API_KEY",
    "openrouter": "OPENROUTER_API_KEY",
    "tavily": "TAVILY_API_KEY",
    "claude": "ANTHROPIC_API_KEY",
    "openai": "OPENAI_API_KEY",
    "deepseek": "DEEPSEEK_API_KEY",
}

DEFAULT_SETTINGS: dict[str, Any] = {
    "language": "auto",  # auto | hindi | english | hinglish
    "timezone": "Asia/Kolkata",  # the user's time zone; the cloud server itself runs on UTC
    "script": "roman",  # roman | devanagari
    "humor": "rare",  # off | rare | normal
    "premium": False,  # use paid providers when their key is set
    # Provider order per task. Paid providers are skipped unless premium is on.
    "routes": {
        "general": ["gemini", "groq", "openrouter", "ollama"],
        "vision": ["gemini", "openrouter", "ollama"],
        "coding": ["claude", "deepseek", "groq", "gemini", "openrouter", "ollama"],
        "interview": ["claude", "openai", "groq", "gemini", "openrouter", "ollama"],
        "game": ["deepseek", "claude", "groq", "gemini", "openrouter", "ollama"],
        "translate": ["gemini", "groq", "openrouter", "ollama"],
        # Web research answers and background memory upkeep.
        "research": ["claude", "groq", "gemini", "openrouter", "ollama"],
        "memory": ["groq", "gemini", "openrouter", "ollama"],
    },
    # Model per provider. Free-tier model names change; check them in each console.
    "models": {
        "gemini": "chain",  # see gemini_chains
        "groq": "chain",  # see model_chains
        "openrouter": "chain",  # see model_chains
        "ollama": "qwen2.5:7b",
        "claude": "claude-opus-5-5",
        "openai": "",  # set in the companion app; provider is off until a model is chosen
        "deepseek": "deepseek-chat",
    },
    "ollama_url": "http://localhost:11434/v1",
    # Quick tasks answer without the model's extra thinking step, for lower delay.
    # Coding and game keep thinking because they need careful reasoning.
    "quick_tasks": ["general", "vision", "translate", "interview"],
    # thinkingConfig sent on quick tasks, by model name prefix (longest match wins); dropped if a model rejects it.
    "gemini_quick_thinking": {
        "gemini-2.5": {"thinkingBudget": 0},
        "gemini-3": {"thinkingLevel": "low"},
        "gemma-4": {"thinkingLevel": "minimal"},
    },
    # Gemini Live models for real-time voice, tried in order. 31 Oct 2026, from the cloud server: both reply in about
    # 1.0 s (median of 4), but 3.8-live hears names right ("Ronie", "Stremio", "Kubernetes") and uses the custom
    # vocabulary, which 3.1-flash-live-preview ignores. 2.5-flash-native-audio: 1.9 s.
    "live_models": ["gemini-3.8-live", "gemini-3.1-flash-live-preview", "gemini-2.5-flash-native-audio-latest"],
    "live_voice": "Charon",  # a Gemini prebuilt voice (see voices.py); pick one in mission control or the app
    "laptop_browser": "",  # browser Ronie opens web pages in on the laptop (e.g. "brave"); empty = Windows' default
    "tv_host": "",  # the Android / Google TV's IP address on the home Wi-Fi (network debugging on)
    "adb_path": "",  # empty = Android SDK platform-tools, else adb on PATH
    "live_google_search": False,  # Gemini Live's built-in search; needs a paid Gemini plan  # a Gemini prebuilt voice name, e.g. "Puck"; empty = the model's default
    # A model that hasn't answered in this many seconds is skipped for the next one.
    "quick_timeout_s": 8,
    "careful_timeout_s": 12,
    # Each Gemini model has its own free quota. Ronie tries a chain of models in order and skips any
    # that are used up. Model names change over time: a model Google doesn't offer is skipped for a day.
    # Order measured on 30 Oct 2026: 3.5-flash-lite 1.1 s, 3.1-flash-lite 1.4 s, gemma-4-26b 1.9 s, 3.5-flash 2.6 s.
    "gemini_chains": {
        # Fast, light answers.
        "quick": [
            "gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemma-4-26b-a4b-it",
            "gemini-2.5-flash-lite", "gemini-flash-lite-latest",
        ],
        # Careful answers: strongest model first; Gemma 4 31B last because it can think for 30 s.
        "reasoning": [
            "gemini-3.5-flash", "gemini-2.5-flash", "gemini-3.5-flash-lite",
            "gemma-4-26b-a4b-it", "gemma-4-31b-it",
        ],
        # Photos from the glasses.
        "vision": ["gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemma-4-26b-a4b-it", "gemini-3.5-flash"],
    },
    "task_chain": {
        "general": "quick",
        "translate": "quick",
        "vision": "vision",
        "interview": "reasoning",
        "coding": "reasoning",
        "game": "reasoning",
        "research": "reasoning",
        "memory": "reasoning",
    },
    # Shared memory (see memory.py). Token counts are rough (about 4 characters per token).
    "memory_compact_tokens": 3000,  # fold older conversation into the summary past this size
    "memory_fact_delay": 60,  # seconds of quiet after a turn before looking for new facts in it
    "memory_keep_turns": 8,  # turns kept word for word after compaction
    "memory_recent_turns": 12,  # recent turns included in every prompt
    "memory_max_facts": 30,  # long-term facts included in every prompt
    # Model chains for the other free providers, by request type (see task_chain).
    # Groq's model list changes; check https://console.groq.com/docs/models.
    "model_chains": {
        "groq": {
            "quick": ["openai/gpt-oss-20b", "qwen/qwen3.8-27b", "openai/gpt-oss-120b"],
            "reasoning": ["openai/gpt-oss-120b", "qwen/qwen3.8-27b", "openai/gpt-oss-20b"],
            "vision": [],  # no Groq model reads photos
        },
        # OpenRouter's free models (names end in ":free"); the list changes, see https://openrouter.ai/models?q=free
        "openrouter": {
            "quick": ["nvidia/nemotron-3.5-lightning:free", "google/gemma-4-26b-a4b-it:free", "thinkingmachines/inkling-small:free"],
            "reasoning": ["nvidia/nemotron-3-ultra-550b-a55b:free", "google/gemma-4-31b-it:free", "thinkingmachines/inkling:free"],
            "vision": ["google/gemma-4-26b-a4b-it:free", "nvidia/nemotron-3.5-super-vl:free"],
        },
    },
    # Requests per day you know a model allows. Limits Google reports in a 429 are learned automatically.
    "gemini_daily_limits": {},
    # Monthly cap per paid provider, in US dollars.
    "budgets_usd": {"claude": 5.0, "openai": 5.0, "deepseek": 2.0},
    # Price per million tokens (input, output) in US dollars, used for budgets.
    # Claude prices are Anthropic's list prices; fill the others from their pricing pages.
    "prices_per_mtok": {
        "claude-opus-5-5": [4.00, 20.00],
        "claude-sonnet-5-5": [2.00, 10.00],
        "claude-haiku-5-5": [0.10, 0.50],
    },
}


def data_dir() -> Path:
    d = Path(os.environ.get("RONIE_HOME", Path.home() / ".ronie"))
    d.mkdir(parents=True, exist_ok=True)
    return d


def _merge(base: dict[str, Any], override: dict[str, Any]) -> dict[str, Any]:
    out = copy.deepcopy(base)
    for k, v in override.items():
        if isinstance(v, dict) and isinstance(out.get(k), dict):
            out[k] = _merge(out[k], v)
        else:
            out[k] = v
    return out


class Settings:
    def __init__(self, path: Path | None = None) -> None:
        self.path = path or data_dir() / "settings.json"
        self.data = copy.deepcopy(DEFAULT_SETTINGS)
        if self.path.exists():
            self.data = _merge(self.data, json.loads(self.path.read_text("utf-8-sig")))

    def update(self, partial: dict[str, Any]) -> None:
        self.data = _merge(self.data, partial)
        self.save()

    def save(self) -> None:
        self.path.write_text(json.dumps(self.data, indent=2, ensure_ascii=False), "utf-8")

    def __getitem__(self, key: str) -> Any:
        return self.data[key]


class Keys:
    """API keys in the OS keyring (Windows Credential Manager), falling back to env vars. On a server without a
    desktop keyring (RONIE_KEY_STORE=file), in ~/.ronie/keys.json, readable only by Ronie's user."""

    def __init__(self) -> None:
        self.file = data_dir() / "keys.json" if os.environ.get("RONIE_KEY_STORE") == "file" else None

    def _read(self) -> dict:
        try:
            return json.loads(self.file.read_text("utf-8")) if self.file and self.file.exists() else {}
        except (OSError, ValueError):
            return {}

    def _write(self, keys: dict) -> None:
        self.file.write_text(json.dumps(keys, indent=2), "utf-8")
        os.chmod(self.file, 0o600)

    def get(self, provider: str) -> str | None:
        if self.file:
            value = self._read().get(provider)
            if value:
                return value
        else:
            try:
                import keyring

                value = keyring.get_password(KEYRING_SERVICE, provider)
                if value:
                    return value
            except Exception:
                pass
        env = ENV_KEYS.get(provider)
        return os.environ.get(env) if env else None

    def set(self, provider: str, key: str) -> None:
        if self.file:
            keys = self._read()
            keys[provider] = key
            self._write(keys)
            return
        import keyring

        keyring.set_password(KEYRING_SERVICE, provider, key)

    def delete(self, provider: str) -> None:
        if self.file:
            keys = self._read()
            if keys.pop(provider, None) is not None:
                self._write(keys)
            return
        import keyring

        try:
            keyring.delete_password(KEYRING_SERVICE, provider)
        except keyring.errors.PasswordDeleteError:
            pass
