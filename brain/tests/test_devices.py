import json
import subprocess

import pytest

from ronie_brain.devices import TV_KEYS, DeviceError, Laptop, TV


class FakeRun:
    """Stands in for subprocess.run: answers Get-StartApps, records everything else."""

    def __init__(self, apps):
        self.apps, self.calls = apps, []

    def __call__(self, args, **kw):
        self.calls.append(args)
        out = json.dumps(self.apps) if "Get-StartApps" in " ".join(args) else ""
        return subprocess.CompletedProcess(args, 0, stdout=out, stderr="")


@pytest.fixture
def laptop():
    opened = []
    run = FakeRun([{"Name": "Visual Studio Code", "AppID": "Microsoft.VisualStudioCode"},
                   {"Name": "Google Chrome", "AppID": "Chrome"}, {"Name": "Spotify", "AppID": "SpotifyAB.Spotify"},
                   {"Name": "Notepad", "AppID": "Microsoft.WindowsNotepad!App"}])
    lap = Laptop(opener=opened.append, runner=run)
    lap._app_paths = lambda: {"brave": r"C:\Brave\brave.exe"}  # not this computer's real programs
    lap.opened, lap.run = opened, run
    return lap


def test_laptop_opens_sites_addresses_and_searches(laptop):
    assert laptop.open("YouTube", "website")["url"] == "https://www.youtube.com"
    assert laptop.open("github.com/anthropics", "")["url"] == "https://github.com/anthropics"
    assert laptop.open("cheap flights to Goa", "search")["url"].endswith("q=cheap+flights+to+Goa")
    assert "search_query=arijit+singh" in laptop.open("arijit singh", "youtube")["url"]
    assert laptop.open("something unheard of", "")["url"].startswith("https://www.google.com/search?q=")


def test_laptop_opens_apps_by_spoken_name(laptop):
    assert laptop.open("vs code", "app") == {"done": "Opened visual studio code"}
    assert laptop.run.calls[-1] == ["explorer.exe", "shell:AppsFolder\\Microsoft.VisualStudioCode"]
    assert laptop.open("chrome", "")["done"] == "Opened google chrome"
    assert laptop.open("notepad", "app")["done"] == "Opened notepad"
    assert laptop.open("brave", "app")["done"] == "Opened brave" and laptop.opened[-1] == r"C:\Brave\brave.exe"
    assert laptop.open("whatsapp", "app")["url"] == "https://web.whatsapp.com"  # no desktop app: the web version
    with pytest.raises(DeviceError):
        laptop.open("photoshop", "app")


def test_laptop_opens_folders(laptop):
    r = laptop.open("downloads", "folder")
    assert r["path"].endswith("Downloads") and laptop.opened[-1] == r["path"]


async def test_tv_keys_apps_and_reconnect(settings):
    settings.update({"tv_host": "192.168.0.50"})
    calls, state = [], {"connected": False}

    async def adb(args):
        calls.append(args)
        if args[0] == "connect":
            state["connected"] = True
            return 0, "connected to 192.168.0.50:5555"
        if args[-1] == "get-state":
            return 0, "device"
        if not state["connected"]:
            return 1, "error: device '192.168.0.50:5555' not found"
        if args[3:6] == ["pm", "list", "packages"]:
            return 0, "package:com.netflix.ninja\npackage:com.google.android.youtube.tv\npackage:in.startv.hotstar"
        return 0, ""

    tv = TV(settings, runner=adb)
    assert (await tv.control("volume_up", times=3))["done"] == "volume up x3"
    assert ["connect", "192.168.0.50:5555"] in calls  # it reconnected after the first try failed
    assert calls[-1] == ["-s", "192.168.0.50:5555", "shell", "input", "keyevent"] + [TV_KEYS["volume_up"]] * 3
    assert (await tv.control("open_app", "Netflix"))["done"] == "Opened Netflix on the TV"
    assert calls[-1][3:6] == ["monkey", "-p", "com.netflix.ninja"]
    await tv.control("open_app", "hotstar")
    assert calls[-1][5] == "in.startv.hotstar"
    await tv.control("youtube_search", "lofi beats")
    assert "search_query=lofi+beats" in calls[-1][-1] and "com.google.android.youtube.tv" in calls[-1]
    with pytest.raises(DeviceError):
        await tv.control("open_app", "Photoshop")


async def test_tv_needs_a_host(settings):
    with pytest.raises(DeviceError, match="IP address"):
        await TV(settings, runner=None).control("home")


async def test_tools_route_device_calls(settings, budget, home):
    from conftest import FakeKeys
    from ronie_brain.memory import Memory
    from ronie_brain.models import Router
    from ronie_brain.tools import Tools
    from ronie_brain.web import Web

    opened = []

    router = Router(settings, {}, budget)
    lap = Laptop(opener=opened.append, runner=FakeRun([]))
    lap._app_paths = lambda: {}
    tools = Tools(router, Memory(settings), Web(FakeKeys()), laptop=lap)
    assert (await tools.run("laptop", {"target": "gmail", "kind": "website"}))["done"] == "Opening gmail"
    assert "error" in await tools.run("tv", {"action": "home"})  # no TV set up: a spoken error, not a crash


async def test_tv_finds_its_new_port_after_a_restart(settings, monkeypatch):
    """Wireless debugging picks a new port when the TV restarts: Ronie finds it and saves it."""
    settings.update({"tv_host": "192.168.0.159:40361"})
    calls = []

    async def adb(args):
        calls.append(args)
        if args == ["connect", "192.168.0.159:40361"]:
            return 1, "failed to connect to 192.168.0.159:40361"
        if args == ["mdns", "services"]:
            return 0, "List of discovered mdns services\nadb-0000-x\t_adb-tls-connect._tcp\t192.168.0.159:37011"
        if args == ["connect", "192.168.0.159:37011"]:
            return 0, "connected to 192.168.0.159:37011"
        if args[-1] == "get-state":
            return 0, "device"
        if args[3:6] == ["pm", "list", "packages"]:
            return 0, "package:com.google.android.youtube.tv"
        return 0, ""

    monkeypatch.setattr("ronie_brain.devices.YOUTUBE_RESULTS_S", 0)
    tv = TV(settings, runner=adb)
    tv.fetch = lambda url: ""  # YouTube can't be read: falls back to search + OK
    assert await tv.connect() == {"connected": True, "tv": "192.168.0.159:37011"}
    assert settings["tv_host"] == "192.168.0.159:37011"
    assert (await tv.control("youtube_play", "lofi"))["done"] == "Playing lofi on YouTube"
    assert calls[-1][-1] == TV_KEYS["ok"]  # pressed OK on the first result


async def test_tv_search_inside_apps(settings, monkeypatch):
    """Search and play inside JioHotstar (via the title's web page), Stremio (its own link) and the app on screen."""
    settings.update({"tv_host": "192.168.0.159:5555"})
    monkeypatch.setattr("ronie_brain.devices.COLD_START_S", 0)
    monkeypatch.setattr("ronie_brain.devices.PAGE_LOAD_S", 0)
    calls, running = [], {"in.startv.hotstar": False}

    async def adb(args):
        calls.append(args)
        sh = args[3:]
        if sh[:3] == ["pm", "list", "packages"]:
            return 0, "package:in.startv.hotstar\npackage:com.stremio.one\npackage:com.google.android.youtube.tv"
        if sh[:1] == ["pidof"]:
            assert sh[2:] == ["||", "true"]  # pidof alone exits with an error when the app isn't running
            return 0, "4242" if running[sh[1]] else ""
        if sh[:1] == ["monkey"]:
            running[sh[2]] = True
        if sh[:2] == ["dumpsys", "activity"]:
            return 0, "  topResumedActivity=ActivityRecord{a9e3 u0 com.stremio.one/.MainActivity t12}"
        return 0, ""

    async def web_search(q):
        assert q == "site:hotstar.com Bigg Boss"
        return [{"url": "https://www.hotstar.com/in/shows/bigg-boss/1971002586/bbhin-day-19/1271702319/watch"}]

    tv = TV(settings, runner=adb, web_search=web_search)
    r = await tv.control("play", "Bigg Boss", app="Jio Hotstar")  # spoken with a space
    assert r["done"] == "Playing Bigg Boss on Jio Hotstar"
    assert r["page"] == "https://www.hotstar.com/in/shows/bigg-boss/1971002586"  # the show page: "watch latest"
    shells = [c[3:] for c in calls]
    launch = ["monkey", "-p", "in.startv.hotstar", "-c", "android.intent.category.LEANBACK_LAUNCHER", "1"]
    assert shells.index(launch) < next(i for i, c in enumerate(shells) if c[:2] == ["am", "start"])  # cold app opened before the link
    assert shells[-1] == ["input", "keyevent", TV_KEYS["ok"]]
    r = await tv.control("search", "Odyssey", app="Streamio")  # misheard name
    assert r["done"] == "Showing Stremio results for Odyssey" and "stremio:///search?search=Odyssey" in calls[-1][-1]
    r = await tv.control("search", "Odyssey")  # no app named: the one on screen (Stremio)
    assert r["done"] == "Showing Stremio results for Odyssey"


def test_bing_results_are_decoded():
    import base64

    from ronie_brain.web import parse_bing

    url = "https://www.hotstar.com/in/shows/bigg-boss/1971002586"
    coded = base64.urlsafe_b64encode(url.encode()).decode().rstrip("=")
    page = (f'<li class="b_algo"><h2><a href="https://www.bing.com/ck/a?!&amp;&amp;p=x&amp;u=a1{coded}&amp;ntb=1">'
            f'Bigg Boss - <strong>JioHotstar</strong></a></h2><p>New episode daily</p></li>')
    assert parse_bing(page) == [{"title": "Bigg Boss - JioHotstar", "url": url, "snippet": "New episode daily"}]


TV_INPUT_DUMP = """
  inputMap: inputId -> TvInputState
    com.mediatek.tis/.HdmiInputService/HDMI100004: info: TvInputInfo{id=com.mediatek.tis/.HdmiInputService/HDMI100004}; state: 1
    TvInputHardwareInfo {id=2, type=9, audio_type=0, audio_addr=null, hdmi_port=1, cable_connection_status=0}
    TvInputHardwareInfo {id=3, type=9, audio_type=0, audio_addr=null, hdmi_port=2, cable_connection_status=0}
  mHdmiDeviceList:
    CEC: logical_address: 0x04 device_type: 4 cec_version: 5 vendor_id: 524358 display_name: PlayStation 5 power_status: 1 physical_address: 0x1000 port_id: 1
  mHardwareInputIdMap: deviceId -> inputId
    2: com.mediatek.tis/.HdmiInputService/HW2
    3: com.mediatek.tis/.HdmiInputService/HW3
  mHdmiInputIdMap: id -> inputId
    4: com.mediatek.tis/.HdmiInputService/HDMI100004
"""


async def test_tv_switches_to_the_playstation(settings):
    settings.update({"tv_host": "192.168.0.159:5555"})
    calls = []

    async def adb(args):
        calls.append(args)
        return 0, TV_INPUT_DUMP if args[3:5] == ["dumpsys", "tv_input"] else ""

    tv = TV(settings, runner=adb)
    assert await tv.inputs() == {"hdmi 1": "com.mediatek.tis/.HdmiInputService/HW2",
                                 "hdmi 2": "com.mediatek.tis/.HdmiInputService/HW3",
                                 "playstation 5": "com.mediatek.tis/.HdmiInputService/HDMI100004"}
    assert (await tv.control("switch_input", "PS5"))["done"] == "Switched the TV to PS5"
    assert calls[-2][-1] == TV_KEYS["turn_on"]
    assert calls[-1][-1] == "'content://android.media.tv/passthrough/com.mediatek.tis%2F.HdmiInputService%2FHDMI100004'"
    await tv.control("switch_input", "HDMI 2")
    assert calls[-1][-1].endswith("HdmiInputService%2FHW3'")
    with pytest.raises(DeviceError, match="found: hdmi 1, hdmi 2, playstation 5"):
        await tv.control("switch_input", "Xbox")


def test_laptop_plays_youtube_and_uses_the_chosen_browser(laptop):
    launched = []
    laptop._launch = launched.append
    laptop._fetch = lambda url: 'xx "videoId":"0sZF6YzhrRU" yy' if "fred+again" in url.lower() else ""
    r = laptop.open("Fred again songs", "youtube_play", browser="brave")
    assert r == {"done": "Playing Fred again songs on YouTube in brave", "url": "https://www.youtube.com/watch?v=0sZF6YzhrRU"}
    assert launched[-1] == [r"C:\Brave\brave.exe", "https://www.youtube.com/watch?v=0sZF6YzhrRU"]
    assert laptop.open("ronakpatil.com", "website", browser="brave")["url"] == "https://ronakpatil.com"
    assert launched[-1][1] == "https://ronakpatil.com"
    assert laptop.open("nothing found here", "youtube_play")["done"].startswith("Couldn't start a video")
    assert laptop.opened[-1].startswith("https://www.youtube.com/results")  # default browser when none is chosen


def test_claude_code_opens_claude_not_vs_code():
    run = FakeRun([{"Name": "Claude", "AppID": "Claude_x!Claude"}, {"Name": "Visual Studio Code", "AppID": "VSCode"}])
    lap = Laptop(opener=lambda t: None, runner=run)
    lap._app_paths = lambda: {"code": r"C:\VSCode\Code.exe"}
    assert lap.find_app("claude code") == ("claude", "Claude_x!Claude")
    assert lap.find_app("cloud code") == ("claude", "Claude_x!Claude")  # how speech-to-text hears it
    assert lap.find_app("vs code") == ("visual studio code", "VSCode")


async def test_tv_through_the_phone_remote(settings):
    """With a paired phone, TV commands become remote keys and links; adb isn't used."""
    settings.update({"tv_host": "192.168.0.159:40361", "tv_cache": {"tv_input": TV_INPUT_DUMP,
                                                                    "packages": "package:in.startv.hotstar"}})
    sent = []

    async def remote(host, ops):
        sent.append((host, ops))
        return {"foreground": "com.gathin.tvhome"}

    async def no_adb(args):
        raise AssertionError("adb must not be used")

    tv = TV(settings, runner=no_adb, remote=remote)
    await tv.control("volume_up", times=2)
    assert sent[-1] == ("192.168.0.159", [{"key": 24}, {"key": 24}])
    await tv.control("switch_input", "PlayStation")
    assert sent[-1][1] == [{"link": "content://android.media.tv/passthrough/com.mediatek.tis%2F.HdmiInputService%2FHDMI100004"}]
    await tv.control("open_app", "Jio Hotstar")
    assert sent[-1][1] == [{"link": "https://www.hotstar.com/in"}]


async def test_tv_falls_back_to_adb_without_the_phone(settings):
    settings.update({"tv_host": "192.168.0.159:5555"})
    calls = []

    async def remote(host, ops):
        raise DeviceError("No phone with the TV remote set up is connected")

    async def adb(args):
        calls.append(args)
        return 0, ""

    await TV(settings, runner=adb, remote=remote).control("home")
    assert calls[-1][-1] == TV_KEYS["home"]


async def test_tv_plays_the_youtube_video_itself(settings, monkeypatch):
    """Play on YouTube opens the top video's watch link (it starts by itself), opening YouTube first if it's closed."""
    settings.update({"tv_host": "192.168.0.159:5555"})
    monkeypatch.setattr("ronie_brain.devices.YOUTUBE_COLD_START_S", 0)
    sent, state = [], {"foreground": "com.gathin.tvhome"}

    async def remote(host, ops):
        sent.append(ops)
        for op in ops:
            if "link" in op and "youtube" in op["link"]:
                state["foreground"] = "com.google.android.youtube.tv"
        return dict(state)

    tv = TV(settings, runner=None, remote=remote)
    tv.fetch = lambda url: '... "videoId":"0sZF6YzhrRU" ...' if "lofi" in url else ""
    r = await tv.control("play", "lofi music", app="YouTube")
    assert r["done"] == "Playing lofi music on YouTube"
    links = [op["link"] for ops in sent for op in ops if "link" in op]
    assert links == ["https://www.youtube.com", "https://www.youtube.com/watch?v=0sZF6YzhrRU"]  # opened, then the video
    assert not any("key" in op for ops in sent for op in ops)  # no OK press needed


def _results_page(*videos, shorts=()):
    """A YouTube results page: normal videos (id, title) plus Shorts, which come first, as they often do."""
    import json as _json

    items = [{"reelItem": {"reelWatchEndpoint": {"videoId": s}}} for s in shorts]
    items += [{"videoRenderer": {"videoId": v, "title": {"runs": [{"text": t}]}, "lengthText": {"simpleText": "3:01"}}}
              for v, t in videos]
    return f'<script>var ytInitialData = {_json.dumps({"contents": items})};</script>'


def test_youtube_pick_skips_shorts_and_what_was_played():
    from ronie_brain.devices import youtube_pick

    page = _results_page(("aaaaaaaaaaa", "Trailer one"), ("bbbbbbbbbbb", "Trailer two"), shorts=("sssssssssss",))
    assert youtube_pick("trailers", lambda url: page)["video"] == "aaaaaaaaaaa"
    pick = youtube_pick("trailers", lambda url: page, skip=["aaaaaaaaaaa"])
    assert pick == {"video": "bbbbbbbbbbb", "title": "Trailer two", "list": ""}


def test_youtube_playlist_plays_on_through_the_list():
    from ronie_brain.devices import youtube_link, youtube_pick

    page = '"watchEndpoint":{"videoId":"ccccccccccc","playlistId":"RDmix"} "watchEndpoint":{"videoId":"ddddddddddd","playlistId":"PLlofi123"}'
    pick = youtube_pick("lofi playlist", lambda url: page if "sp=" in url else "", playlist=True)
    assert youtube_link(pick) == "https://www.youtube.com/watch?v=ddddddddddd&list=PLlofi123"


async def test_play_another_one_on_the_tv_doesnt_repeat(settings, monkeypatch):
    settings.update({"tv_host": "192.168.0.159:5555"})

    async def remote(host, ops):
        return {"foreground": "com.google.android.youtube.tv"}

    tv = TV(settings, runner=None, remote=remote)
    tv.fetch = lambda url: _results_page(("aaaaaaaaaaa", "Eetha trailer"), ("bbbbbbbbbbb", "Jumanji trailer"))
    first = await tv.control("play", "new movie trailers", app="YouTube")
    second = await tv.control("play", "official movie trailers", app="YouTube")
    assert first["title"] == "Eetha trailer" and second["title"] == "Jumanji trailer"


def test_spoken_volume_levels():
    from ronie_brain.devices import volume_target

    assert volume_target("15", 100) == 15 and volume_target("half", 30) == 15 and volume_target("आधा", 30) == 15
    assert volume_target("30%", 50) == 15 and volume_target("max", 40) == 40 and volume_target("200", 40) == 40
    assert volume_target("loud", 40) is None


async def test_set_volume_presses_down_to_the_exact_level(settings):
    settings.update({"tv_host": "192.168.0.159:5555"})
    sent = []

    async def remote(host, ops):
        sent.extend(ops)
        return {"foreground": "", "volume": 22, "volume_max": 100}

    tv = TV(settings, runner=None, remote=remote)
    r = await tv.control("set_volume", "15")
    assert r["volume"] == 15 and len([o for o in sent if o.get("key") == 25]) == 7  # KEYCODE_VOLUME_DOWN x7


async def _tools_with(settings, where, screen_on=True):
    from ronie_brain.tools import Tools

    class R:
        pass

    r = R()
    r.settings = settings
    t = Tools.__new__(Tools)
    t.router = r
    calls = []

    async def availability():
        return where

    async def agent(tool, args):
        calls.append(("laptop", args))
        return {"done": "ok"}

    class FakeTV:
        async def screen_on(self):
            return screen_on

        async def find(self, query, app="", play=False):
            calls.append(("tv", query))
            return {"done": f"Playing {query} on YouTube"}

    class FakeWeb:
        async def search(self, q, n=6):
            return [{"url": "https://www.youtube.com/watch?v=x"}, {"url": "https://example.org/recipe"}]

    from ronie_brain.devices import Played

    class FakeLaptop:
        played = Played()
        _fetch = staticmethod(lambda url: _results_page(("vvvvvvvvvvv", "Black holes explained")))

    t.availability, t.laptop_agent, t.tv, t.web, t.laptop = availability, agent, FakeTV(), FakeWeb(), FakeLaptop()
    t._shown = ("", 0.0)
    return t, calls


async def test_show_plays_on_the_tv_when_home_and_its_on(settings):
    t, calls = await _tools_with(settings, {"home": True, "tv": True, "laptop": True})
    r = await t._show({"topic": "black holes explained", "kind": "video"})
    assert r["where"] == "TV" and calls == [("tv", "black holes explained")]


async def test_show_lays_out_a_video_and_a_page_on_the_laptop_when_the_tv_is_off(settings):
    t, calls = await _tools_with(settings, {"home": True, "tv": True, "laptop": True}, screen_on=False)
    r = await t._show({"topic": "black holes", "kind": "video"})
    assert r["where"] == "laptop" and r["video_title"] == "Black holes explained"
    assert calls == [("laptop", {"video_url": "https://www.youtube.com/watch?v=vvvvvvvvvvv",
                                 "page_url": "https://example.org/recipe", "browser": ""})]


async def test_show_follows_the_screen_the_user_faces(settings):
    t, calls = await _tools_with(settings, {"home": True, "tv": True, "laptop": True, "facing": "laptop"})
    assert (await t._show({"topic": "black holes", "kind": "video"}))["where"] == "laptop"  # not the TV behind them
    t, calls = await _tools_with(settings, {"home": True, "tv": True, "laptop": True, "facing": "tv"})
    assert (await t._show({"topic": "paneer recipe", "kind": "page"}))["where"] == "TV"
    t, calls = await _tools_with(settings, {"home": True, "tv": True, "laptop": True, "facing": "tv"}, screen_on=False)
    assert (await t._show({"topic": "black holes"})).get("skipped") and not calls  # TV off: not the laptop behind


async def test_show_does_nothing_quietly_away_from_home(settings):
    t, calls = await _tools_with(settings, {"home": False, "tv": False, "laptop": False})
    r = await t._show({"topic": "black holes", "kind": "video"})
    assert r.get("skipped") and not calls


def test_facing_from_the_vision_reply():
    from ronie_brain.app import facing_from

    assert facing_from("laptop - a keyboard and two monitors") == "laptop"
    assert facing_from("**TV** – a television on the wall") == "tv"
    assert facing_from("none - a kitchen") is None and facing_from("") is None


async def test_show_doesnt_open_the_same_topic_twice(settings):
    t, calls = await _tools_with(settings, {"home": True, "tv": True, "laptop": True, "facing": "laptop"})
    await t._show({"topic": "how LLMs work", "kind": "video"})
    again = await t._show({"topic": "How LLMs work", "kind": "page"})
    assert again.get("skipped") and len(calls) == 1
