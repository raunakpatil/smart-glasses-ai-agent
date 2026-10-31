"""Helpers shared by the HTTP providers."""
from __future__ import annotations

import re

import httpx

from .base import LLMRequest, ProviderError

TIMEOUT = httpx.Timeout(30.0, connect=5.0)


_client: httpx.AsyncClient | None = None


def shared_client() -> httpx.AsyncClient:
    """One connection pool for all providers, so each request skips a new TLS handshake."""
    global _client
    if _client is None or _client.is_closed:
        _client = httpx.AsyncClient(timeout=TIMEOUT)
    return _client


DEFAULT_RATE_LIMIT_WAIT = 60.0


def retry_after_seconds(r: httpx.Response) -> float:
    """How long a 429 says to wait: the Retry-After header, or Google's 'retry in 6h21m44s' text."""
    header = r.headers.get("retry-after")
    if header and header.replace(".", "", 1).isdigit():
        return float(header)
    m = re.search(r"retry in ((?:\d+h)?(?:\d+m)?(?:[\d.]+s)?)", r.text)
    if m and m.group(1):
        parts = {unit: value for value, unit in re.findall(r"([\d.]+)([hms])", m.group(1))}
        return float(parts.get("h", 0)) * 3600 + float(parts.get("m", 0)) * 60 + float(parts.get("s", 0))
    return DEFAULT_RATE_LIMIT_WAIT


def raise_for_status(name: str, r: httpx.Response) -> None:
    if r.status_code == 429:
        raise ProviderError(f"{name}: free limit reached", retry_after=retry_after_seconds(r))
    r.raise_for_status()


def _messages(req: LLMRequest) -> list[dict]:
    return [*req.history, {"role": "user", "content": req.prompt}]
