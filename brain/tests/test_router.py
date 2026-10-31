import pytest

from conftest import FakeProvider
from ronie_brain.models import AllProvidersFailed, LLMRequest, Router


def make(settings, budget, **providers):
    return Router(settings, providers, budget)


async def test_uses_first_free_provider(settings, budget):
    router = make(settings, budget, gemini=FakeProvider("gemini"), groq=FakeProvider("groq"))
    result = await router.complete(LLMRequest("hi"))
    assert result.provider == "gemini"
    assert result.fallbacks == []


async def test_falls_back_when_provider_fails(settings, budget):
    groq = FakeProvider("groq")
    router = make(settings, budget, gemini=FakeProvider("gemini", fail=True), groq=groq)
    result = await router.complete(LLMRequest("hi"))
    assert result.provider == "groq"
    assert "gemini: down" in result.fallbacks


async def test_paid_provider_skipped_when_premium_off(settings, budget):
    claude = FakeProvider("claude", paid=True)
    router = make(settings, budget, claude=claude, gemini=FakeProvider("gemini"))
    result = await router.complete(LLMRequest("write a function", task="coding"))
    assert result.provider == "gemini"
    assert claude.calls == 0


async def test_paid_provider_used_and_costed_when_premium_on(settings, budget):
    settings.update({"premium": True})
    router = make(settings, budget, claude=FakeProvider("claude", paid=True, tokens=(1_000_000, 100_000)))
    result = await router.complete(LLMRequest("write a function", task="coding"))
    assert result.provider == "claude"
    # claude-sonnet-5-5 at $2 in / $10 out per million tokens
    assert result.cost_usd == pytest.approx(2.0 + 1.0)
    assert budget.spent("claude") == pytest.approx(3.0)


async def test_budget_cap_moves_to_free_provider(settings, budget):
    settings.update({"premium": True, "budgets_usd": {"claude": 1.0}})
    budget.record("claude", 1.5)
    router = make(settings, budget, claude=FakeProvider("claude", paid=True), gemini=FakeProvider("gemini"))
    result = await router.complete(LLMRequest("x", task="coding"))
    assert result.provider == "gemini"
    assert "claude: monthly budget used up" in result.fallbacks


async def test_request_premium_overrides_setting(settings, budget):
    router = make(settings, budget, claude=FakeProvider("claude", paid=True), gemini=FakeProvider("gemini"))
    result = await router.complete(LLMRequest("x", task="coding", premium=True))
    assert result.provider == "claude"


async def test_unknown_task_uses_general_route(settings, budget):
    router = make(settings, budget, gemini=FakeProvider("gemini"))
    assert (await router.complete(LLMRequest("x", task="cooking"))).provider == "gemini"


async def test_all_failed_reports_reasons(settings, budget):
    router = make(settings, budget, gemini=FakeProvider("gemini", fail=True), groq=FakeProvider("groq", ready=False))
    with pytest.raises(AllProvidersFailed) as e:
        await router.complete(LLMRequest("x"))
    assert "gemini: down" in str(e.value)
    assert "groq: not set up" in str(e.value)


async def test_rate_limited_provider_is_skipped_until_reset(settings, budget):
    from ronie_brain.models import ProviderError

    class Limited(FakeProvider):
        async def complete(self, req):
            self.calls += 1
            raise ProviderError("gemini: free limit reached", retry_after=3600)

    gemini, groq = Limited("gemini"), FakeProvider("groq")
    router = make(settings, budget, gemini=gemini, groq=groq)
    assert (await router.complete(LLMRequest("a"))).provider == "groq"
    second = await router.complete(LLMRequest("b"))
    assert second.provider == "groq" and gemini.calls == 1
    assert any("limit reached, back at" in f for f in second.fallbacks)
    assert router.cooling_until("gemini")


def test_retry_after_parses_google_message():
    import httpx
    from ronie_brain.models.providers import retry_after_seconds

    r = httpx.Response(429, text='"Please retry in 6h21m44.2s."')
    assert retry_after_seconds(r) == pytest.approx(6 * 3600 + 21 * 60 + 44.2)
    assert retry_after_seconds(httpx.Response(429, headers={"retry-after": "30"})) == 30
