import json

import httpx
import pytest

from conftest import FakeKeys
from ronie_brain.models import LLMRequest, ProviderError
from ronie_brain.models import gemini as gemini_mod
from ronie_brain.models.gemini import GeminiProvider, GeminiQuota

LIMIT_429 = json.dumps({"error": {"code": 429, "message": (
    "Quota exceeded for metric: generate_content_free_tier_requests, limit: 20, model: gemini-2.5-flash, "
    "description: Generate content requests per day limit exceeded on Free Tier. Please retry in 2h0m0s."
)}})


def ok(text):
    return httpx.Response(200, json={
        "candidates": [{"content": {"parts": [{"text": text}]}}],
        "usageMetadata": {"promptTokenCount": 10, "candidatesTokenCount": 5},
    })


@pytest.fixture
def calls(monkeypatch):
    """Fake Google: each test sets responses per model; records which models were called."""
    state = {"responses": {}, "called": [], "bodies": {}}

    def handler(request: httpx.Request) -> httpx.Response:
        model = request.url.path.split("/models/")[1].split(":")[0]
        state["called"].append(model)
        state["bodies"][model] = json.loads(request.content)
        return state["responses"].get(model, httpx.Response(404))

    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    monkeypatch.setattr(gemini_mod, "shared_client", lambda: client)
    return state


@pytest.fixture
def gemini(settings):
    keys = FakeKeys()
    keys.set("gemini", "test-key")
    settings.update({"gemini_chains": {
        "quick": ["lite", "flash"],
        "reasoning": ["flash", "lite"],
        "vision": ["lite"],
    }})
    return GeminiProvider(settings, keys)


async def test_quick_and_reasoning_use_different_chains(gemini, calls):
    calls["responses"] = {"lite": ok("lite answer"), "flash": ok("flash answer")}
    assert (await gemini.complete(LLMRequest("hi", task="general"))).model == "lite"
    assert (await gemini.complete(LLMRequest("solve", task="coding"))).model == "flash"


async def test_moves_down_chain_on_429_and_learns_limit(gemini, calls):
    calls["responses"] = {"flash": httpx.Response(429, text=LIMIT_429), "lite": ok("from lite")}
    result = await gemini.complete(LLMRequest("solve", task="coding"))
    assert result.model == "lite" and result.text == "from lite"
    assert "gemini flash: free limit reached" in result.fallbacks
    assert gemini.quota.limit("flash") == 20
    assert gemini.quota.blocked_until("flash")

    calls["called"].clear()
    await gemini.complete(LLMRequest("again", task="coding"))
    assert calls["called"] == ["lite"]  # flash is skipped without a network call


async def test_daily_count_stops_before_google_does(gemini, calls, settings):
    settings.update({"gemini_daily_limits": {"lite": 2}})
    calls["responses"] = {"lite": ok("a"), "flash": ok("b")}
    models = [(await gemini.complete(LLMRequest("x"))).model for _ in range(3)]
    assert models == ["lite", "lite", "flash"]
    assert gemini.quota.used("lite") == 2


async def test_unknown_model_skipped(gemini, calls):
    calls["responses"] = {"flash": ok("b")}  # lite returns 404
    result = await gemini.complete(LLMRequest("x"))
    assert result.model == "flash"
    assert "gemini lite: not offered for this key" in result.fallbacks


async def test_bad_key_stops_immediately(gemini, calls):
    calls["responses"] = {"lite": httpx.Response(400, text="API key not valid. Please pass a valid API key.")}
    with pytest.raises(ProviderError, match="key rejected"):
        await gemini.complete(LLMRequest("x"))
    assert calls["called"] == ["lite"]


async def test_all_exhausted_does_not_block_other_chains(gemini, calls):
    calls["responses"] = {"lite": httpx.Response(429, text=LIMIT_429), "flash": httpx.Response(429, text=LIMIT_429)}
    with pytest.raises(ProviderError) as e:
        await gemini.complete(LLMRequest("x"))
    assert "at its limit" in str(e.value) and e.value.retry_after is None


def test_gemma_gets_system_prompt_in_message(gemini):
    body = gemini.body(LLMRequest("hello", system="Be Ronie."), "gemma-3-27b-it")
    assert "systemInstruction" not in body
    assert body["contents"][0]["parts"][0]["text"].startswith("Be Ronie.")


def test_quota_persists_across_restarts(settings, home):
    q = GeminiQuota(settings)
    q.record_use("lite")
    q.block("flash", 3600)
    again = GeminiQuota(settings)
    assert again.used("lite") == 1 and again.blocked_until("flash")


def test_thinking_setting_matches_model_family(gemini):
    body = lambda model: gemini.body(LLMRequest("hi"), model)["generationConfig"].get("thinkingConfig")
    assert body("gemini-2.5-flash-lite") == {"thinkingBudget": 0}
    assert body("gemini-3.5-flash-lite") == {"thinkingLevel": "low"}
    assert body("gemma-4-26b-a4b-it") == {"thinkingLevel": "minimal"}
    assert body("lite") is None
    careful = gemini.body(LLMRequest("x", task="coding"), "gemini-3.5-flash")["generationConfig"]
    assert "thinkingConfig" not in careful and careful["maxOutputTokens"] >= 4096


async def test_slow_model_is_skipped_on_quick_requests(gemini, monkeypatch, settings):
    def handler(request):
        if "/models/lite:" in request.url.path:
            raise httpx.ReadTimeout("slow", request=request)
        return ok("from flash")

    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    monkeypatch.setattr(gemini_mod, "shared_client", lambda: client)
    result = await gemini.complete(LLMRequest("hi"))
    assert result.model == "flash" and "gemini lite: too slow, skipped" in result.fallbacks


async def test_empty_answer_moves_on(gemini, calls):
    calls["responses"] = {"lite": ok("   "), "flash": ok("real answer")}
    assert (await gemini.complete(LLMRequest("x"))).text == "real answer"
