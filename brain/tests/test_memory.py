import json

import pytest

from conftest import FakeKeys, FakeProvider
from ronie_brain.memory import Compactor, Memory, parse_json_object
from ronie_brain.models import LLMRequest, Router
from ronie_brain.tools import Tools
from ronie_brain.web import Web, html_to_text, parse_duckduckgo


@pytest.fixture
def memory(settings, home):
    return Memory(settings, home / "memory.db")


def test_remember_dedupes_and_searches(memory):
    a = memory.remember("My car is parked in lot B, level 3.", "place", 5)
    b = memory.remember("my car is parked in Lot B level 3", "place", 3)
    assert a == b and len(memory.facts()) == 1
    memory.remember("Mom's birthday is on 12 March.", "person", 4)
    found = memory.search("where did I park the car")
    assert [f["text"] for f in found["facts"]] == ["My car is parked in lot B, level 3."]


def test_context_has_facts_summary_recent_turns_and_date(memory):
    memory.remember("The user's name is Asha.", "fact", 5)
    memory.set_summary("Yesterday they planned a Goa trip for December.")
    memory.add_turn("user", "chai ya coffee?", "voice")
    memory.add_turn("ronie", "Chai, obviously.", "voice")
    ctx = memory.context("goa")
    assert "Current date and time:" in ctx
    assert "- The user's name is Asha." in ctx
    assert "Goa trip" in ctx
    assert "User: chai ya coffee?\nRonie: Chai, obviously." in ctx
    assert "Never mention a memory system" in ctx


async def test_every_provider_gets_the_same_memory(settings, budget, memory):
    """Switching providers mid-conversation must not change what the model knows."""
    memory.remember("The user's car is in lot B, level 3.", "place", 5)
    seen = {}

    class Recorder(FakeProvider):
        async def complete(self, req):
            seen[self.name] = req.system
            return await super().complete(req)

    router = Router(settings, {"gemini": Recorder("gemini", fail=True), "groq": Recorder("groq")}, budget)
    router.memory_context = memory.context
    result = await router.complete(LLMRequest("where is my car?"))
    assert result.provider == "groq"
    assert "lot B, level 3" in seen["gemini"] and seen["gemini"] == seen["groq"]


async def test_raw_requests_skip_persona_and_memory(settings, budget, memory):
    seen = {}

    class Recorder(FakeProvider):
        async def complete(self, req):
            seen["system"] = req.system
            return await super().complete(req)

    router = Router(settings, {"groq": Recorder("groq")}, budget)
    router.memory_context = memory.context
    await router.complete(LLMRequest("summarize", task="memory", raw=True))
    assert seen["system"] is None


async def test_compaction_folds_old_turns_into_summary_and_facts(settings, memory):
    settings.update({"memory_keep_turns": 2})
    for i in range(6):
        memory.add_turn("user" if i % 2 == 0 else "ronie", f"message {i}", "voice")
    memory.remember("Old fact", "note", 2)
    old_id = memory.facts()[0]["id"]
    prompts = []

    async def fake_llm(prompt):
        prompts.append(prompt)
        return "Sure!\n```json\n" + json.dumps({
            "summary": "They chatted about messages 0 to 3.",
            "new_facts": [{"text": "The user's sister is called Priya.", "kind": "person", "importance": 4}],
            "updated_facts": [],
            "obsolete_fact_ids": [old_id],
        }) + "\n```"

    result = await Compactor(memory, settings, fake_llm).compact()
    assert result == {"folded": 4, "facts_added": 1}
    assert "message 3" in prompts[0] and "message 4" not in prompts[0]
    assert memory.summary() == "They chatted about messages 0 to 3."
    assert [f["text"] for f in memory.facts()] == ["The user's sister is called Priya."]
    assert [t["text"] for t in memory.uncompacted()] == ["message 4", "message 5"]


async def test_failed_compaction_keeps_turns(settings, memory):
    settings.update({"memory_keep_turns": 0})
    memory.add_turn("user", "hello", "voice")

    async def broken(prompt):
        return "not json"

    result = await Compactor(memory, settings, broken).compact()
    assert result["folded"] == 0 and len(memory.uncompacted()) == 1


async def test_compaction_scheduled_when_conversation_grows(settings, memory):
    settings.update({"memory_compact_tokens": 10, "memory_keep_turns": 0})
    calls = []

    async def fake_llm(prompt):
        calls.append(prompt)
        return json.dumps({"summary": "s", "new_facts": []})

    compactor = Compactor(memory, settings, fake_llm)
    memory.add_turn("user", "x" * 200, "voice")
    compactor.maybe_schedule()
    await compactor.task
    assert calls


async def test_facts_found_after_a_short_quiet_chat(settings, memory):
    # A short chat never reaches the compaction size, but its facts are saved once it goes quiet.
    settings.update({"memory_fact_delay": 0})
    prompts = []

    async def fake_llm(prompt):
        prompts.append(prompt)
        return json.dumps({"new_facts": [{"text": "The user supports Real Madrid.", "kind": "preference", "importance": 4}]})

    compactor = Compactor(memory, settings, fake_llm)
    memory.add_turn("user", "I'm a fan of Real Madrid.", "voice")
    memory.add_turn("ronie", "Nice, they're flying this season.", "voice")
    compactor.note_turn()
    await compactor.facts_task
    assert "I'm a fan of Real Madrid." in prompts[0] and "battery" in prompts[0]  # junk rules are in the prompt
    assert [f["text"] for f in memory.facts()] == ["The user supports Real Madrid."]
    assert not memory.uncompacted_tokens() > settings["memory_compact_tokens"]  # no compaction was needed

    # The next look only reads what's new; Ronie talking alone costs no model call.
    memory.add_turn("ronie", "Anything else?", "voice")
    assert await compactor.extract_facts() == {"scanned": 1, "facts_added": 0}
    assert len(prompts) == 1


def test_parse_json_object_handles_wrapped_json():
    assert parse_json_object('Here: {"a": {"b": 1}} done') == {"a": {"b": 1}}


async def test_tools_remember_recall_forget(settings, budget, memory):
    tools = Tools(Router(settings, {}, budget), memory, Web(FakeKeys()))
    assert await tools.run("remember", {"text": "Wifi password is on the fridge."}) == {"saved": True}
    recall = await tools.run("recall", {"query": "wifi password"})
    assert recall["facts"] == ["Wifi password is on the fridge."]
    assert (await tools.run("forget", {"query": "wifi"}))["forgotten"] == 1
    assert memory.facts() == []
    assert "unknown tool" in (await tools.run("launch_rocket", {}))["error"]


async def test_research_reads_sources_and_answers(settings, budget, memory):
    class FakeWeb:
        async def gather_sources(self, query):
            return [{"title": "F1 live", "url": "https://example.com/f1", "snippet": "", "text": "Lap 30: Verstappen leads."}]

        research_prompt = staticmethod(Web.research_prompt)

    seen = {}

    class Recorder(FakeProvider):
        async def complete(self, req):
            seen["prompt"], seen["task"] = req.prompt, req.task
            return await super().complete(req)

    tools = Tools(Router(settings, {"groq": Recorder("groq", reply="Verstappen leads on lap 30.")}, budget), memory, FakeWeb())
    result = await tools.run("research_web", {"question": "Who is leading the F1 race?", "query": "F1 live race"})
    assert result["answer"] == "Verstappen leads on lap 30."
    assert result["sources"] == [{"title": "F1 live", "url": "https://example.com/f1"}]
    assert "Lap 30: Verstappen leads." in seen["prompt"] and seen["task"] == "research"


def test_parse_duckduckgo_results():
    page = '''
    <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fwww.formula1.com%2Flive&amp;rut=x">F1 <b>Live</b></a>
    <a class="result__snippet" href="#">Live timing &amp; updates</a>
    <a class="result__a" href="https://duckduckgo.com/y.js?ad=1">Ad</a>
    '''
    assert parse_duckduckgo(page) == [{"title": "F1 Live", "url": "https://www.formula1.com/live", "snippet": "Live timing & updates"}]


def test_html_to_text_drops_scripts_and_tags():
    assert html_to_text("<script>x()</script><p>Lap&nbsp;30</p><p>Leader: VER</p>") == "Lap\xa030\nLeader: VER"
