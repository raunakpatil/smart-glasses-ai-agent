"""Short-lived Gemini Live tokens, so the phone can open voice sessions without ever holding the API key.

Each token opens one Live session. It must be used within a couple of minutes and the session can run
for up to 30 minutes; the phone asks for a new token for every session.
"""
from __future__ import annotations

import time
from datetime import datetime, timedelta, timezone

import httpx

from .models.providers_common import shared_client

TOKEN_URL = "https://generativelanguage.googleapis.com/v1alpha/auth_tokens"
# Sessions opened with a token use this endpoint, with ?access_token=<token>.
LIVE_TOKEN_URL = (
    "wss://generativelanguage.googleapis.com/ws/"
    "google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContentConstrained"
)
SESSION_MINUTES = 30
START_WITHIN_SECONDS = 120


class LiveTokenError(Exception):
    pass


def _iso(t: datetime) -> str:
    return t.strftime("%Y-%m-%dT%H:%M:%SZ")


async def create_live_token(api_key: str) -> dict:
    now = datetime.now(timezone.utc)
    body = {
        "uses": 1,
        "expireTime": _iso(now + timedelta(minutes=SESSION_MINUTES)),
        "newSessionExpireTime": _iso(now + timedelta(seconds=START_WITHIN_SECONDS)),
    }
    try:
        r = await shared_client().post(TOKEN_URL, headers={"x-goog-api-key": api_key}, json=body, timeout=10)
    except httpx.HTTPError as e:
        raise LiveTokenError(f"can't reach Google ({type(e).__name__})") from e
    if r.status_code != 200:
        raise LiveTokenError(f"Google refused a voice token ({r.status_code})")
    return {
        "token": r.json()["name"],
        "url": LIVE_TOKEN_URL,
        "start_by": time.time() + START_WITHIN_SECONDS,
        "expires_at": time.time() + SESSION_MINUTES * 60,
    }
