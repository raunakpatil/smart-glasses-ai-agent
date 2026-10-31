import json

import httpx
import pytest

from conftest import FakeKeys
from ronie_brain.models import LLMRequest, ProviderError
from ronie_brain.models import providers as prov
from ronie_brain.models.providers import OpenAICompatProvider, strip_thinking


def reply(text):
    return httpx.Response(200, json={"choices": [{"message": {"content": text}}], "usage": {"prompt_tokens": 3, "completion_tokens": 2}})


@pytest.fixture
def fake(monkeypatch):
    state = {"responses": {}, "bodies": []}

    def handler(request):
        body = json.loads(request.content)
        state["bodies"].append(body)
        return state["responses"].get(body["model"], httpx.Response(404))

    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    monkeypatch.setattr(prov, "shared_client", lambda: client)
    return state


@pytest.fixture
def groq(settings):
    keys = FakeKeys()
    keys.set("groq", "k")
    return OpenAICompatProvider("groq", settings, keys, "https://api.groq.com/openai/v1", paid=False)


async def test_groq_picks_model_by_request_type(groq, fake):
    fake["responses"] = {"openai/gpt-oss-20b": reply("fast"), "openai/gpt-oss-120b": reply("careful")}
    assert (await groq.complete(LLMRequest("hi", task="general"))).model == "openai/gpt-oss-20b"
    assert (await groq.complete(LLMRequest("code", task="coding"))).model == "openai/gpt-oss-120b"
    quick, careful = fake["bodies"]
    assert quick["reasoning_effort"] == "low" and careful["reasoning_effort"] == "medium"
    assert careful["max_tokens"] >= 4096


async def test_groq_moves_on_when_model_missing_or_limited(groq, fake):
    fake["responses"] = {
        "openai/gpt-oss-20b": httpx.Response(429, headers={"retry-after": "120"}),
        "qwen/qwen3.8-27b": reply("<think>hmm</think>Chai."),
    }
    result = await groq.complete(LLMRequest("chai ya coffee?"))
    assert result.model == "qwen/qwen3.8-27b" and result.text == "Chai."
    assert "groq openai/gpt-oss-20b: limit reached" in result.fallbacks
    assert fake["bodies"][-1]["reasoning_format"] == "hidden"


async def test_groq_has_no_vision_model(groq, fake):
    with pytest.raises(ProviderError, match="no model for vision"):
        await groq.complete(LLMRequest("what is this?", task="vision"))


async def test_single_model_providers_still_work(settings, fake):
    keys = FakeKeys()
    keys.set("deepseek", "k")
    ds = OpenAICompatProvider("deepseek", settings, keys, "https://api.deepseek.com", paid=True)
    fake["responses"] = {"deepseek-chat": reply("ok")}
    result = await ds.complete(LLMRequest("x"))
    assert result.model == "deepseek-chat" and "reasoning_effort" not in fake["bodies"][-1]


def test_strip_thinking():
    assert strip_thinking("<think>\nlong\n</think>\n\nAnswer") == "Answer"


async def test_openrouter_uses_free_chain_and_hides_reasoning(settings, fake):
    keys = FakeKeys()
    keys.set("openrouter", "k")
    orr = OpenAICompatProvider("openrouter", settings, keys, "https://openrouter.ai/api/v1", paid=False)
    fake["responses"] = {"google/gemma-4-26b-a4b-it:free": reply("Chai!")}  # first quick model 404s
    result = await orr.complete(LLMRequest("chai ya coffee?"))
    assert result.model == "google/gemma-4-26b-a4b-it:free"
    assert fake["bodies"][-1]["reasoning"] == {"exclude": True, "effort": "low"}
    assert all(m.endswith(":free") for chain in settings["model_chains"]["openrouter"].values() for m in chain)
