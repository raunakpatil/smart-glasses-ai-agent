from conftest import FakeKeys, FakeProvider
from ronie_brain.models import LLMRequest, Router
from ronie_brain.models.providers import GeminiProvider
from ronie_brain.persona import clean_spoken, system_prompt


def test_clean_spoken_removes_markdown():
    text = "Mere liye toh **chai** ka mazaa hai!\n- *garam* chai\n## Bonus\nSee [menu](http://x.y) `now`"
    assert clean_spoken(text) == "Mere liye toh chai ka mazaa hai! garam chai Bonus See menu now"


def test_clean_spoken_keeps_plain_text_and_math():
    assert clean_spoken("2 * 3 = 6, aur 10 - 4 = 6.") == "2 * 3 = 6, aur 10 - 4 = 6."


def test_system_prompt_follows_settings(settings):
    auto = system_prompt("general", settings)
    assert "Ronie" in auto and "same language" in auto and "no markdown" in auto
    settings.update({"language": "hindi", "script": "devanagari", "humor": "off"})
    hindi = system_prompt("general", settings)
    assert "Always reply in Hindi" in hindi and "Devanagari" in hindi and "Do not make jokes" in hindi


def test_no_jokes_in_interview_and_code_allowed_in_coding(settings):
    settings.update({"humor": "normal"})
    assert "Do not make jokes" in system_prompt("interview", settings)
    assert "code block" in system_prompt("coding", settings)


async def test_router_adds_persona_and_cleans_spoken_answers(settings, budget):
    seen = {}

    class Recorder(FakeProvider):
        async def complete(self, req):
            seen["system"] = req.system
            return await super().complete(req)

    router = Router(settings, {"gemini": Recorder("gemini", reply="**Chai**, obviously.")}, budget)
    result = await router.complete(LLMRequest("chai ya coffee?", system="User is in Pune."))
    assert seen["system"].startswith("You are Ronie") and seen["system"].endswith("User is in Pune.")
    assert result.text == "Chai, obviously."


async def test_coding_answers_keep_code(settings, budget):
    reply = "Use a set.\n```python\nseen = set()\n```"
    router = Router(settings, {"gemini": FakeProvider("gemini", reply=reply)}, budget)
    assert (await router.complete(LLMRequest("dedupe", task="coding"))).text == reply


def test_gemini_skips_thinking_only_for_quick_tasks(settings):
    gemini = GeminiProvider(settings, FakeKeys())
    quick = gemini.body(LLMRequest("hi", task="general"), "gemini-2.5-flash")
    assert quick["generationConfig"]["thinkingConfig"] == {"thinkingBudget": 0}
    assert "thinkingConfig" not in gemini.body(LLMRequest("solve", task="coding"), "gemini-2.5-flash")["generationConfig"]
    assert "thinkingConfig" not in gemini.body(LLMRequest("hi"), "unknown-model")["generationConfig"]


def test_voice_tools_stay_complete(settings):
    """Every voice feature needs its tool here; a missing one silently breaks it (laptop, TV, battery, games…)."""
    from ronie_brain.persona import LIVE_FUNCTIONS, LIVE_TOOLS

    names = {f["name"] for f in LIVE_FUNCTIONS}
    assert {"ask_brain", "look", "remember", "recall", "forget", "research_web", "laptop", "laptop_control", "tv",
            "battery", "game"} <= names
    assert "Chalo bye" in LIVE_TOOLS and "Call game" in LIVE_TOOLS


def test_ronie_speaks_with_the_voices_gender_and_knows_local_time(settings):
    from datetime import datetime, timedelta, timezone

    from ronie_brain.persona import identity, live_config

    at = datetime(2026, 10, 31, 0, 7, tzinfo=timezone(timedelta(hours=5, minutes=30)))
    settings.update({"live_voice": "Charon"})
    assert "masculine" in identity(settings, at) and "12:07 AM" in identity(settings, at)
    assert "Saturday 31 October 2026" in identity(settings, at)
    settings.update({"live_voice": "Kore"})
    assert "feminine" in identity(settings, at)
    system = live_config(settings)["system"]
    assert "clock" in system and "Sorry, kya bola" in system
    assert "clock" in {f["name"] for f in live_config(settings)["functions"]}


async def test_clock_tool_uses_the_users_time_zone_not_the_servers(settings):
    from ronie_brain.tools import Tools

    class R:
        pass

    r = R()
    r.settings = settings
    tools = Tools.__new__(Tools)
    tools.router = r
    out = await tools._clock({})
    assert out["timezone"] == "Asia/Kolkata" and out["time"].endswith(("AM", "PM")) and out["day"]


async def test_laptop_accepts_query_as_the_target(settings):
    from ronie_brain.tools import Tools

    calls = []

    async def agent(tool, args):
        calls.append(args)
        return {"done": "ok"}

    class R:
        pass

    r = R()
    r.settings = settings
    tools = Tools.__new__(Tools)
    tools.router, tools.laptop_agent = r, agent
    await tools._laptop({"kind": "youtube_play", "query": "lofi hip hop"})
    assert calls[0]["target"] == "lofi hip hop"



def test_modes_can_be_switched_by_voice(settings):
    from ronie_brain.persona import live_config

    for mode in ("assistant", "interview_practice"):
        fns = {f["name"]: f for f in live_config(settings, mode=mode)["functions"]}
        assert "Interview mode" in fns["set_mode"]["parameters"]["properties"]["mode"]["enum"]


def test_show_is_offered_only_when_home_with_a_screen(settings):
    from ronie_brain.persona import live_config

    def names(devices):
        return {f["name"] for f in live_config(settings, devices=devices)["functions"]}

    assert "show" not in names(None)
    assert "show" not in names({"home": False, "tv": False, "laptop": False})
    assert "show" not in names({"home": True, "tv": False, "laptop": False})
    assert "show" in names({"home": True, "tv": True, "laptop": False})
    system = live_config(settings, devices={"home": True, "tv": False, "laptop": True})["system"]
    assert "near the laptop" in system and "the TV" not in system.split("near")[1][:20]
    assert "Ronie" in live_config(settings)["vocabulary"] and len(live_config(settings)["vocabulary"]) <= 100
