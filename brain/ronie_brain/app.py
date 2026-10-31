"""FastAPI app: WebSocket endpoints for phone, PC and mission control, plus a few local HTTP routes."""
from __future__ import annotations

import asyncio
import base64
import html
import json
import logging
import time
import uuid
from difflib import SequenceMatcher
from contextlib import asynccontextmanager
from dataclasses import asdict
from pathlib import Path
from typing import Any

from fastapi import FastAPI, HTTPException, Request, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse, HTMLResponse, Response
from pydantic import BaseModel

from . import __version__
from .config import PROVIDERS, SERVICE_KEYS, Keys, Settings, data_dir
from .devices import DeviceError, find_adb
from .discovery import Advertiser
from .hub import Hub
from .speech import SpeechError, pcm_to_wav, transcribe
from .interview import ImportError_, Interview
from .live_tokens import LiveTokenError, create_live_token
from .memory import Compactor, Memory
from .models import AllProvidersFailed, LLMRequest, Provider, Router
from .models.budget import Budget
from .models.providers import build_providers
from .pairing import Pairing, lan_ip
from .room_audio import LaptopListener
from .persona import live_config
from .tools import Tools
from .voices import VOICE_NAMES, PreviewError, preview, voice_list
from .web import Web

log = logging.getLogger("ronie.brain")

PHOTO_WAIT_S = 16  # how long show waits for the wake photo: the glasses' camera takes up to ~10 s to start
FACING_PROMPT = (
    "This photo is from smart glasses the user is wearing at home, so it shows what is right in front of their eyes. "
    "Which screen are they facing? laptop: a laptop, a computer monitor, a keyboard, or a desk with a computer close "
    "in front. tv: a television in front of them, usually further away on a wall or stand. none: neither. "
    "Reply as one word (laptop, tv or none), a dash, then a few words on what you see."
)


def facing_from(answer: str) -> str | None:
    """"laptop", "tv" or None from the vision model's reply ("laptop - a keyboard and two monitors")."""
    first = answer.strip().lower().lstrip("*\"' ").split()[0] if answer.strip() else ""
    first = first.strip("-:.,*")
    return {"laptop": "laptop", "computer": "laptop", "monitor": "laptop", "tv": "tv", "television": "tv"}.get(first)

STATIC = Path(__file__).parent / "dashboard" / "static"
LOCAL_HOSTS = frozenset({"127.0.0.1", "::1", "localhost"})
# Host names mission control may be opened under. Anything else (e.g. a DNS-rebinding site) can't change keys.
PAGE_HOSTS = frozenset({"127.0.0.1", "localhost", "[::1]"})
REQUEST_FIELDS = {"prompt", "task", "system", "history", "max_tokens", "premium"}
KEY_PROVIDERS = tuple(p for p in PROVIDERS if p != "ollama") + SERVICE_KEYS


class LLMBody(BaseModel):
    prompt: str
    task: str = "general"
    system: str | None = None
    premium: bool | None = None


class KeyBody(BaseModel):
    key: str


class FactBody(BaseModel):
    text: str


class VoiceBody(BaseModel):
    voice: str


class DevicesBody(BaseModel):
    tv_host: str | None = None
    laptop_browser: str | None = None


class RunToolBody(BaseModel):
    tool: str
    args: dict = {}


class DocBody(BaseModel):
    name: str
    data_b64: str | None = None  # a file (PDF, .docx, text), base64
    text: str | None = None  # or pasted text


class JobBody(BaseModel):
    text: str


class GameActionBody(BaseModel):
    action: str = ""
    move: str | None = None
    hole: str | None = None
    board: str | None = None
    pot: float = 0.0
    to_call: float = 0.0
    opponents: int = 1
    game: str | None = None
    topic: str | None = None
    style: str | None = None
    fen: str | None = None
    image_b64: str | None = None


# Laptop listening counts as hearing the call only if it heard speech this recently (seconds).
LAPTOP_RECENT_S = 120
# How long a glasses-mic line waits for the laptop's copy of the same words before it's taken as the user's.
DUPLICATE_WAIT_S = 4.0

def create_app(
    *,
    port: int = 8790,
    settings: Settings | None = None,
    keys: Keys | None = None,
    budget: Budget | None = None,
    providers: dict[str, Provider] | None = None,
    pairing: Pairing | None = None,
    local_hosts: frozenset[str] = LOCAL_HOSTS,
    page_hosts: frozenset[str] = PAGE_HOSTS,
    memory: Memory | None = None,
    web: Web | None = None,
    live_token=create_live_token,
    interview: Interview | None = None,
    advertise: bool = True,
) -> FastAPI:
    settings = settings or Settings()
    keys = keys or Keys()
    budget = budget or Budget(settings)
    pairing = pairing or Pairing()
    hub = Hub()
    router = Router(settings, providers or build_providers(settings, keys), budget, on_event=hub.dashboard_event)
    tasks: set[asyncio.Task] = set()

    # One memory for every model, provider and voice session.
    memory = memory or Memory(settings)
    router.memory_context = memory.context

    async def memory_llm(prompt: str) -> str:
        return (await router.complete(LLMRequest(prompt, task="memory", raw=True, max_tokens=2048))).text

    compactor = Compactor(memory, settings, memory_llm)
    interview = interview or Interview()
    tools = Tools(router, memory, web or Web(keys), interview)
    phone_replies: dict[str, asyncio.Future] = {}  # requests the brain sent to a phone, by message id
    tv_phones: dict[int, bool] = {}  # phones (by socket) that are paired with the TV's remote service
    presence: dict[int, tuple[dict, float]] = {}  # phones (by socket): whether they're on the home Wi-Fi, and when
    # The screen in front of the user, from the glasses' photo when they called Ronie. "ready" is unset while a photo
    # is on its way: the camera takes up to ~10 s to start, and Ronie used to decide before it arrived (31 Oct).
    facing: dict = {"what": None, "at": 0.0, "ready": asyncio.Event(), "since": 0.0}
    facing["ready"].set()

    async def phone_tv_remote(host: str, ops: list[dict]) -> dict:
        """Ask a connected phone to press keys / open links on the TV through the TV's own remote service."""
        phones = [ws for ws in hub.clients.get("phone", ()) if tv_phones.get(id(ws))]
        if not phones:
            raise DeviceError("No phone with the TV remote set up is connected")
        id_ = uuid.uuid4().hex[:12]
        future: asyncio.Future = asyncio.get_running_loop().create_future()
        phone_replies[id_] = future
        try:
            await hub.send(phones[-1], "tv.remote", {"host": host, "ops": ops}, id_)
            result = await asyncio.wait_for(future, 15 + sum(op.get("sleep", 0) for op in ops))
        except asyncio.TimeoutError:
            raise DeviceError("The phone didn't answer about the TV") from None
        finally:
            phone_replies.pop(id_, None)
        if result.get("error"):
            raise DeviceError(str(result["error"]))
        return result

    tools.tv.remote = phone_tv_remote

    def agents() -> list:
        return list(hub.clients.get("home", ()))

    async def agent_call(type_: str, payload: dict, timeout: float = 20) -> dict:
        """Ask the laptop agent (when the brain runs on a server) to do something only the laptop can."""
        found = agents()
        if not found:
            raise DeviceError("Your laptop is off or the Ronie agent isn't running on it")
        id_ = uuid.uuid4().hex[:12]
        future: asyncio.Future = asyncio.get_running_loop().create_future()
        phone_replies[id_] = future
        try:
            await hub.send(found[-1], type_, payload, id_)
            return (await asyncio.wait_for(future, timeout)).get("result") or {}
        except asyncio.TimeoutError:
            raise DeviceError("The laptop didn't answer") from None
        finally:
            phone_replies.pop(id_, None)

    if settings.data.get("laptop_mode") == "agent":  # brain on a server: the laptop is reached through its agent
        async def laptop_by_agent(tool: str, args: dict) -> dict:
            return await agent_call("device.run", {"tool": tool, "args": args})

        tools.laptop_agent = laptop_by_agent

    async def availability() -> dict:
        """Where Ronie can show things right now. Home: a phone said so in the last 10 minutes (it reaches the TV
        on the home Wi-Fi). TV: home and a phone has the TV remote. Laptop: home, and someone used it lately."""
        now = time.time()
        home = any(p.get("home") and now - at < 600 for p, at in presence.values())
        laptop = False
        if home:
            try:
                if settings.data.get("laptop_mode") == "agent":
                    status_ = await agent_call("device.run", {"tool": "laptop_status", "args": {}}, timeout=3)
                else:
                    status_ = tools.laptop.status()
                idle = status_.get("idle_s")
                laptop = idle is not None and idle < 600
            except DeviceError:
                idle, laptop = None, False
        else:
            idle = None
        wait = PHOTO_WAIT_S - (time.time() - facing["since"])
        if home and not facing["ready"].is_set() and wait > 0:  # the wake photo is on its way: wait for it
            try:
                await asyncio.wait_for(facing["ready"].wait(), wait)
            except asyncio.TimeoutError:
                pass
        seen = facing["what"] if home and time.time() - facing["at"] < 600 else None
        if seen is None and laptop and idle is not None and idle < 120:
            seen = "laptop"  # no answer from the photo, but the laptop was used in the last two minutes
        return {"home": home, "tv": home and any(tv_phones.values()), "laptop": laptop, "facing": seen}

    async def look_around(image_b64: str) -> None:
        """Works out from the glasses' photo which screen is in front of the user: "laptop", "tv" or None."""
        try:
            # Room to think: with 5 tokens the model's thinking used them all and the answer came back empty (31 Oct).
            r = await router.complete(LLMRequest(FACING_PROMPT, task="vision", raw=True, max_tokens=400,
                                                 image_b64=image_b64, timeout_s=12))
            answer = r.text.strip()
            what = facing_from(answer)
            facing.update(what=what, at=time.time())
            log.info("user is facing: %s (%s)", what or "no screen", answer[:120])
        except Exception as e:
            log.info("couldn't tell the surroundings: %s", e)
        finally:
            facing["ready"].set()

    tools.availability = availability

    async def voice_changed(voice: str) -> None:
        # Running voice sessions (PC and phone) restart with the new voice at once.
        await hub.broadcast("pc", "live.restart", {"reason": "voice"})
        await hub.broadcast("phone", "live.restart", {"reason": "voice"})
        await hub.dashboard_event("settings", f"Ronie's voice is now {voice}")

    def record(role: str, text: str, source: str) -> None:
        if memory.add_turn(role, text, source):
            compactor.maybe_schedule()

    def interview_hint() -> str:
        """Names and skills from the profile help Whisper spell them right."""
        p = interview.profile or {}
        return (p.get("name") or "") + " " + " ".join(p.get("skills", [])[:15])

    async def answer_heard(heard: str, level_db: float | None, speaker: str | None) -> dict:
        async def complete(prompt: str) -> str:
            return (await router.complete(LLMRequest(prompt, task="interview", raw=True, max_tokens=900))).text

        await hub.dashboard_event("heard", heard, {"source": f"interview ({speaker or 'room'})"})
        result = await interview.respond(heard, complete, level_db, speaker=speaker)
        if result.get("interviewer_question"):
            await hub.dashboard_event("answer", result["answer"], {"source": "interview cue"})
        return result

    async def laptop_utterance(pcm: bytes, level_db: float) -> None:
        """The interviewer said something on the call: answer it on every connected phone."""
        try:
            heard = await transcribe(pcm_to_wav(pcm), keys, prompt=interview_hint())
            if not heard:
                return
            laptop_heard.append((time.monotonic(), heard))
            del laptop_heard[:-20]
            result = await answer_heard(heard, level_db, "interviewer")
        except (SpeechError, AllProvidersFailed, ValueError) as e:
            await hub.broadcast("phone", "interview.answer", {"heard": "", "interviewer_question": False,
                                                           "error": str(e), "source": "laptop"})
            return
        await hub.broadcast("phone", "interview.answer", {"heard": heard, "source": "laptop", **result})

    async def laptop_device(name: str) -> None:
        await hub.dashboard_event("interview", f"Listening to the laptop's output: {name}")

    async def laptop_talking(talking: bool, transcribing: bool) -> None:
        # The phone stops whispering while the interviewer talks, and picks up again (or switches answers) after.
        await hub.broadcast("phone", "interview.interviewer", {"talking": talking, "transcribing": transcribing})

    laptop = LaptopListener(laptop_utterance, laptop_device, laptop_talking)
    laptop_heard: list[tuple[float, str]] = []  # (when, text) of recent lines from the call audio

    async def glasses_speaker(heard: str, hint: str | None) -> str | None:
        """Who a glasses-mic line is, given the phone's hint. The hint "candidate" (the laptop hears the call) only
        holds while the laptop is actually hearing a call: if the interviewer is on another device, the glasses' mic
        does pick them up when loud enough, so fall back to loudness and words. "duplicate": the glasses caught a
        line the laptop heard too (its copy may still be transcribing, so wait a little for it)."""
        if hint != "candidate":
            return hint
        now = time.monotonic()
        recent = [text for t, text in laptop_heard if now - t < LAPTOP_RECENT_S]
        if not recent:
            return None
        deadline = now + DUPLICATE_WAIT_S
        while True:
            if any(SequenceMatcher(None, heard.lower(), text.lower()).ratio() > 0.6
                   for t, text in laptop_heard if time.monotonic() - t < 20):
                return "duplicate"
            if time.monotonic() >= deadline:
                return "candidate"
            await asyncio.sleep(0.25)

    def log_tool(role: str, name: str, args: dict, result: dict) -> None:
        """Every voice tool call and its result, so a failed request can be traced afterwards (~/.ronie/tools.log)."""
        try:
            with (data_dir() / "tools.log").open("a", encoding="utf-8") as f:
                # A game photo arrives as a long base64 string: keep the log readable.
                short = {k: (v[:120] + "…" if isinstance(v, str) and len(v) > 200 else v) for k, v in args.items()}
                f.write(json.dumps({"time": time.strftime("%Y-%m-%d %H:%M:%S"), "from": role, "tool": name,
                                    "args": short, "result": result}, ensure_ascii=False) + "\n")
        except OSError:
            pass

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        advertiser = Advertiser(lan_ip(), port) if advertise else None
        if advertiser:
            await advertiser.start()
        yield
        laptop.stop()
        if advertiser:
            await advertiser.stop()

    app = FastAPI(title="Ronie brain", version=__version__, lifespan=lifespan)
    app.state.settings, app.state.router, app.state.hub = settings, router, hub

    def is_local(host: str | None) -> bool:
        return host in local_hosts

    def from_here(conn: Request | WebSocket) -> bool:
        """This computer itself. On a cloud server the HTTPS proxy also connects from 127.0.0.1, but adds
        X-Forwarded-For: those requests come from the internet and are never trusted as local."""
        if "x-forwarded-for" in conn.headers:
            return False
        return is_local(conn.client.host if conn.client else None)

    def require_local(request: Request) -> None:
        if not from_here(request):
            raise HTTPException(403, "Only available on this computer")

    def require_mission_control(request: Request) -> None:
        """For changes from the mission control page: this computer, and not another website in the same browser."""
        require_local(request)
        host = request.headers.get("host", "")
        if host.rsplit(":", 1)[0] not in page_hosts:
            raise HTTPException(403, "Open mission control at http://127.0.0.1:%d/" % port)
        origin = request.headers.get("origin")
        if origin and origin.split("://", 1)[-1] != host:
            raise HTTPException(403, "Requests from other websites aren't allowed")

    def status() -> dict[str, Any]:
        return {
            "version": __version__,
            "premium": settings["premium"],
            "language": settings["language"],
            "providers": {
                name: {
                    "available": p.available(),
                    "paid": p.paid,
                    "model": settings["models"].get(name),
                    # Whether a key is saved; the key itself is never sent to the page.
                    "has_key": bool(keys.get(name)) if name in KEY_PROVIDERS else None,
                    "limited_until": router.cooling_until(name),  # Unix time, while a rate limit is active
                }
                for name, p in router.providers.items()
            },
            "budgets": budget.summary(),
            "gemini_models": quota.snapshot() if (quota := getattr(router.providers.get("gemini"), "quota", None)) else [],
            "clients": hub.counts(),
        }

    # ---------- HTTP ----------

    @app.get("/health")
    async def health() -> dict:
        return {"ok": True, "version": __version__}

    @app.get("/")
    async def dashboard(request: Request) -> FileResponse:
        require_local(request)
        return FileResponse(STATIC / "index.html")

    @app.get("/api/status")
    async def api_status(request: Request) -> dict:
        require_local(request)
        return status()

    @app.get("/pair", response_class=HTMLResponse)
    async def pair(request: Request) -> str:
        require_local(request)
        ip = lan_ip()
        page = (STATIC / "pair.html").read_text("utf-8")
        return (
            page.replace("{{QR}}", pairing.qr_svg(ip, port, settings.data.get("public_url") or None))
            .replace("{{ADDRESS}}", f"{ip}:{port}")
            .replace("{{CODE}}", html.escape(pairing.qr_payload(ip, port, settings.data.get("public_url") or None)))
        )

    @app.post("/api/llm")
    async def api_llm(body: LLMBody, request: Request) -> dict:
        """Send a prompt through the router from this computer (handy for testing)."""
        require_local(request)
        await hub.dashboard_event("heard", body.prompt, {"source": "test box", "task": body.task})
        try:
            result = await router.complete(LLMRequest(**body.model_dump()))
        except AllProvidersFailed as e:
            raise HTTPException(503, str(e)) from e
        record("user", body.prompt, "test box")
        record("ronie", result.text, "test box")
        return asdict(result)

    @app.get("/api/live-config")
    async def api_live_config(request: Request) -> dict:
        """Ronie's instructions and tools for a Gemini Live voice session (the PC voice client uses this)."""
        require_local(request)
        return live_config(settings, memory.context())

    def interview_state() -> dict:
        return {
            "docs": interview.docs(),
            "job": interview.job,
            "profile": interview.profile,
            "gaps": interview.gaps,
            "cues": interview.recent_cues(),
        }

    @app.get("/api/interview")
    async def get_interview(request: Request) -> dict:
        require_local(request)
        return interview_state()

    @app.post("/api/interview/docs")
    async def add_interview_doc(body: DocBody, request: Request) -> dict:
        require_mission_control(request)
        try:
            if body.data_b64:
                interview.add_file(body.name, body.data_b64)
            elif body.text and body.text.strip():
                interview.add_doc(body.name or "Pasted text", body.text.strip()[:40_000])
            else:
                raise HTTPException(422, "Choose a file or paste some text")
        except ImportError_ as e:
            raise HTTPException(422, str(e)) from e
        return interview_state()

    @app.delete("/api/interview/docs/{doc_id}")
    async def remove_interview_doc(doc_id: int, request: Request) -> dict:
        require_mission_control(request)
        interview.remove_doc(doc_id)
        return interview_state()

    @app.put("/api/interview/job")
    async def set_interview_job(body: JobBody, request: Request) -> dict:
        require_mission_control(request)
        interview.set_job(body.text)
        return interview_state()

    @app.post("/api/interview/build")
    async def build_interview(request: Request) -> dict:
        require_mission_control(request)

        async def complete(prompt: str) -> str:
            req = LLMRequest(prompt, task="memory", raw=True, max_tokens=8000, timeout_s=90)
            return (await router.complete(req)).text

        await hub.dashboard_event("tool", "Building the interview profile from your documents")
        try:
            await interview.build(complete)
        except ImportError_ as e:
            raise HTTPException(422, str(e)) from e
        except (AllProvidersFailed, ValueError) as e:
            raise HTTPException(503, f"Couldn't build the profile: {e}") from e
        return interview_state()

    @app.get("/api/voices")
    async def get_voices(request: Request) -> dict:
        require_local(request)
        return {"voices": voice_list(settings["live_voice"])}

    @app.put("/api/voice")
    async def set_voice(body: VoiceBody, request: Request) -> dict:
        require_mission_control(request)
        if body.voice not in VOICE_NAMES:
            raise HTTPException(404, f"Unknown voice {body.voice}")
        settings.update({"live_voice": body.voice})
        await voice_changed(body.voice)
        return {"voices": voice_list(body.voice)}

    @app.get("/api/voices/{voice}/preview")
    async def voice_preview(voice: str, request: Request) -> Response:
        require_local(request)
        key = keys.get("gemini")
        if not key:
            raise HTTPException(409, "Add a Gemini key first")
        try:
            clip = await preview(voice, key)
        except PreviewError as e:
            raise HTTPException(502, f"Couldn't make a preview: {e}") from e
        return Response(clip, media_type="audio/wav", headers={"Cache-Control": "max-age=86400"})

    @app.get("/api/devices")
    async def get_devices(request: Request) -> dict:
        require_local(request)
        return {"tv_host": settings.data.get("tv_host", ""), "adb": bool(find_adb(settings)),
                "laptop_browser": settings.data.get("laptop_browser", "")}

    @app.put("/api/devices")
    async def put_devices(body: DevicesBody, request: Request) -> dict:
        require_mission_control(request)
        changes = {k: v.strip() for k, v in body.model_dump().items() if v is not None}
        if changes:
            settings.update(changes)
        return await get_devices(request)

    @app.post("/api/tv/connect")
    async def tv_connect(request: Request) -> dict:
        require_mission_control(request)
        try:
            return await tools.tv.connect()
        except DeviceError as e:
            raise HTTPException(409, str(e)) from e

    @app.post("/api/devices/run")
    async def run_device_tool(body: RunToolBody, request: Request) -> dict:
        """Try a device action from mission control (the same tools the voice uses)."""
        require_mission_control(request)
        if body.tool not in ("laptop", "laptop_control", "tv"):
            raise HTTPException(404, f"Unknown device tool {body.tool}")
        result = await tools.run(body.tool, body.args)
        await hub.dashboard_event("tool", f"{body.tool}: {result.get('done') or result.get('error')}")
        return result

    @app.get("/api/memory")
    async def get_memory(request: Request) -> dict:
        require_local(request)
        return {
            "facts": [dict(f) for f in memory.facts()],
            "summary": memory.summary(),
            "recent_tokens": memory.uncompacted_tokens(),
            "compact_at_tokens": settings["memory_compact_tokens"],
        }

    @app.post("/api/memory/facts")
    async def add_fact(body: FactBody, request: Request) -> dict:
        require_mission_control(request)
        if not body.text.strip():
            raise HTTPException(422, "Enter something to remember")
        memory.remember(body.text, "note", 5, source="mission control")
        await hub.dashboard_event("memory", f"Remembered: {body.text.strip()}")
        return await get_memory(request)

    @app.delete("/api/memory/facts/{fact_id}")
    async def delete_fact(fact_id: int, request: Request) -> dict:
        require_mission_control(request)
        if not memory.forget(fact_id):
            raise HTTPException(404, "Not found")
        return await get_memory(request)

    @app.post("/api/memory/compact")
    async def compact_now(request: Request) -> dict:
        require_mission_control(request)
        result = await compactor.compact()
        return {**result, **(await get_memory(request))}

    @app.put("/api/keys/{provider}")
    async def put_key(provider: str, body: KeyBody, request: Request) -> dict:
        require_mission_control(request)
        if provider not in KEY_PROVIDERS:
            raise HTTPException(404, f"Unknown provider {provider}")
        key = body.key.strip()
        if not key:
            raise HTTPException(422, "Enter a key")
        try:
            keys.set(provider, key)
        except Exception as e:
            raise HTTPException(500, f"Couldn't save the key in Windows Credential Manager: {e}") from e
        await hub.dashboard_event("settings", f"{provider} key saved from mission control")
        return status()

    @app.delete("/api/keys/{provider}")
    async def delete_key(provider: str, request: Request) -> dict:
        require_mission_control(request)
        if provider not in KEY_PROVIDERS:
            raise HTTPException(404, f"Unknown provider {provider}")
        keys.delete(provider)
        await hub.dashboard_event("settings", f"{provider} key removed from mission control")
        return status()

    # ---------- Game Mode ----------

    @app.get("/api/game/state")
    async def get_game_state(request: Request) -> dict:
        require_local(request)
        return await tools.game.dispatch("chess_state")

    @app.post("/api/game/action")
    async def run_game_action(body: GameActionBody, request: Request) -> dict:
        require_local(request)
        result = await tools.game.dispatch(body.action, body.model_dump())
        await hub.dashboard_event("game", f"{body.action}: {result.get('spoken', '')[:100]}")
        return result

    @app.post("/api/game/chess/move")
    async def chess_move(body: GameActionBody, request: Request) -> dict:
        require_local(request)
        result = await tools.game.dispatch("chess_move", body.model_dump())
        await hub.dashboard_event("game", f"move: {result.get('spoken', '')}")
        return result

    @app.get("/api/game/chess/best")
    async def chess_best(request: Request, style: str | None = None) -> dict:
        require_local(request)
        args = {"style": style} if style else {}
        return await tools.game.dispatch("chess_best", args)

    @app.post("/api/game/poker/equity")
    async def poker_equity(body: GameActionBody, request: Request) -> dict:
        require_local(request)
        return await tools.game.dispatch("poker_equity", body.model_dump())

    @app.post("/api/game/reset")
    async def game_reset(body: GameActionBody, request: Request) -> dict:
        require_local(request)
        return await tools.game.dispatch("chess_reset", body.model_dump())

    @app.post("/api/game/vision")
    async def game_vision(body: GameActionBody, request: Request) -> dict:
        require_local(request)
        result = await tools.game.dispatch("vision", {**body.model_dump(), "router": router})
        await hub.dashboard_event("game", f"Vision: {result.get('spoken', '')[:100]}")
        return result


    # ---------- WebSockets ----------

    async def handle(ws: WebSocket, role: str, msg: dict[str, Any]) -> None:
        type_, payload, id_ = msg.get("type"), msg.get("payload") or {}, msg.get("id")

        if type_ == "surroundings.pending" and role == "phone":  # the user called Ronie at home; a photo is coming
            facing["ready"] = asyncio.Event()
            facing["since"] = time.time()
            return
        if type_ == "surroundings" and role == "phone":  # that photo, from the glasses (or none: the camera failed)
            image = str(payload.get("image_b64") or "")
            if image:
                task = asyncio.create_task(look_around(image))
                tasks.add(task)
                task.add_done_callback(tasks.discard)
            else:
                facing["ready"].set()
            return
        if type_ == "presence" and role == "phone":  # the phone says whether it's on the home Wi-Fi
            before = presence.get(id(ws), ({}, 0))[0].get("home")
            presence[id(ws)] = (dict(payload), time.time())
            if bool(payload.get("home")) != bool(before):
                log.info("phone presence: %s", "at home" if payload.get("home") else "away")
            return
        if type_ == "tv.remote.status" and role == "phone":  # the phone says whether it can control the TV
            tv_phones[id(ws)] = bool(payload.get("paired"))
            return

        if type_ == "device.result" and role == "home":  # the laptop agent's answer
            future = phone_replies.get(id_ or "")
            if future and not future.done():
                future.set_result(payload)
            return

        if type_ == "interview.laptop_audio" and role == "home":  # the interviewer, heard on the laptop
            try:
                pcm = base64.b64decode(payload.get("audio_b64") or "")
            except ValueError:
                return
            task = asyncio.create_task(laptop_utterance(pcm, float(payload.get("level_db") or -30)))
            tasks.add(task)
            task.add_done_callback(tasks.discard)
            return

        if type_ == "interview.laptop_talking" and role == "home":
            await laptop_talking(bool(payload.get("talking")), bool(payload.get("transcribing")))
            return

        if type_ == "tv.remote.result" and role == "phone":  # the phone's answer to a TV command
            future = phone_replies.get(id_ or "")
            if future and not future.done():
                future.set_result(payload)
            return

        if type_ == "llm.request":
            fields = {k: v for k, v in payload.items() if k in REQUEST_FIELDS}
            if not isinstance(fields.get("prompt"), str):
                await hub.send(ws, "error", {"code": "bad_request", "message": "prompt is required"}, id_)
                return

            async def run() -> None:
                try:
                    result = await router.complete(LLMRequest(**fields))
                    await hub.send(ws, "llm.response", asdict(result), id_)
                    if role == "phone":  # a conversation turn; tool requests from the voice client are not
                        record("user", fields["prompt"], "phone")
                        record("ronie", result.text, "phone")
                except AllProvidersFailed as e:
                    await hub.send(ws, "error", {"code": "all_providers_failed", "message": str(e)}, id_)
                except Exception:
                    log.exception("llm.request failed")
                    await hub.send(ws, "error", {"code": "internal", "message": "Request failed"}, id_)

            task = asyncio.create_task(run())
            tasks.add(task)
            task.add_done_callback(tasks.discard)

        elif type_ == "tool.call":
            name, args = str(payload.get("name", "")), payload.get("args") or {}

            async def run_tool() -> None:
                await hub.dashboard_event("tool", f"{name}: {json.dumps(args, ensure_ascii=False)[:200]}", {"source": role})
                try:
                    result = await tools.run(name, args)
                except Exception:
                    log.exception("tool %s failed", name)
                    result = {"error": "Tool failed"}
                log_tool(role, name, args, result)
                await hub.dashboard_event("tool", f"{name} -> {json.dumps(result, ensure_ascii=False)[:200]}",
                                          {"source": role})
                await hub.send(ws, "tool.result", {"result": result}, id_)

            task = asyncio.create_task(run_tool())
            tasks.add(task)
            task.add_done_callback(tasks.discard)

        elif type_ == "live.start":
            # A voice session is starting (phone, or the PC voice client): hand over a one-use Live token
            # plus Ronie's instructions and the shared memory. The Gemini key never leaves this computer.
            async def start_live() -> None:
                key = keys.get("gemini")
                if not key:
                    await hub.send(ws, "error", {"code": "no_key", "message": "Add a Gemini key in mission control"}, id_)
                    return
                try:
                    token = await live_token(key)
                except LiveTokenError as e:
                    await hub.send(ws, "error", {"code": "live_token", "message": str(e)}, id_)
                    return
                mode = str(payload.get("mode") or "assistant")
                if isinstance(payload.get("home"), bool):  # the phone says where the user is as the session starts
                    presence[id(ws)] = ({"home": payload["home"]}, time.time())
                try:
                    devices = await asyncio.wait_for(availability(), 3) if mode == "assistant" else None
                except asyncio.TimeoutError:
                    devices = None
                config = live_config(settings, memory.context(), mode=mode, interview=interview, devices=devices)
                await hub.send(ws, "live.ready", {**token, "config": config}, id_)
                await hub.dashboard_event("connect", f"Voice session token issued to the {role}")

            task = asyncio.create_task(start_live())
            tasks.add(task)
            task.add_done_callback(tasks.discard)

        elif type_ == "interview.segment":
            # Interview cues: one utterance from the glasses' mic (16 kHz mono PCM). Transcribe it, and if it's the
            # interviewer asking something, answer as the candidate. No chatbot listens in this mode. While the laptop
            # listens to the call ("speaker": "candidate"), the mic is only the user and is kept as context.
            async def answer_segment() -> None:
                try:
                    heard = await transcribe(pcm_to_wav(base64.b64decode(payload.get("audio_b64") or "")), keys,
                                             prompt=interview_hint())
                except (SpeechError, ValueError) as e:
                    await hub.send(ws, "error", {"code": "transcribe", "message": str(e)}, id_)
                    return
                if not heard:
                    await hub.send(ws, "interview.answer", {"heard": "", "interviewer_question": False}, id_)
                    return
                if payload.get("calibrate") and payload.get("level_db") is not None:
                    interview.calibrate(float(payload["level_db"]))
                    await hub.send(ws, "interview.answer", {"heard": heard, "speaker": "candidate", "calibrated": True,
                                                         "interviewer_question": False}, id_)
                    return
                level = payload.get("level_db")
                hint = payload.get("speaker") if payload.get("speaker") in ("candidate", "interviewer") else None
                speaker = await glasses_speaker(heard, hint)
                if speaker == "duplicate":  # the laptop already heard (and answered) this line
                    await hub.send(ws, "interview.answer", {"heard": "", "interviewer_question": False}, id_)
                    return
                try:
                    result = await answer_heard(heard, float(level) if level is not None else None, speaker)
                except (AllProvidersFailed, ValueError) as e:
                    await hub.send(ws, "error", {"code": "interview", "message": f"Couldn't answer: {e}"}, id_)
                    return
                expected = str(payload.get("expected") or "").strip()
                if expected and result.get("speaker") != "interviewer":
                    # The user was repeating a prompted phrase: did they get it right?
                    async def quick(prompt: str) -> str:
                        return (await router.complete(LLMRequest(prompt, task="interview", raw=True, max_tokens=200,
                                                                 timeout_s=6))).text
                    try:
                        result["check"] = await interview.check_repeat(heard, expected,
                                                                       str(payload.get("answer") or ""), quick)
                    except (AllProvidersFailed, ValueError):
                        result["check"] = {"verdict": "ok", "correction": ""}
                await hub.send(ws, "interview.answer", {"heard": heard, **result}, id_)

            task = asyncio.create_task(answer_segment())
            tasks.add(task)
            task.add_done_callback(tasks.discard)

        elif type_ == "interview.job" and role == "phone":  # the job description, typed in the app
            text = str(payload.get("text") or "").strip()
            interview.set_job(text)
            await hub.dashboard_event("interview", "Job description saved from the phone" if text else "Job description cleared")
            await hub.send(ws, "interview.job.saved", {"chars": len(text)}, id_)

        elif type_ == "status.get" and role == "phone":  # the app's AI & Keys tab: what's set up and working
            st = status()
            await hub.send(ws, "status", {
                "premium": st["premium"],
                "live_model": (settings["live_models"] or [""])[0],
                "live_voice": settings["live_voice"],
                "providers": {name: {k: p[k] for k in ("available", "paid", "model", "has_key", "limited_until")}
                              for name, p in st["providers"].items()},
                "service_keys": {name: bool(keys.get(name)) for name in SERVICE_KEYS},
            }, id_)

        elif type_ == "memory.list" and role == "phone":  # the app's Memory tab
            await hub.send(ws, "memory", {"facts": [{"id": f["id"], "text": f["text"]} for f in memory.facts()],
                                          "summary": memory.summary()}, id_)

        elif type_ == "memory.forget" and role == "phone":
            gone = memory.forget(int(payload.get("id") or 0))
            if gone:
                await hub.dashboard_event("memory", "A memory was removed from the phone")
            await hub.send(ws, "memory.forgotten", {"ok": gone}, id_)

        elif type_ == "interview.listen" and role == "phone":
            # Hear the interviewer from the call playing on this laptop (Meet, Zoom, a ChatGPT voice…).
            if agents() or settings.data.get("laptop_mode") == "agent":  # brain on a server: the agent listens
                try:
                    result = await agent_call("interview.listen", {"on": bool(payload.get("on"))})
                except DeviceError as e:
                    result = {"error": str(e)}
                if result.get("error"):
                    await hub.send(ws, "error", {"code": "laptop_audio", "message": result["error"]}, id_)
                else:
                    await hub.send(ws, "interview.listening", {"on": bool(result.get("on")),
                                                               "device": result.get("device", "")}, id_)
                return
            if payload.get("on"):
                try:
                    device = laptop.start()
                except RuntimeError as e:
                    await hub.send(ws, "error", {"code": "laptop_audio", "message": str(e)}, id_)
                    return
                await hub.dashboard_event("interview", f"Listening to the laptop's audio ({device}) for the interviewer")
                await hub.send(ws, "interview.listening", {"on": True, "device": device}, id_)
            else:
                laptop.stop()
                await hub.send(ws, "interview.listening", {"on": False}, id_)

        elif type_ == "settings.update" and role == "phone":
            changes = payload.get("settings") or {}
            if "live_voice" in changes and changes["live_voice"] not in VOICE_NAMES:
                await hub.send(ws, "error", {"code": "bad_voice", "message": f"Unknown voice {changes['live_voice']}"}, id_)
                return
            settings.update(changes)
            await hub.send(ws, "ack", {"ok": True}, id_)
            await hub.dashboard_event("settings", "Settings updated from the phone")
            if "live_voice" in changes:
                await voice_changed(changes["live_voice"])

        elif type_ == "voices.list":
            await hub.send(ws, "voices", {"voices": voice_list(settings["live_voice"])}, id_)

        elif type_ in ("keys.set", "keys.delete") and role == "phone":
            provider = payload.get("provider")
            if provider not in KEY_PROVIDERS:
                await hub.send(ws, "error", {"code": "bad_provider", "message": f"Unknown provider {provider}"}, id_)
                return
            if type_ == "keys.set":
                key = payload.get("key")
                if not isinstance(key, str) or not key.strip():
                    await hub.send(ws, "error", {"code": "bad_request", "message": "key is required"}, id_)
                    return
                keys.set(provider, key.strip())
            else:
                keys.delete(provider)
            await hub.send(ws, "ack", {"ok": True}, id_)
            await hub.dashboard_event("settings", f"{provider} key {'saved' if type_ == 'keys.set' else 'removed'}")

        elif type_ == "event":
            stage, text, extra = str(payload.get("stage", "event")), str(payload.get("text", "")), payload.get("extra") or {}
            await hub.dashboard_event(stage, text, {"source": role, **extra})
            if extra.get("source") == "voice" and stage in ("heard", "answer"):
                record("user" if stage == "heard" else "ronie", text, "voice")

        elif type_ == "relay":
            target, inner = payload.get("to"), payload.get("message")
            if target in ("phone", "pc") and isinstance(inner, dict):
                sent = await hub.send_raw(target, inner)
                if not sent:
                    await hub.send(ws, "error", {"code": "not_connected", "message": f"No {target} connected"}, id_)
            else:
                await hub.send(ws, "error", {"code": "bad_request", "message": "relay needs to and message"}, id_)

        else:
            await hub.send(ws, "error", {"code": "unknown_type", "message": f"Not handled: {type_}"}, id_)

    async def client_socket(ws: WebSocket, role: str) -> None:
        await ws.accept()
        try:
            hello = await asyncio.wait_for(ws.receive_json(), timeout=10)
        except (asyncio.TimeoutError, WebSocketDisconnect, ValueError):
            await ws.close(code=4400)
            return
        payload = hello.get("payload") or {}
        trusted = role == "pc" and from_here(ws)
        if hello.get("type") != "hello" or not (trusted or pairing.check(payload.get("token"))):
            await ws.close(code=4401)
            return

        hub.add(role, ws)
        await hub.send(ws, "welcome", {"server": "ronie-brain", "version": __version__, "providers": status()["providers"]}, hello.get("id"))
        await hub.dashboard_event("connect", f"{role} connected", {"device": payload.get("device")})
        try:
            while True:
                msg = await ws.receive_json()
                if isinstance(msg, dict):
                    await handle(ws, role, msg)
        except (WebSocketDisconnect, ValueError):
            pass
        finally:
            hub.remove(role, ws)
            tv_phones.pop(id(ws), None)
            presence.pop(id(ws), None)
            if role == "phone" and not hub.counts().get("phone"):
                laptop.stop()  # nobody left to hear the answers
            await hub.dashboard_event("connect", f"{role} disconnected")

    @app.websocket("/ws/phone")
    async def ws_phone(ws: WebSocket) -> None:
        await client_socket(ws, "phone")

    @app.websocket("/ws/pc")
    async def ws_pc(ws: WebSocket) -> None:
        await client_socket(ws, "pc")

    @app.websocket("/ws/home")
    async def ws_home(ws: WebSocket) -> None:  # the laptop agent, when the brain runs on a server
        await client_socket(ws, "home")

    @app.websocket("/ws/dashboard")
    async def ws_dashboard(ws: WebSocket) -> None:
        if not from_here(ws):
            await ws.close(code=4403)
            return
        await ws.accept()
        hub.add("dashboard", ws)
        try:
            while True:
                await ws.receive_text()
        except WebSocketDisconnect:
            pass
        finally:
            hub.remove("dashboard", ws)

    return app
