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


# ---------- Hindi, local time, time labels, topics, expiry, forget, search by meaning ----------

def test_hindi_in_devanagari_is_searchable(memory):
    memory.remember("मेरी गाड़ी लेवल 3 पर खड़ी है", "place", 5)
    found = memory.search("मेरी गाड़ी कहाँ है?")
    assert [f["text"] for f in found["facts"]] == ["मेरी गाड़ी लेवल 3 पर खड़ी है"]
    # The same wording with different punctuation is the same memory, in Hindi too.
    memory.remember("मेरी गाड़ी लेवल 3 पर खड़ी है।", "place", 5)
    assert len(memory.facts()) == 1


def test_context_and_compaction_use_the_users_time_zone(settings, memory):
    from ronie_brain.config import local_now

    settings.update({"timezone": "Pacific/Kiritimati"})  # UTC+14: a different day from the server for 14 hours
    now = local_now(settings)
    assert f"Current date and time: {now.strftime('%A %d %B %Y').replace(' 0', ' ')}" in memory.context()
    assert now.date().isoformat() in Compactor(memory, settings, None).today()


def test_old_turns_carry_when_they_were_said(memory):
    memory.add_turn("user", "I'm at the airport.", "voice")
    memory.add_turn("ronie", "Safe flight!", "voice")
    memory.db.execute("UPDATE turns SET ts = ts - 2 * 86400")  # two days ago
    memory.add_turn("user", "chai ya coffee?", "voice")
    ctx = memory.context()
    labels = [line for line in ctx.splitlines() if line.startswith("[")]
    assert len(labels) == 2 and labels[1].startswith("[today")  # one before the old pair, one after the pause
    assert "may not be true now" in ctx
    assert ctx.index(labels[0]) < ctx.index("I'm at the airport.") < ctx.index(labels[1]) < ctx.index("chai ya coffee?")


def test_a_new_fact_on_the_same_topic_replaces_the_old_one(memory):
    memory.remember("The user's car is parked on level 3, pillar B12.", "place", 5, topic="Parking spot")
    memory.remember("Mom's birthday is on 12 March.", "person", 4)
    memory.remember("The user's car is parked on level 2, pillar A4.", "place", 5, topic="parking spot")
    assert sorted(f["text"] for f in memory.facts()) == [
        "Mom's birthday is on 12 March.", "The user's car is parked on level 2, pillar A4."]


def test_expired_memories_are_dropped(settings, memory):
    from datetime import timedelta

    from ronie_brain.config import local_now

    today = local_now(settings).date()
    memory.remember("The user parked on level 3.", "place", 5, expires=(today - timedelta(days=1)).isoformat())
    memory.remember("The user flies to Goa on Friday.", "plan", 4, expires=today.isoformat())
    memory.remember("The user's sister is Priya.", "person", 4, expires="next week")  # not a date: kept for good
    ctx = memory.context()
    assert "level 3" not in ctx and "Goa" in ctx
    assert sorted(f["text"] for f in memory.facts()) == ["The user flies to Goa on Friday.", "The user's sister is Priya."]
    assert next(f for f in memory.facts() if "Priya" in f["text"])["expires"] == ""


def test_recent_facts_make_the_prompt_even_when_many_are_more_important(settings, memory):
    settings.update({"memory_max_facts": 3})
    for i in range(5):
        memory.remember(f"Important old fact {i}.", "fact", 5)
    memory.db.execute("UPDATE facts SET updated = updated - 30 * 86400")
    memory.remember("The user started a new job at a bakery.", "fact", 2)
    assert "new job at a bakery" in memory.context()


def test_session_context_picks_facts_about_what_was_said_lately(settings, memory):
    settings.update({"memory_max_facts": 2})
    memory.remember("Important fact one.", "fact", 5)
    memory.remember("Important fact two.", "fact", 5)
    memory.remember("The user's dog is called Bruno.", "person", 1)
    memory.db.execute("UPDATE facts SET updated = updated - 30 * 86400")
    memory.add_turn("user", "Bruno ko walk pe le jana hai", "voice")
    assert "Bruno" in memory.context()  # the words of the recent chat count as the question


async def test_forget_asks_which_one_when_several_fit(settings, budget, memory):
    tools = Tools(Router(settings, {}, budget), memory, Web(FakeKeys()))
    memory.remember("Mom's birthday is on 12 March.", "person", 4)
    memory.remember("Sister Priya's birthday is on 4 May.", "person", 4)
    asked = await tools.run("forget", {"query": "birthday"})
    assert asked["forgotten"] == 0 and len(asked["which"]) == 2 and len(memory.facts()) == 2
    # All the words must fit first: "mom birthday" is only mom's, even though "birthday" fits both.
    assert (await tools.run("forget", {"query": "mom birthday"}))["text"] == "Mom's birthday is on 12 March."
    sister = memory.facts()[0]
    assert (await tools.run("forget", {"query": "birthday", "id": str(sister["id"])}))["forgotten"] == 1
    assert memory.facts() == []
    assert (await tools.run("forget", {"query": "birthday"}))["forgotten"] == 0


async def test_forget_checks_first_when_only_the_meaning_fits(settings, budget, memory):
    memory.embedder = fake_embedder()
    memory.remember("The user's car is parked on level 3.", "place", 5)
    tools = Tools(Router(settings, {}, budget), memory, Web(FakeKeys()))
    asked = await tools.run("forget", {"query": "gaadi wali baat"})
    assert asked["forgotten"] == 0 and [w["text"] for w in asked["which"]] == ["The user's car is parked on level 3."]
    assert len(memory.facts()) == 1


def fake_embedder(calls=None):
    """Vectors by meaning, for a handful of ideas: a Hinglish question lands next to the English memory."""
    ideas = {"car": ("car", "gaadi", "गाड़ी", "parked"), "food": ("coffee", "chai", "roast"), "pet": ("dog", "kutta")}

    async def embed(texts, task):
        if calls is not None:
            calls.append((len(texts), task))
        return [[1.0 if any(w in t.lower() for w in words) else 0.0 for words in ideas.values()] + [0.1] for t in texts]

    return embed


async def test_recall_finds_an_english_memory_from_a_hinglish_question(settings, budget, memory):
    calls = []
    memory.embedder = fake_embedder(calls)
    memory.remember("The user's car is parked on level 3, pillar B12.", "place", 5)
    memory.remember("The user prefers dark roast coffee.", "preference", 3)
    tools = Tools(Router(settings, {}, budget), memory, Web(FakeKeys()))
    recall = await tools.run("recall", {"query": "meri gaadi kahan hai"})
    assert recall["facts"] == ["The user's car is parked on level 3, pillar B12."]
    assert calls == [(2, "RETRIEVAL_DOCUMENT"), (1, "RETRIEVAL_QUERY")]
    await tools.run("recall", {"query": "chai"})
    assert calls[2:] == [(1, "RETRIEVAL_QUERY")]  # saved memories are embedded once, not on every search


async def test_search_by_meaning_steps_aside_when_it_fails(memory):
    async def broken(texts, task):
        raise OSError("no network")

    memory.embedder = broken
    memory.remember("The user's car is parked on level 3.", "place", 5)
    assert [f["text"] for f in (await memory.find("car parked"))["facts"]] == ["The user's car is parked on level 3."]
    assert memory._embed_off_until > 0  # words only for a while, so recall stays quick


async def test_facts_carry_topic_and_expiry_from_the_fact_check(settings, memory):
    settings.update({"memory_fact_delay": 0})

    async def fake_llm(prompt):
        assert "topic" in prompt and "expires" in prompt and "in English" in prompt
        return json.dumps({"new_facts": [{"text": "The user's car is parked on level 2.", "kind": "place",
                                          "importance": 4, "topic": "parking spot", "expires": "2099-01-01"}]})

    memory.remember("The user's car is parked on level 3.", "place", 4, topic="parking spot")
    memory.add_turn("user", "Gaadi level 2 pe lagayi hai.", "voice")
    await Compactor(memory, settings, fake_llm).extract_facts()
    [fact] = memory.facts()
    assert fact["text"] == "The user's car is parked on level 2." and fact["expires"] == "2099-01-01"


def test_an_old_database_gets_the_new_columns(settings, home):
    import sqlite3

    path = home / "old.db"
    db = sqlite3.connect(path)
    db.execute("CREATE TABLE facts (id INTEGER PRIMARY KEY, ts REAL NOT NULL, updated REAL NOT NULL, text TEXT NOT NULL, "
               "kind TEXT NOT NULL DEFAULT 'note', importance INTEGER NOT NULL DEFAULT 3, source TEXT NOT NULL DEFAULT '')")
    db.execute("INSERT INTO facts (ts, updated, text) VALUES (1, 1, 'Old fact')")
    db.commit()
    db.close()
    m = Memory(settings, path)
    assert m.facts()[0]["topic"] == "" and m.facts()[0]["expires"] == ""
    m.remember("New fact", topic="x", expires="2099-01-01")
