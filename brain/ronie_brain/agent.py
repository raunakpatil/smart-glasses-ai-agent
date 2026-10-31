"""`ronie-brain agent`: runs on the laptop when the brain lives on a cloud server. It connects out to the brain (no
ports to open at home) and does what only the laptop can: open sites and apps, press media keys, and record the
laptop's own audio for interview mode."""
from __future__ import annotations

import asyncio
import base64
import json
import logging
import socket
import uuid

import websockets

from .devices import DeviceError, Laptop
from .room_audio import LaptopListener

log = logging.getLogger(__name__)


class Agent:
    def __init__(self, url: str, token: str, laptop: Laptop | None = None) -> None:
        self.url = url.rstrip("/") + "/ws/home"
        self.token = token
        self.laptop = laptop or Laptop()
        self.ws = None
        self.listener: LaptopListener | None = None

    async def run_forever(self) -> None:
        delay = 1
        while True:
            try:
                async with websockets.connect(self.url, max_size=None, ping_interval=20) as ws:
                    self.ws = ws
                    await self._send("hello", {"role": "home", "token": self.token, "device": socket.gethostname()})
                    welcome = json.loads(await ws.recv())
                    if welcome.get("type") != "welcome":
                        raise ConnectionError(f"not accepted: {welcome}")
                    log.info("connected to %s", self.url)
                    delay = 1
                    async for raw in ws:
                        msg = json.loads(raw)
                        asyncio.create_task(self._handle(msg.get("type"), msg.get("payload") or {}, msg.get("id")))
            except (OSError, ConnectionError, websockets.WebSocketException) as e:
                log.info("brain not reachable (%s); retrying in %ss", e, delay)
            finally:
                self.ws = None
                if self.listener:
                    self.listener.stop()
            await asyncio.sleep(delay)
            delay = min(delay * 2, 30)

    async def _send(self, type_: str, payload: dict, id_: str | None = None) -> None:
        if self.ws is not None:
            await self.ws.send(json.dumps({"v": 1, "type": type_, "id": id_ or uuid.uuid4().hex[:12], "payload": payload}))

    async def _handle(self, type_: str | None, payload: dict, id_: str | None) -> None:
        if type_ == "device.run":
            await self._send("device.result", {"result": await self._run(payload)}, id_)
        elif type_ == "interview.listen":
            if payload.get("on"):
                try:
                    if self.listener is None:
                        self.listener = LaptopListener(self._utterance, on_talking=self._talking)
                    device = self.listener.start()
                    await self._send("device.result", {"result": {"on": True, "device": device}}, id_)
                except RuntimeError as e:
                    await self._send("device.result", {"result": {"error": str(e)}}, id_)
            else:
                if self.listener:
                    self.listener.stop()
                await self._send("device.result", {"result": {"on": False}}, id_)

    async def _run(self, payload: dict) -> dict:
        tool, args = payload.get("tool"), payload.get("args") or {}
        try:
            if tool == "laptop":
                return await asyncio.to_thread(self.laptop.open, str(args.get("target", "")), str(args.get("kind") or ""),
                                               str(args.get("browser") or ""))
            if tool == "laptop_show":
                return await asyncio.to_thread(self.laptop.show, str(args.get("video_url") or ""),
                                               str(args.get("page_url") or ""), str(args.get("browser") or ""))
            if tool == "laptop_status":
                return await asyncio.to_thread(self.laptop.status)
            if tool == "laptop_control":
                return await asyncio.to_thread(self.laptop.control, str(args.get("action", "")), int(args.get("times") or 1))
            return {"error": f"The laptop can't run {tool}"}
        except DeviceError as e:
            return {"error": str(e)}

    async def _utterance(self, pcm: bytes, level_db: float) -> None:
        await self._send("interview.laptop_audio", {"audio_b64": base64.b64encode(pcm).decode(), "level_db": level_db})

    async def _talking(self, talking: bool, transcribing: bool) -> None:
        await self._send("interview.laptop_talking", {"talking": talking, "transcribing": transcribing})


def main(url: str, token: str) -> None:
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s")
    print(f"Ronie laptop agent: connecting to {url}. Leave this window open; Ctrl+C to stop.")
    try:
        asyncio.run(Agent(url, token).run_forever())
    except KeyboardInterrupt:
        pass
