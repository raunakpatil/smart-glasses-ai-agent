"""Tools Ronie's voice model can call. They run in the brain so every model shares them."""
from __future__ import annotations

import asyncio
import time

from .devices import TV, DeviceError, Laptop, youtube_link, youtube_pick
from .games import GameManager
from .interview import Interview
from .memory import Memory
from .models import AllProvidersFailed, LLMRequest, Router
from .web import RESEARCH_INSTRUCTIONS, Web, WebError

BRAIN_TASKS = {"coding", "game", "interview", "general"}


class Tools:
    def __init__(self, router: Router, memory: Memory, web: Web, interview: Interview | None = None,
                 laptop: Laptop | None = None, tv: TV | None = None, game: GameManager | None = None) -> None:
        self.router, self.memory, self.web, self.interview = router, memory, web, interview
        self.laptop = laptop or Laptop()
        self.game = game or GameManager()
        self.game.router = self.router
        # async (tool, args) -> result, when the brain runs on a server and a laptop agent does laptop actions;
        # it raises DeviceError when no agent is connected (the laptop is off).
        self.laptop_agent = None
        self.tv = tv or TV(router.settings, web_search=getattr(web, "search", None))
        # async () -> {"home", "tv", "laptop", "facing"}: what Ronie can show things on right now; None = nothing.
        self.availability = None
        self._shown: tuple[str, float] = ("", 0.0)  # the last topic shown, and when

    async def run(self, name: str, args: dict) -> dict:
        handler = getattr(self, f"_{name}", None)
        if handler is None:
            return {"error": f"unknown tool {name}"}
        try:
            return await handler(args)
        except (AllProvidersFailed, WebError, DeviceError) as e:
            return {"error": str(e)}

    async def _ask_brain(self, args: dict) -> dict:
        task = args.get("task") if args.get("task") in BRAIN_TASKS else "general"
        r = await self.router.complete(LLMRequest(str(args.get("request", "")), task=task))
        return {"answer": r.text}

    async def _remember(self, args: dict) -> dict:
        text = str(args.get("text", "")).strip()
        if not text:
            return {"error": "nothing to remember"}
        self.memory.remember(text, str(args.get("kind", "note")), 5, source="user asked",
                             topic=str(args.get("topic") or ""), expires=str(args.get("expires") or ""))
        return {"saved": True}

    async def _recall(self, args: dict) -> dict:
        found = await self.memory.find(str(args.get("query", "")))
        return {
            "facts": [f["text"] for f in found["facts"]],
            "past_conversation": [f"{'User' if t['role'] == 'user' else 'Ronie'}: {t['text']}" for t in found["turns"]],
        }

    async def _forget(self, args: dict) -> dict:
        """Forgets one memory. When several fit, nothing is deleted: Ronie asks which one, then calls again with its
        id. Deleting the best guess once removed the wrong birthday."""
        try:
            fact_id = int(str(args.get("id") or "0").strip() or 0)
        except ValueError:
            fact_id = 0
        if fact_id and (fact := self.memory.fact(fact_id)) is not None:
            self.memory.forget(fact_id)
            return {"forgotten": 1, "text": fact["text"]}
        found, sure = await self.memory.candidates_to_forget(str(args.get("query", "")))
        if not found:
            return {"forgotten": 0, "note": "Nothing like that is saved."}
        if len(found) == 1 and sure:
            self.memory.forget(found[0]["id"])
            return {"forgotten": 1, "text": found[0]["text"]}
        return {"forgotten": 0, "which": [{"id": str(f["id"]), "text": f["text"]} for f in found],
                "note": "Not sure which memory this is. Ask the user (say the options briefly), then call forget "
                        "again with the chosen one's id."}

    async def _interview_cue(self, args: dict) -> dict:
        if self.interview is None:
            return {"error": "Interview mode isn't set up"}

        async def complete(prompt: str) -> str:
            # Fast free models, JSON out, no persona: the cue must arrive while the interviewer is still listening.
            return (await self.router.complete(LLMRequest(prompt, task="interview", raw=True, max_tokens=600))).text

        return await self.interview.cue(str(args.get("question", "")), complete)

    async def _laptop(self, args: dict) -> dict:
        kind = str(args.get("kind") or "")
        kinds = ("website", "app", "folder", "search", "youtube", "youtube_play")
        browser = str(args.get("browser") or "") or str(self.router.settings.data.get("laptop_browser") or "")
        # The model sometimes names it query or value instead of target ("Nothing to open" on 30 Oct).
        target = str(args.get("target") or args.get("query") or args.get("value") or "")
        kind = kind if kind in kinds else ""
        if self.laptop_agent is not None:
            return await self.laptop_agent("laptop", {"target": target, "kind": kind, "browser": browser})
        return await asyncio.to_thread(self.laptop.open, target, kind, browser)

    async def _laptop_control(self, args: dict) -> dict:
        if self.laptop_agent is not None:
            return await self.laptop_agent("laptop_control", {"action": str(args.get("action", "")), "times": _times(args)})
        return await asyncio.to_thread(self.laptop.control, str(args.get("action", "")), _times(args))

    async def _tv(self, args: dict) -> dict:
        result = await self.tv.control(str(args.get("action", "")), str(args.get("value") or ""), _times(args),
                                       app=str(args.get("app") or ""))
        return {**result, "via": self.tv.via}

    async def _show(self, args: dict) -> dict:
        """Shows something that helps with what the user asked, on the screen they face (from the photo taken when
        they called Ronie): the laptop gets a video and an article, the TV a video. Not knowing which, a video goes
        to the TV if it's on, else the laptop. Otherwise it quietly does nothing."""
        topic = str(args.get("topic") or "").strip()
        kind = "page" if str(args.get("kind") or "") == "page" else "video"
        if not topic:
            return {"error": "What should I show?"}
        if topic.lower() == self._shown[0] and time.time() - self._shown[1] < 300:  # it asked twice (video, then page)
            return {"skipped": True, "note": "Already showing this. Don't mention it again."}
        where = await self.availability() if self.availability else {}
        nothing = {"skipped": True, "note": "Nothing to show it on right now. Don't mention showing anything."}
        if not where.get("home"):
            return nothing
        self._shown = (topic.lower(), time.time())
        facing = where.get("facing")  # from the photo taken when the user called Ronie: "laptop", "tv" or None
        if facing == "laptop" and where.get("laptop"):
            return await self._show_on_laptop(topic)
        tv_first = facing == "tv" or (kind == "video" and facing is None)
        if tv_first and where.get("tv") and await self.tv.screen_on():
            result = await self.tv.find(topic, "youtube", play=True)
            return {**result, "where": "TV"}
        if where.get("laptop") and facing != "tv":  # facing the TV but it's off: not on the laptop behind them
            return await self._show_on_laptop(topic)
        return nothing

    async def _show_on_laptop(self, topic: str) -> dict:
        """A video and an article about the topic, laid out across the laptop's screens."""
        pick = await asyncio.to_thread(youtube_pick, topic, getattr(self.laptop, "_fetch", None),
                                       self.laptop.played.items)
        if pick:
            self.laptop.played.add(pick)
        page = await self._best_page(topic)
        args = {"video_url": youtube_link(pick) if pick else "", "page_url": page or "",
                "browser": str(self.router.settings.data.get("laptop_browser") or "")}
        if not args["video_url"] and not args["page_url"]:
            return {"skipped": True, "note": "Found nothing to show. Don't mention showing anything."}
        if self.laptop_agent is not None:
            result = await self.laptop_agent("laptop_show", args)
        else:
            result = await asyncio.to_thread(self.laptop.show, args["video_url"], args["page_url"], args["browser"])
        if result.get("error"):
            return {"skipped": True, "note": "Couldn't open it. Don't mention showing anything."}
        return {**result, "where": "laptop", **({"video_title": pick["title"]} if pick and pick["title"] else {})}

    async def _best_page(self, topic: str) -> str | None:
        """The top web result worth reading for a topic (not a video site)."""
        for r in await self.web.search(topic, n=6):
            url = r.get("url", "")
            if url.startswith("http") and not any(s in url for s in ("youtube.com", "youtu.be", "instagram.com")):
                return url
        return None

    async def _set_mode(self, args: dict) -> dict:
        # The phone switches its own mode; other clients have no modes.
        return {"error": "Modes are switched in the phone app"}

    async def _clock(self, args: dict) -> dict:
        from .persona import local_now
        now = local_now(self.router.settings)
        return {"time": now.strftime("%I:%M %p").lstrip("0"), "day": now.strftime("%A"),
                "date": now.strftime("%d %B %Y").lstrip("0"), "timezone": self.router.settings["timezone"]}

    async def _battery(self, args: dict) -> dict:
        # The phone answers this itself (it's connected to the glasses); other clients have no glasses.
        return {"error": "Battery is only known when Ronie runs on the phone with the glasses"}

    async def _end_conversation(self, args: dict) -> dict:
        # The phone handles this itself (back to waiting for the wake word); other clients just carry on.
        return {"done": "Conversation ended"}

    async def _look(self, args: dict) -> dict:
        # The phone app takes glasses photos itself; this only runs for clients without a camera (the PC).
        return {"error": "There's no camera here. Looking works when Ronie runs on the phone with the glasses."}

    async def _research_web(self, args: dict) -> dict:
        question = str(args.get("question", "")).strip()
        sources = await self.web.gather_sources(str(args.get("query") or question))
        r = await self.router.complete(
            LLMRequest(self.web.research_prompt(question, sources), task="research", system=RESEARCH_INSTRUCTIONS, max_tokens=1024)
        )
        return {"answer": r.text, "sources": [{"title": s["title"], "url": s["url"]} for s in sources[:4]]}

    async def _game(self, args: dict) -> dict:
        action = str(args.get("action") or "")
        return await self.game.dispatch(action, args)


def _times(args: dict) -> int:
    try:
        return int(float(args.get("times") or 1))
    except (TypeError, ValueError):
        return 1
