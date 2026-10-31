"""Control things around the user: this laptop (the brain runs on it) and an Android / Google TV over Wi-Fi (ADB).
All free, no accounts."""
from __future__ import annotations

import asyncio
import collections
import ctypes
import difflib
import json
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path
from urllib.parse import quote, quote_plus

from .config import Settings


class DeviceError(Exception):
    pass


# ---------- laptop ----------

SITES = {
    "youtube": "https://www.youtube.com", "gmail": "https://mail.google.com", "google": "https://www.google.com",
    "chatgpt": "https://chatgpt.com", "claude": "https://claude.ai", "gemini": "https://gemini.google.com",
    "whatsapp": "https://web.whatsapp.com", "github": "https://github.com", "linkedin": "https://www.linkedin.com",
    "netflix": "https://www.netflix.com", "spotify": "https://open.spotify.com", "maps": "https://maps.google.com",
    "google maps": "https://maps.google.com", "calendar": "https://calendar.google.com",
    "drive": "https://drive.google.com", "google drive": "https://drive.google.com", "docs": "https://docs.google.com",
    "meet": "https://meet.google.com", "google meet": "https://meet.google.com", "instagram": "https://www.instagram.com",
    "twitter": "https://x.com", "x": "https://x.com", "amazon": "https://www.amazon.in", "flipkart": "https://www.flipkart.com",
    "hotstar": "https://www.hotstar.com", "jiohotstar": "https://www.hotstar.com", "prime video": "https://www.primevideo.com",
    "reddit": "https://www.reddit.com", "stack overflow": "https://stackoverflow.com", "wikipedia": "https://www.wikipedia.org",
    "f1": "https://www.formula1.com", "formula 1": "https://www.formula1.com", "cricbuzz": "https://www.cricbuzz.com",
    "outlook": "https://outlook.live.com", "notion": "https://www.notion.so", "figma": "https://www.figma.com",
}
FOLDERS = {"downloads": "Downloads", "documents": "Documents", "desktop": "Desktop", "pictures": "Pictures",
           "photos": "Pictures", "music": "Music", "videos": "Videos", "home": ""}
SETTINGS_PAGES = {"settings": "ms-settings:", "bluetooth": "ms-settings:bluetooth", "wifi": "ms-settings:network-wifi",
                  "wi-fi": "ms-settings:network-wifi", "display": "ms-settings:display", "sound": "ms-settings:sound",
                  "battery": "ms-settings:batterysaver"}
DOMAIN = re.compile(r"^(https?://)?([\w-]+\.)+[a-z]{2,}(/\S*)?$", re.I)

# Virtual-key codes for media and volume keys.
KEYS = {"play_pause": 0xB3, "next_track": 0xB0, "previous_track": 0xB1, "stop_media": 0xB2,
        "volume_up": 0xAF, "volume_down": 0xAE, "mute": 0xAD}
LAPTOP_ACTIONS = sorted(KEYS) + ["lock_screen", "show_desktop"]


def _clean(name: str) -> str:
    return re.sub(r"\s+", " ", re.sub(r"[^\w\s.+-]", " ", name.lower())).strip()


def _get_page(url: str) -> str:
    import httpx

    headers = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                             "Chrome/130.0 Safari/537.36", "Accept-Language": "en"}
    return httpx.get(url, headers=headers, timeout=8, follow_redirects=True).text


def first_youtube_video(query: str, fetch=None) -> str | None:
    """The first video id on YouTube's results page for a search (None if it can't be read)."""
    pick = youtube_pick(query, fetch)
    return pick["video"] if pick else None


# YouTube's search filter for playlists only.
PLAYLISTS_FILTER = "&sp=EgIQAw%253D%253D"


def youtube_pick(query: str, fetch=None, skip=(), playlist: bool = False) -> dict | None:
    """What to play for a YouTube search: {"video", "title", "list"}. Skips Shorts (the first match on the page was
    often a Short) and anything in skip (played already: "play another one" kept getting the same video, 31 Oct).
    playlist: the first playlist's first video, with its list id so it plays on through the playlist."""
    url = f"https://www.youtube.com/results?search_query={quote_plus(query)}"
    try:
        page = (fetch or _get_page)(url + (PLAYLISTS_FILTER if playlist else ""))
    except Exception:
        return None
    if playlist:
        for video, list_id in re.findall(r'"watchEndpoint":\{"videoId":"([\w-]{11})","playlistId":"([\w-]+)"', page):
            if list_id.startswith("PL") and list_id not in skip:
                return {"video": video, "list": list_id, "title": ""}
    videos = _youtube_videos(page)
    for v in videos:
        if v["video"] not in skip:
            return v
    if videos:  # everything here was played already: start again from the top
        return videos[0]
    m = re.search(r'"videoId":"([A-Za-z0-9_-]{11})"', page)  # couldn't read the page's data: first id on it
    return {"video": m.group(1), "title": "", "list": ""} if m else None


def _youtube_videos(page: str) -> list[dict]:
    """Normal videos (not Shorts, not live streams) from a results page, in order."""
    m = re.search(r"var ytInitialData = (\{.*?\});</script>", page, re.S)
    if not m:
        return []
    try:
        data = json.loads(m.group(1))
    except ValueError:
        return []
    out: list[dict] = []

    def walk(o) -> None:
        if isinstance(o, dict):
            v = o.get("videoRenderer")
            if isinstance(v, dict) and v.get("videoId") and v.get("lengthText"):  # live streams have no length
                runs = (v.get("title") or {}).get("runs") or [{}]
                out.append({"video": v["videoId"], "title": runs[0].get("text", ""), "list": ""})
            for x in o.values():
                walk(x)
        elif isinstance(o, list):
            for x in o:
                walk(x)

    walk(data)
    return out


def youtube_link(pick: dict) -> str:
    return f"https://www.youtube.com/watch?v={pick['video']}" + (f"&list={pick['list']}" if pick.get("list") else "")


def wants_playlist(query: str) -> bool:
    return bool(re.search(r"\bplaylists?\b", query, re.I))


class Played:
    """The last videos and playlists Ronie played, so "play another one" doesn't repeat."""

    def __init__(self) -> None:
        self.items: collections.deque[str] = collections.deque(maxlen=40)

    def add(self, pick: dict) -> None:
        self.items.append(pick["list"] or pick["video"])


def displays() -> list[dict]:
    """The screens on this Windows laptop: {"name", "work": (left, top, right, bottom), "internal"}. internal is the
    laptop's own screen (Windows says which output is built in)."""
    if sys.platform != "win32":
        return []
    from ctypes import wintypes

    user32 = ctypes.windll.user32
    try:
        user32.SetProcessDpiAwarenessContext(ctypes.c_void_p(-4))  # real pixels on every screen
    except Exception:
        pass

    class MonitorInfo(ctypes.Structure):
        _fields_ = [("cbSize", wintypes.DWORD), ("rcMonitor", wintypes.RECT), ("rcWork", wintypes.RECT),
                    ("dwFlags", wintypes.DWORD), ("szDevice", ctypes.c_wchar * 32)]

    found: list[dict] = []
    proc = ctypes.WINFUNCTYPE(ctypes.c_int, wintypes.HMONITOR, wintypes.HDC, ctypes.POINTER(wintypes.RECT),
                              wintypes.LPARAM)

    def each(monitor, _dc, _rect, _data):
        info = MonitorInfo()
        info.cbSize = ctypes.sizeof(MonitorInfo)
        user32.GetMonitorInfoW(monitor, ctypes.byref(info))
        w = info.rcWork
        found.append({"name": info.szDevice, "work": (w.left, w.top, w.right, w.bottom), "internal": False})
        return 1

    user32.EnumDisplayMonitors(0, 0, proc(each), 0)
    built_in = _internal_display_names()
    for d in found:
        d["internal"] = d["name"] in built_in
    return found


def _internal_display_names() -> set[str]:
    """Windows names (like DISPLAY1) of the built-in screens, from the display configuration."""
    from ctypes import wintypes

    class Luid(ctypes.Structure):
        _fields_ = [("LowPart", wintypes.DWORD), ("HighPart", wintypes.LONG)]

    class Source(ctypes.Structure):
        _fields_ = [("adapterId", Luid), ("id", ctypes.c_uint32), ("modeInfoIdx", ctypes.c_uint32),
                    ("statusFlags", ctypes.c_uint32)]

    class Rational(ctypes.Structure):
        _fields_ = [("n", ctypes.c_uint32), ("d", ctypes.c_uint32)]

    class Target(ctypes.Structure):
        _fields_ = [("adapterId", Luid), ("id", ctypes.c_uint32), ("modeInfoIdx", ctypes.c_uint32),
                    ("outputTechnology", ctypes.c_uint32), ("rotation", ctypes.c_uint32),
                    ("scaling", ctypes.c_uint32), ("refreshRate", Rational), ("scanLineOrdering", ctypes.c_uint32),
                    ("targetAvailable", ctypes.c_int), ("statusFlags", ctypes.c_uint32)]

    class Path(ctypes.Structure):
        _fields_ = [("sourceInfo", Source), ("targetInfo", Target), ("flags", ctypes.c_uint32)]

    class Mode(ctypes.Structure):
        _fields_ = [("infoType", ctypes.c_uint32), ("id", ctypes.c_uint32), ("adapterId", Luid),
                    ("data", ctypes.c_byte * 48)]

    class Header(ctypes.Structure):
        _fields_ = [("type", ctypes.c_uint32), ("size", ctypes.c_uint32), ("adapterId", Luid),
                    ("id", ctypes.c_uint32)]

    class SourceName(ctypes.Structure):
        _fields_ = [("header", Header), ("viewGdiDeviceName", ctypes.c_wchar * 32)]

    user32 = ctypes.windll.user32
    n_paths, n_modes = ctypes.c_uint32(), ctypes.c_uint32()
    active_only = 2
    if user32.GetDisplayConfigBufferSizes(active_only, ctypes.byref(n_paths), ctypes.byref(n_modes)):
        return set()
    paths, modes = (Path * n_paths.value)(), (Mode * n_modes.value)()
    if user32.QueryDisplayConfig(active_only, ctypes.byref(n_paths), paths, ctypes.byref(n_modes), modes, None):
        return set()
    internal = set()
    for p in paths[:n_paths.value]:
        if p.targetInfo.outputTechnology & 0xFFFFFFFF != 0x80000000:  # DISPLAYCONFIG_OUTPUT_TECHNOLOGY_INTERNAL
            continue
        name = SourceName()
        name.header.type, name.header.size = 1, ctypes.sizeof(SourceName)  # GET_SOURCE_NAME
        name.header.adapterId, name.header.id = p.sourceInfo.adapterId, p.sourceInfo.id
        if not user32.DisplayConfigGetDeviceInfo(ctypes.byref(name)):
            internal.add(name.viewGdiDeviceName)
    return internal


def browser_windows(program: str) -> list[int]:
    """Visible top-level windows belonging to a browser program (by its file name)."""
    if sys.platform != "win32":
        return []
    from ctypes import wintypes

    user32, kernel32 = ctypes.windll.user32, ctypes.windll.kernel32
    exe = Path(program).name.lower()
    out: list[int] = []
    proc = ctypes.WINFUNCTYPE(ctypes.c_bool, wintypes.HWND, wintypes.LPARAM)

    def each(hwnd, _):
        if not user32.IsWindowVisible(hwnd) or user32.GetWindow(hwnd, 4):  # skip hidden and owned windows
            return True
        pid = wintypes.DWORD()
        user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
        handle = kernel32.OpenProcess(0x1000, False, pid.value)  # PROCESS_QUERY_LIMITED_INFORMATION
        if handle:
            buf, size = ctypes.create_unicode_buffer(520), wintypes.DWORD(520)
            if kernel32.QueryFullProcessImageNameW(handle, 0, buf, ctypes.byref(size)) and \
                    Path(buf.value).name.lower() == exe:
                out.append(int(hwnd))
            kernel32.CloseHandle(handle)
        return True

    user32.EnumWindows(proc(each), 0)
    return out


def place_window(hwnd: int, rect: tuple[int, int, int, int], maximise: bool = False) -> None:
    """Moves a window into rect (a screen's work area or part of it), maximised there if asked."""
    user32 = ctypes.windll.user32
    left, top, right, bottom = rect
    user32.ShowWindow(hwnd, 9)  # SW_RESTORE: a maximised window can't be moved
    user32.SetWindowPos(hwnd, 0, left, top, right - left, bottom - top, 0x0004 | 0x0040)  # NOZORDER, SHOWWINDOW
    if maximise:
        user32.ShowWindow(hwnd, 3)  # SW_MAXIMIZE, on the screen it's now on


class Laptop:
    """Opens websites, apps, folders and searches, and presses media keys, on the laptop the brain runs on."""

    def __init__(self, opener=None, runner=None, launcher=None, fetcher=None) -> None:
        self._open = opener or self._startfile  # injectable for tests
        self._run = runner or subprocess.run
        self._launch = launcher or (lambda args: subprocess.Popen(args))  # start a program and don't wait for it
        self._fetch = fetcher or _get_page
        self._apps: dict[str, str] | None = None
        self._apps_at = 0.0
        self.played = Played()

    @staticmethod
    def _startfile(target: str) -> None:
        if sys.platform != "win32":
            subprocess.Popen(["xdg-open" if sys.platform.startswith("linux") else "open", target])
        else:
            os.startfile(target)  # noqa: S606 (opening what the user asked for is the point)

    def apps(self) -> dict[str, str]:
        """Installed apps from the Start menu: lower-case name -> launch id (cached for 10 minutes)."""
        if self._apps is not None and time.time() - self._apps_at < 600:
            return self._apps
        apps: dict[str, str] = {}
        if sys.platform == "win32":
            r = self._run(["powershell", "-NoProfile", "-Command", "Get-StartApps | ConvertTo-Json -Compress"],
                          capture_output=True, text=True, timeout=20)
            try:
                rows = json.loads(r.stdout or "[]")
            except json.JSONDecodeError:
                rows = []
            for row in rows if isinstance(rows, list) else [rows]:
                if row.get("Name") and row.get("AppID"):
                    apps[row["Name"].lower()] = row["AppID"]
            for name, exe in self._app_paths().items():  # programs without a Start-menu entry (e.g. Brave)
                apps.setdefault(name, f"path:{exe}")
        self._apps, self._apps_at = apps, time.time()
        return apps

    @staticmethod
    def _app_paths() -> dict[str, str]:
        """Windows' registry of installed programs by file name ("brave" -> its brave.exe)."""
        try:
            import winreg
        except ImportError:
            return {}
        found: dict[str, str] = {}
        for hive in (winreg.HKEY_CURRENT_USER, winreg.HKEY_LOCAL_MACHINE):
            try:
                key = winreg.OpenKey(hive, r"Software\Microsoft\Windows\CurrentVersion\App Paths")
            except OSError:
                continue
            for i in range(10_000):
                try:
                    exe_name = winreg.EnumKey(key, i)
                    path = winreg.QueryValue(key, exe_name).strip('"')
                except OSError:
                    break
                if exe_name.lower().endswith(".exe") and path:
                    found.setdefault(exe_name[:-4].lower(), path)
        return found

    def find_app(self, name: str) -> tuple[str, str] | None:
        """Best match for a spoken app name: (display name, launch id)."""
        want = _clean(name)
        if not want:
            return None
        apps = self.apps()
        names = {_clean(n): n for n in apps}
        if want in names:
            n = names[want]
            return n, apps[n]
        # "word" matches: "vs code" ~ "visual studio code", "chrome" ~ "google chrome"
        starts = [n for c, n in names.items() if c.startswith(want) or f" {want}" in f" {c}"]
        if starts:
            n = min(starts, key=len)
            return n, apps[n]
        aliases = {"vs code": "visual studio code", "vscode": "visual studio code", "word": "word",
                   "claude code": "claude", "cloud code": "claude", "cloud": "claude", "claude ai": "claude",
                   "claude desktop": "claude", "cloud ai": "claude",
                   "excel": "excel", "powerpoint": "powerpoint", "terminal": "terminal", "edge": "microsoft edge",
                   "file explorer": "file explorer", "explorer": "file explorer", "camera": "camera"}
        if want in aliases and aliases[want] != want:
            return self.find_app(aliases[want])
        close = difflib.get_close_matches(want, list(names), n=1, cutoff=0.75)
        if close:
            n = names[close[0]]
            return n, apps[n]
        first, _, rest = want.partition(" ")
        if rest and first in ("google", "microsoft", "adobe", "windows"):  # "google chrome" -> "chrome"
            return self.find_app(rest)
        return None

    def open(self, target: str, kind: str = "", browser: str = "") -> dict:
        """Open whatever the user named: a website, an app, a folder, a settings page, a web search, or a YouTube video
        to play. browser: open web pages in this browser instead of the default one."""
        self._browser = self._find_browser(browser) if browser.strip() else None
        target = target.strip()
        key = _clean(target).removeprefix("the ").removeprefix("my ").removesuffix(" website").removesuffix(" app")
        key = key.removesuffix(" folder").strip()
        if not target:
            raise DeviceError("Nothing to open")
        if kind == "search":
            return self._url(f"https://www.google.com/search?q={quote_plus(target)}", f"Searching for {target}")
        if kind == "youtube":
            return self._url(f"https://www.youtube.com/results?search_query={quote_plus(target)}",
                             f"Searching YouTube for {target}")
        if kind == "youtube_play":
            pick = youtube_pick(target, self._fetch, self.played.items, wants_playlist(target))
            if pick is None:  # couldn't read the results: show them instead
                return self._url(f"https://www.youtube.com/results?search_query={quote_plus(target)}",
                                 f"Couldn't start a video, so showing YouTube results for {target}")
            self.played.add(pick)
            done = self._url(youtube_link(pick), f"Playing {'a playlist of ' if pick['list'] else ''}{target} on YouTube")
            return {**done, "title": pick["title"]} if pick["title"] else done
        if kind in ("", "website") and key in SITES:
            return self._url(SITES[key], f"Opening {target}")
        if DOMAIN.match(target.replace(" ", "")) and kind in ("", "website"):
            url = target.replace(" ", "")
            return self._url(url if url.startswith("http") else f"https://{url}", f"Opening {url}")
        if kind in ("", "folder") and key in FOLDERS:
            path = Path.home() / FOLDERS[key]
            self._open(str(path))
            return {"done": f"Opened {key}", "path": str(path)}
        if kind in ("", "app") and key in SETTINGS_PAGES:
            self._open(SETTINGS_PAGES[key])
            return {"done": f"Opened {key} settings"}
        if kind in ("", "app"):
            found = self.find_app(key)
            if found:
                name, app_id = found
                if app_id.startswith("path:"):
                    self._open(app_id[5:])
                else:
                    self._run(["explorer.exe", f"shell:AppsFolder\\{app_id}"], timeout=10)
                return {"done": f"Opened {name}"}
            if kind == "app" and key in SITES:  # e.g. WhatsApp without the desktop app: the web version
                return self._url(SITES[key], f"Opening {target} on the web")
            if kind == "app":
                raise DeviceError(f"I couldn't find an app called {target} on the laptop")
        if kind == "website":
            return self._url(f"https://www.google.com/search?q={quote_plus(target)}&btnI=1", f"Opening {target}")
        # Not a site, folder or app we know: search for it.
        return self._url(f"https://www.google.com/search?q={quote_plus(target)}", f"Searching for {target}")

    def _url(self, url: str, done: str) -> dict:
        browser = getattr(self, "_browser", None)
        if browser:
            self._run_detached([browser[1], url])
            done += f" in {browser[0]}"
        else:
            self._open(url)
        return {"done": done, "url": url}

    def _find_browser(self, name: str) -> tuple[str, str] | None:
        """(name, program path) for a browser installed as a program, e.g. Brave or Chrome; None = default browser."""
        found = self.find_app(name)
        if found and found[1].startswith("path:"):
            return found[0], found[1][5:]
        return None

    def _run_detached(self, args: list[str]) -> None:
        self._launch(args)

    def show(self, video_url: str = "", page_url: str = "", browser: str = "") -> dict:
        """Opens a video and a page about one topic in their own browser windows, placed for reading: with two
        displays, the page on the laptop's own screen and the video on the other; with one, side by side."""
        found = self._find_browser(browser or "brave") if sys.platform == "win32" else None
        urls = [u for u in (page_url, video_url) if u]
        if not urls:
            raise DeviceError("Nothing to show")
        if found is None:  # no browser program to open windows with: just open them
            for u in urls:
                self._open(u)
            return {"done": "Opened it on the laptop"}
        screens = displays()
        built_in = next((d for d in screens if d["internal"]), screens[0] if screens else None)
        other = next((d for d in screens if d is not built_in), None)
        placed = []
        for url, is_video in ((page_url, False), (video_url, True)):
            if not url:
                continue
            hwnd = self._new_window(found[1], url)
            if hwnd and built_in:
                if other:  # page on the laptop's screen, video on the monitor, both maximised
                    place_window(hwnd, (other if is_video else built_in)["work"], maximise=True)
                else:  # one screen: video on the left half, page on the right
                    left, top, right, bottom = built_in["work"]
                    mid = (left + right) // 2
                    place_window(hwnd, (left, top, mid, bottom) if is_video else (mid, top, right, bottom))
            placed.append("a video" if is_video else "a page")
        where = "the monitor and the laptop screen" if other else "the laptop, side by side"
        return {"done": f"Opened {' and '.join(placed)} on {where}"}

    def _new_window(self, program: str, url: str) -> int | None:
        """Opens url in a new browser window and returns that window (None if it didn't show up in time)."""
        before = set(browser_windows(program))
        self._run_detached([program, "--new-window", url])
        deadline = time.time() + 6
        while time.time() < deadline:
            time.sleep(0.25)
            new = [h for h in browser_windows(program) if h not in before]
            if new:
                time.sleep(0.3)  # let it finish appearing before moving it
                return new[0]
        return None

    def status(self) -> dict:
        """Seconds since the last keyboard or mouse input (None where that can't be read): is someone using it?"""
        if sys.platform != "win32":
            return {"idle_s": None}

        class LastInput(ctypes.Structure):
            _fields_ = [("cbSize", ctypes.c_uint), ("dwTime", ctypes.c_uint)]

        info = LastInput()
        info.cbSize = ctypes.sizeof(LastInput)
        if not ctypes.windll.user32.GetLastInputInfo(ctypes.byref(info)):
            return {"idle_s": None}
        return {"idle_s": round(((ctypes.windll.kernel32.GetTickCount() - info.dwTime) & 0xFFFFFFFF) / 1000)}

    def control(self, action: str, times: int = 1) -> dict:
        if sys.platform != "win32":
            raise DeviceError("Laptop control works on Windows only for now")
        user32 = ctypes.windll.user32
        if action == "lock_screen":
            user32.LockWorkStation()
            return {"done": "Locked the laptop"}
        if action == "show_desktop":
            win, d = 0x5B, 0x44  # Win+D
            user32.keybd_event(win, 0, 0, 0)
            user32.keybd_event(d, 0, 0, 0)
            user32.keybd_event(d, 0, 2, 0)
            user32.keybd_event(win, 0, 2, 0)
            return {"done": "Showing the desktop"}
        if action not in KEYS:
            raise DeviceError(f"Unknown laptop action {action}")
        steps = max(1, min(times, 25)) if action.startswith("volume") else 1
        for _ in range(steps):
            user32.keybd_event(KEYS[action], 0, 0, 0)
            user32.keybd_event(KEYS[action], 0, 2, 0)  # key up
        return {"done": action.replace("_", " ") + (f" x{steps}" if steps > 1 else "")}


# ---------- Android TV / Google TV (ADB over Wi-Fi) ----------

TV_KEYS = {
    "power": "KEYCODE_POWER", "turn_on": "KEYCODE_WAKEUP", "turn_off": "KEYCODE_SLEEP", "home": "KEYCODE_HOME",
    "back": "KEYCODE_BACK", "up": "KEYCODE_DPAD_UP", "down": "KEYCODE_DPAD_DOWN", "left": "KEYCODE_DPAD_LEFT",
    "right": "KEYCODE_DPAD_RIGHT", "ok": "KEYCODE_DPAD_CENTER", "play_pause": "KEYCODE_MEDIA_PLAY_PAUSE",
    "next": "KEYCODE_MEDIA_NEXT", "previous": "KEYCODE_MEDIA_PREVIOUS", "fast_forward": "KEYCODE_MEDIA_FAST_FORWARD",
    "rewind": "KEYCODE_MEDIA_REWIND", "volume_up": "KEYCODE_VOLUME_UP", "volume_down": "KEYCODE_VOLUME_DOWN",
    "mute": "KEYCODE_VOLUME_MUTE", "settings": "KEYCODE_SETTINGS", "input": "KEYCODE_TV_INPUT",
}
TV_APPS = {
    "youtube": ["com.google.android.youtube.tv", "com.google.android.youtube.googletv"],
    "netflix": ["com.netflix.ninja"], "prime video": ["com.amazon.amazonvideo.livingroom"],
    "amazon prime": ["com.amazon.amazonvideo.livingroom"], "hotstar": ["in.startv.hotstar", "in.startv.hotstaronly"],
    "jiohotstar": ["in.startv.hotstar"], "jio hotstar": ["in.startv.hotstar"], "jio": ["in.startv.hotstar"],
    "disney": ["in.startv.hotstar", "com.disney.disneyplus"], "stremio": ["com.stremio.one"],
    "spotify": ["com.spotify.tv.android"], "youtube music": ["com.google.android.youtube.tvmusic"],
    "jiocinema": ["com.jio.media.stb.ondemand"], "sony liv": ["com.sonyliv"], "zee5": ["com.graymatrix.did"],
    "plex": ["com.plexapp.android"], "vlc": ["org.videolan.vlc"], "play store": ["com.android.vending"],
}
APP_LABELS = {"in.startv.hotstar": "JioHotstar", "com.netflix.ninja": "Netflix", "com.stremio.one": "Stremio",
              "com.google.android.youtube.tv": "YouTube"}
TV_ACTIONS = sorted(TV_KEYS) + ["open_app", "search", "play", "switch_input", "type_text", "set_volume"]


def volume_target(value: str, top: int) -> int | None:
    """A spoken volume ("15", "half", "aadha", "30%", "max", "zero") as a level from 0 to top."""
    v = value.strip().lower()
    if v in ("half", "aadha", "aadhi", "adha", "\u0906\u0927\u093e", "50%"):
        return round(top / 2)
    if v in ("max", "maximum", "full", "poora", "pura", "100%"):
        return top
    if v in ("min", "minimum", "zero", "0", "mute"):
        return 0
    if m := re.fullmatch(r"(\d{1,3})\s*(%|percent|pratishat)", v):
        return round(top * min(int(m.group(1)), 100) / 100)
    if re.fullmatch(r"\d{1,3}", v):
        return min(int(v), top)
    return None
INPUT_ALIASES = {"ps5": "playstation", "ps4": "playstation", "ps": "playstation", "play station": "playstation",
                 "xbox": "xbox", "fire stick": "fire tv", "firestick": "fire tv", "set top box": "set-top box"}
# Android key codes, for the TV's own remote service (the phone presses keys through it).
KEY_CODES = {
    "KEYCODE_POWER": 26, "KEYCODE_WAKEUP": 224, "KEYCODE_SLEEP": 223, "KEYCODE_HOME": 3, "KEYCODE_BACK": 4,
    "KEYCODE_DPAD_UP": 19, "KEYCODE_DPAD_DOWN": 20, "KEYCODE_DPAD_LEFT": 21, "KEYCODE_DPAD_RIGHT": 22,
    "KEYCODE_DPAD_CENTER": 23, "KEYCODE_MEDIA_PLAY_PAUSE": 85, "KEYCODE_MEDIA_NEXT": 87, "KEYCODE_MEDIA_PREVIOUS": 88,
    "KEYCODE_MEDIA_FAST_FORWARD": 90, "KEYCODE_MEDIA_REWIND": 89, "KEYCODE_VOLUME_UP": 24, "KEYCODE_VOLUME_DOWN": 25,
    "KEYCODE_VOLUME_MUTE": 164, "KEYCODE_SETTINGS": 176, "KEYCODE_TV_INPUT": 178,
}
# Links that open each app (the remote service opens links, not package names).
APP_HOME_LINKS = {
    "com.google.android.youtube.tv": "https://www.youtube.com", "com.netflix.ninja": "https://www.netflix.com/browse",
    "in.startv.hotstar": "https://www.hotstar.com/in", "com.stremio.one": "stremio:///",
    "com.amazon.amazonvideo.livingroom": "https://app.primevideo.com", "com.spotify.tv.android": "https://open.spotify.com",
    "com.google.android.youtube.tvmusic": "https://music.youtube.com",
}
YOUTUBE_RESULTS_S = 4.0  # time for YouTube's search results to load before playing the first one
YOUTUBE_COLD_START_S = 6.0  # YouTube opened from scratch before it takes a video link
PAGE_LOAD_S = 6.0  # time for a show's page to open before pressing play
COLD_START_S = 20.0  # a streaming app opened from scratch (splash, profile) before it takes a link
# Streaming apps that open their own website's links: find the title's page with a web search, then open it in the app.
WEB_LINKED = {
    "in.startv.hotstar": ("hotstar.com", re.compile(r"https://www\.hotstar\.com/in/(?:shows|movies)/[^/?#]+/\d+")),
    "com.netflix.ninja": ("netflix.com", re.compile(r"https://www\.netflix\.com/(?:[a-z-]+/)?title/\d+")),
}


def find_adb(settings: Settings) -> str | None:
    configured = settings.data.get("adb_path") or ""
    if configured and Path(configured).exists():
        return configured
    local = Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk" / "platform-tools" / "adb.exe"
    if local.exists():
        return str(local)
    return shutil.which("adb")


class TV:
    """An Android / Google TV on the home Wi-Fi. Commands go through the phone when it's paired with the TV's own remote
    service (works with the laptop off); otherwise with adb from this computer (network debugging on the TV)."""

    def __init__(self, settings: Settings, runner=None, web_search=None, remote=None) -> None:
        self.settings = settings
        self._runner = runner  # injectable for tests: async (args) -> (code, output)
        self.web_search = web_search  # async (query) -> [{"url": ...}], to find a title's page in a streaming app
        self.fetch = None  # (url) -> page text, for finding a YouTube video; None = the web
        # async (host, ops) -> {"foreground": pkg} | raises DeviceError: the phone's TV remote; None = no phone.
        self.remote = remote
        self._foreground = ""
        self._volume: tuple[int, int] | None = None  # (level, max), as the TV last told the phone
        self._screen_on: bool | None = None
        self.played = Played()
        self.via = ""  # how the last command reached the TV: "phone" (its TV remote) or "laptop" (adb)

    async def _remote_shell(self, cmd: tuple[str, ...]) -> str:
        """The adb shell commands this class uses, done with the phone's TV remote instead."""
        host = self.host.rsplit(":", 1)[0]
        cache = self.settings.data.get("tv_cache") or {}
        ops: list[dict] = []
        if cmd[:2] == ("input", "keyevent"):
            ops = [{"key": KEY_CODES[k]} for k in cmd[2:] if k in KEY_CODES]
        elif cmd[:2] == ("am", "start"):
            ops = [{"link": cmd[-1].strip("'")}]
        elif cmd[:1] == ("monkey",):
            link = APP_HOME_LINKS.get(cmd[2])
            if not link:
                raise DeviceError("The phone's TV remote can't open that app")
            ops = [{"link": link}]
        elif cmd[:1] == ("pidof",):
            await self._remote_ops(host, [])
            return "1" if self._foreground == cmd[1] else ""
        elif cmd[:2] == ("dumpsys", "activity"):
            await self._remote_ops(host, [])
            return f"  topResumedActivity=ActivityRecord{{0 u0 {self._foreground}/.Main t0}}"
        elif cmd[:3] == ("pm", "list", "packages"):
            return cache.get("packages") or "\n".join(f"package:{p}" for p in APP_HOME_LINKS)
        elif cmd[:2] == ("dumpsys", "tv_input"):
            return cache.get("tv_input", "")
        elif cmd[:2] == ("dumpsys", "power"):
            await self._remote_ops(host, [])
            if self._screen_on is None:
                raise DeviceError("The TV hasn't said whether its screen is on")
            return f"mWakefulness={'Awake' if self._screen_on else 'Asleep'}"
        elif cmd[:3] == ("cmd", "media_session", "volume") and "--get" in cmd:
            await self._remote_ops(host, [])
            if self._volume is None:
                raise DeviceError("The TV hasn't said its volume yet")
            return f"volume is {self._volume[0]} in range [0..{self._volume[1]}]"
        else:
            raise DeviceError("The phone's TV remote can't do that (it can press keys and open apps and links)")
        await self._remote_ops(host, ops)
        return ""

    async def _remote_ops(self, host: str, ops: list[dict]) -> None:
        result = await self.remote(host, ops)
        self._foreground = result.get("foreground") or self._foreground
        if isinstance(result.get("screen_on"), bool):
            self._screen_on = result["screen_on"]
        if isinstance(result.get("volume"), int) and isinstance(result.get("volume_max"), int) and result["volume_max"]:
            self._volume = (result["volume"], result["volume_max"])

    def _remember(self, cmd: tuple[str, ...], out: str) -> None:
        """Keep what adb can see but the remote can't (installed apps, HDMI inputs) for when only the phone is around."""
        key = {("pm", "list", "packages"): "packages", ("dumpsys", "tv_input"): "tv_input"}.get(cmd[:3]) or \
            {("dumpsys", "tv_input"): "tv_input"}.get(cmd[:2])
        if key and out:
            cache = dict(self.settings.data.get("tv_cache") or {})
            if cache.get(key) != out:
                cache[key] = out
                self.settings.update({"tv_cache": cache})

    @property
    def host(self) -> str:
        host = str(self.settings.data.get("tv_host") or "").strip()
        return host if not host or ":" in host else f"{host}:5555"

    async def _adb(self, *args: str, timeout: float = 8) -> tuple[int, str]:
        if self._runner:
            return await self._runner(list(args))
        adb = find_adb(self.settings)
        if not adb:
            raise DeviceError("adb isn't installed on the laptop (Android SDK platform-tools)")
        proc = await asyncio.create_subprocess_exec(adb, *args, stdout=asyncio.subprocess.PIPE,
                                                    stderr=asyncio.subprocess.STDOUT)
        try:
            out, _ = await asyncio.wait_for(proc.communicate(), timeout)
        except asyncio.TimeoutError:
            proc.kill()
            raise DeviceError("The TV didn't answer") from None
        return proc.returncode or 0, out.decode(errors="replace").strip()

    async def connect(self) -> dict:
        if not self.host:
            raise DeviceError("Set the TV's IP address in mission control first (Devices → TV)")
        code, out = await self._adb("connect", self.host)
        low = out.lower()
        if "connected" not in low or "cannot" in low or "failed" in low:
            # Wireless debugging (Android 11+) picks a new port whenever the TV restarts: find it on the network.
            moved = await self._find_new_port()
            if moved:
                self.settings.update({"tv_host": moved})
                code, out = await self._adb("connect", moved)
                low = out.lower()
        if "connected" in low and "cannot" not in low and "failed" not in low:
            code, state = await self._adb("-s", self.host, "get-state")
            if state.strip() == "device":
                return {"connected": True, "tv": self.host}
            return {"connected": False, "tv": self.host,
                    "message": "Allow the connection on the TV screen (tick 'Always allow'), then try again"}
        return {"connected": False, "tv": self.host, "message": out or "Couldn't reach the TV"}

    async def _find_new_port(self) -> str | None:
        """The TV's current wireless-debugging address, from adb's network discovery (same IP, any port)."""
        ip = self.host.rsplit(":", 1)[0]
        _, out = await self._adb("mdns", "services")
        for line in out.splitlines():
            m = re.search(rf"\b{re.escape(ip)}:(\d+)\b", line)
            if m and "adb-tls-connect" in line:
                found = f"{ip}:{m.group(1)}"
                return found if found != self.host else None
        return None

    async def _shell(self, *cmd: str) -> str:
        if not self.host:
            raise DeviceError("Set the TV's IP address in mission control first (Devices → TV)")
        if self.remote is not None:
            try:
                out = await self._remote_shell(cmd)
                self.via = "phone"
                return out
            except DeviceError:
                if self._runner is None and not find_adb(self.settings):
                    raise
                # the phone isn't home or isn't paired: try adb from this computer
        out = await self._adb_shell(*cmd)
        self.via = "laptop"
        self._remember(cmd, out)
        return out

    async def _adb_shell(self, *cmd: str) -> str:
        code, out = await self._adb("-s", self.host, "shell", *cmd)
        if code != 0 or "not found" in out or "offline" in out or "unauthorized" in out:
            status = await self.connect()  # the link drops when the TV sleeps; reconnect once
            if not status["connected"]:
                raise DeviceError(status.get("message") or "Couldn't reach the TV")
            code, out = await self._adb("-s", self.host, "shell", *cmd)
            if code != 0:
                raise DeviceError(out or "The TV refused that")
        return out

    async def control(self, action: str, value: str = "", times: int = 1, app: str = "") -> dict:
        if action in TV_KEYS:
            steps = max(1, min(times, 30)) if action in ("volume_up", "volume_down", "up", "down", "left", "right") else 1
            await self._shell("input", "keyevent", *[TV_KEYS[action]] * steps)
            return {"done": action.replace("_", " ") + (f" x{steps}" if steps > 1 else "")}
        if action == "open_app":
            return await self.open_app(value)
        if action in ("search", "play", "youtube_search", "youtube_play"):
            if action.startswith("youtube"):
                app, action = "youtube", action.removeprefix("youtube_")
            return await self.find(value, app, play=action == "play")
        if action == "switch_input":
            return await self.switch_input(value)
        if action == "set_volume":
            return await self.set_volume(value)
        if action == "type_text":
            text = value.strip()
            if not text:
                raise DeviceError("Nothing to type")
            await self._shell("input", "text", re.sub(r"[^\w.@-]", lambda m: "%s" if m.group() == " " else "", text))
            return {"done": f"Typed {text}"}
        raise DeviceError(f"Unknown TV action {action}")

    async def screen_on(self) -> bool:
        """Whether the TV's screen is on right now (False if that can't be told)."""
        try:
            out = await self._shell("dumpsys", "power")
        except DeviceError:
            return False
        return "mWakefulness=Awake" in out

    async def set_volume(self, value: str) -> dict:
        """Volume to a level ("15"), a share ("half", "aadha", "30%"), or "max" / "min"."""
        out = await self._shell("cmd", "media_session", "volume", "--stream", "3", "--get")
        m = re.search(r"volume is (\d+) in range \[(\d+)\.\.(\d+)\]", out)
        if not m:
            raise DeviceError("The TV didn't say its volume, so I can't set an exact level")
        now, top = int(m.group(1)), int(m.group(3))
        target = volume_target(value, top)
        if target is None:
            raise DeviceError("Say a volume level like 15, half, or 30 percent")
        if target != now:
            key = TV_KEYS["volume_up" if target > now else "volume_down"]
            await self._shell("input", "keyevent", *[key] * abs(target - now))
        return {"done": f"Volume is now {target} out of {top}", "volume": target, "max": top}

    async def find(self, query: str, app: str = "", play: bool = False) -> dict:
        """Search for a show, movie or video inside an app, and play it if asked. No app named: the app on screen if
        Ronie can search in it, else YouTube."""
        query = query.strip()
        if not query:
            raise DeviceError("What should I look for?")
        if app.strip():
            pkg, label = await self.resolve_app(app), app.strip()
        else:
            pkg = await self.foreground()
            if pkg not in WEB_LINKED and pkg != "com.stremio.one" and not pkg.startswith("com.google.android.youtube"):
                pkg = None
            pkg = pkg or await self._installed_package(TV_APPS["youtube"]) or TV_APPS["youtube"][0]
            label = APP_LABELS.get(pkg, pkg)
        if pkg.startswith("com.google.android.youtube"):
            pick = await asyncio.to_thread(youtube_pick, query, self.fetch, self.played.items,
                                           wants_playlist(query)) if play else None
            if pick:  # open the video itself: it starts playing, no clicking through results
                if not await self._running(pkg):  # a cold start drops the link, so open the app first
                    await self._launch(pkg)
                    await asyncio.sleep(YOUTUBE_COLD_START_S)
                await self._view(pkg, youtube_link(pick))
                self.played.add(pick)
                out = {"done": f"Playing {'a playlist of ' if pick['list'] else ''}{query} on YouTube",
                       "video": youtube_link(pick)}
                return {**out, "title": pick["title"]} if pick["title"] else out
            await self._view(pkg, f"https://www.youtube.com/results?search_query={quote_plus(query)}")
            if play:  # couldn't find the video: the first result is highlighted once the results load
                await asyncio.sleep(YOUTUBE_RESULTS_S)
                await self._shell("input", "keyevent", TV_KEYS["ok"])
                return {"done": f"Playing {query} on YouTube"}
            return {"done": f"Showing YouTube results for {query}"}
        if pkg == "com.stremio.one":
            await self._view(pkg, f"stremio:///search?search={quote_plus(query)}")
            return {"done": f"Showing Stremio results for {query}",
                    "note": "Stremio needs the user to pick a result and a stream, so nothing is playing yet."}
        if pkg in WEB_LINKED:
            site, pattern = WEB_LINKED[pkg]
            if self.web_search is None:
                raise DeviceError(f"I can't search inside {label} without web search")
            results = await self.web_search(f"site:{site} {query}")
            page = next((m.group(0) for r in results if (m := pattern.match(r.get("url", "")))), None)
            if page is None:
                raise DeviceError(f"I couldn't find {query} on {label}")
            if not await self._running(pkg):  # a cold start drops the link, so open the app first
                await self._launch(pkg)
                await asyncio.sleep(COLD_START_S)
            await self._view(pkg, page)
            if play:  # the page opens with its main button (play / watch latest episode) selected
                await asyncio.sleep(PAGE_LOAD_S)
                await self._shell("input", "keyevent", TV_KEYS["ok"])
                return {"done": f"Playing {query} on {label}", "page": page}
            return {"done": f"Opened {query} on {label}", "page": page}
        await self._launch(pkg)
        raise DeviceError(f"I opened {label}, but I can't search inside it yet. YouTube, Stremio, Hotstar and Netflix work")

    async def inputs(self) -> dict[str, str]:
        """HDMI inputs: lower-case name -> TV input id. Devices that announce themselves over HDMI-CEC by name
        ("playstation 5") and every port by number ("hdmi 1")."""
        out = await self._shell("dumpsys", "tv_input")
        found: dict[str, str] = {}
        by_hw = dict(re.findall(r"^\s+(\d+): (\S+/\.HdmiInputService/HW\d+)\s*$", out, re.M))
        for hw_id, port in re.findall(r"TvInputHardwareInfo \{id=(\d+), type=9,.*?hdmi_port=(\d+)", out):
            if hw_id in by_hw:
                found.setdefault(f"hdmi {port}", by_hw[hw_id])
        by_logical = dict(re.findall(r"^\s+(\d+): (\S+/\.HdmiInputService/HDMI\d+)\s*$", out, re.M))
        for logical, name, port in re.findall(
                r"CEC: logical_address: 0x([0-9a-f]+) device_type: \d+ .*?display_name: (.+?) power_status: \d+ "
                r"physical_address: \S+ port_id: (\d+)", out):
            input_id = by_logical.get(str(int(logical, 16))) or found.get(f"hdmi {port}")
            if input_id:
                found[name.strip().lower()] = input_id
        return found

    async def switch_input(self, name: str) -> dict:
        """Show an HDMI input, e.g. the PlayStation. Switching to a device's input also wakes it over HDMI-CEC."""
        want = _clean(name)
        want = INPUT_ALIASES.get(want, want)
        want = re.sub(r"^(?:hdmi|input)\s*(\d)$", r"hdmi \1", want)
        inputs = await self.inputs()
        match = inputs.get(want) or next((i for n, i in inputs.items() if want and (want in n or n in want)), None)
        if match is None:
            names = ", ".join(sorted(inputs)) or "none"
            raise DeviceError(f"I can't find {name} on the TV's inputs (found: {names})")
        await self._shell("input", "keyevent", TV_KEYS["turn_on"])  # in case the TV is asleep
        await self._shell("am", "start", "-a", "android.intent.action.VIEW", "-d",
                          f"'content://android.media.tv/passthrough/{quote(match, safe='')}'")
        return {"done": f"Switched the TV to {name}"}

    async def _view(self, pkg: str, url: str) -> None:
        await self._shell("am", "start", "-a", "android.intent.action.VIEW", "-p", pkg, "-d", f"'{url}'")

    async def _launch(self, pkg: str) -> None:
        await self._shell("monkey", "-p", pkg, "-c", "android.intent.category.LEANBACK_LAUNCHER", "1")

    async def _running(self, pkg: str) -> bool:
        return bool((await self._shell("pidof", pkg, "||", "true")).strip())  # pidof fails when it isn't running

    async def foreground(self) -> str:
        """The package of the app on screen."""
        out = await self._shell("dumpsys", "activity", "activities")
        m = re.search(r"(?:topResumedActivity|mResumedActivity)=ActivityRecord\{\S+ \S+ ([\w.]+)/", out)
        return m.group(1) if m else ""

    async def _installed_package(self, candidates: list[str]) -> str | None:
        out = await self._shell("pm", "list", "packages")
        installed = {line.removeprefix("package:").strip() for line in out.splitlines()}
        return next((p for p in candidates if p in installed), None)

    async def resolve_app(self, name: str) -> str:
        """The installed package for a spoken app name ("Jio Hotstar", "Streamio", "Netflix")."""
        key = _clean(name)
        out = await self._shell("pm", "list", "packages")
        installed = [line.removeprefix("package:").strip() for line in out.splitlines()]
        candidates = list(TV_APPS.get(key, []))
        close = difflib.get_close_matches(key, list(TV_APPS), n=1, cutoff=0.7)  # "streamio" -> "stremio"
        if close:
            candidates += TV_APPS[close[0]]
        for word in key.split():  # "jio hotstar" -> "hotstar"
            candidates += TV_APPS.get(word, [])
        pkg = next((p for p in candidates if p in installed), None)
        if pkg is None:  # any installed package whose name contains the spoken words
            for words in (key.replace(" ", ""), *key.split()):
                matches = [p for p in installed if len(words) > 3 and words in p.lower().replace(".", "")]
                if matches:
                    pkg = min(matches, key=len)
                    break
        if pkg is None:
            raise DeviceError(f"{name} isn't installed on the TV")
        return pkg

    async def open_app(self, name: str) -> dict:
        await self._launch(await self.resolve_app(name))
        return {"done": f"Opened {name} on the TV"}
