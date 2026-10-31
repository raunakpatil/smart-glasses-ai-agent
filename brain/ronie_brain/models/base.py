from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field


@dataclass
class LLMRequest:
    prompt: str
    task: str = "general"
    system: str | None = None
    history: list[dict] = field(default_factory=list)  # [{"role": "user"|"assistant", "content": str}]
    max_tokens: int = 1024
    premium: bool | None = None  # None = use the premium setting
    raw: bool = False  # internal jobs (memory compaction): no persona, no memory, no speech cleanup
    timeout_s: float | None = None  # per-model timeout; None = the quick/careful setting
    image_b64: str | None = None  # base64 JPEG / PNG for vision models
    mime_type: str = "image/jpeg"


@dataclass
class LLMResult:
    text: str
    provider: str
    model: str
    input_tokens: int = 0
    output_tokens: int = 0
    cost_usd: float = 0.0
    ms: int = 0
    fallbacks: list[str] = field(default_factory=list)


class ProviderError(Exception):
    """A provider failed (network, rate limit, refusal, bad response). The router tries the next one.

    retry_after (seconds) is set for rate limits, so the router can skip the provider until then.
    """

    def __init__(self, message: str, retry_after: float | None = None) -> None:
        super().__init__(message)
        self.retry_after = retry_after


class Provider(ABC):
    name: str
    paid: bool = False

    @abstractmethod
    def available(self) -> bool:
        """True when the provider has what it needs (key, model) to be tried."""

    @abstractmethod
    async def complete(self, req: LLMRequest) -> LLMResult:
        """Return the answer, or raise ProviderError."""
