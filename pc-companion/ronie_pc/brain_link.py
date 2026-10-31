"""WebSocket link to the brain server: request/response by message id, plus fire-and-forget events."""
from __future__ import annotations

import asyncio
import json
import logging
import platform
import time
import uuid

import websockets

log = logging.getLogger("ronie.brain_link")


def envelope(type_: str, payload: dict, id_: str | None = None) -> dict:
    return {"v": 1, "type": type_, "id": id_ or uuid.uuid4().hex[:12], "ts": time.time(), "payload": payload}


class BrainError(Exception):
    pass


class BrainLink:
    def __init__(self, url: str) -> None:
        self.url = url
        self.ws = None
        self.pending: dict[str, asyncio.Future] = {}
        self._reader: asyncio.Task | None = None
        self.on_push = lambda msg: None  # messages the brain sends on its own (e.g. live.restart)

    @property
    def connected(self) -> bool:
        return self.ws is not None

    async def connect(self) -> bool:
        try:
            self.ws = await websockets.connect(self.url, max_size=16 * 1024 * 1024, open_timeout=3)
            await self.ws.send(json.dumps(envelope("hello", {"role": "pc", "device": f"{platform.node()} voice"})))
            welcome = json.loads(await asyncio.wait_for(self.ws.recv(), 5))
            if welcome.get("type") != "welcome":
                raise BrainError(f"unexpected reply {welcome.get('type')}")
        except (OSError, asyncio.TimeoutError, websockets.WebSocketException, BrainError) as e:
            log.warning("Brain server not reachable at %s (%s). Voice works, but tools won't.", self.url, e)
            self.ws = None
            return False
        self._reader = asyncio.create_task(self._read())
        return True

    async def _read(self) -> None:
        try:
            async for raw in self.ws:
                msg = json.loads(raw)
                fut = self.pending.pop(msg.get("id"), None)
                if fut and not fut.done():
                    fut.set_result(msg)
                elif not fut:
                    self.on_push(msg)
        except websockets.ConnectionClosed:
            pass
        finally:
            self.ws = None
            for fut in self.pending.values():
                if not fut.done():
                    fut.set_exception(BrainError("brain server disconnected"))
            self.pending.clear()

    async def request(self, type_: str, payload: dict, timeout: float = 60) -> dict:
        if not self.ws:
            raise BrainError("brain server not running")
        msg = envelope(type_, payload)
        fut = asyncio.get_running_loop().create_future()
        self.pending[msg["id"]] = fut
        await self.ws.send(json.dumps(msg))
        reply = await asyncio.wait_for(fut, timeout)
        if reply.get("type") == "error":
            raise BrainError(reply["payload"].get("message", "brain error"))
        return reply["payload"]

    async def event(self, stage: str, text: str, **extra) -> None:
        if not self.ws:
            return
        try:
            await self.ws.send(json.dumps(envelope("event", {"stage": stage, "text": text, "extra": extra})))
        except websockets.ConnectionClosed:
            pass

    async def close(self) -> None:
        if self.ws:
            await self.ws.close()
