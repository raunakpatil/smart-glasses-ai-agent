"""Tracks connected clients by role and sends protocol messages."""
from __future__ import annotations

import time
import uuid
from typing import Any

from fastapi import WebSocket

ROLES = ("phone", "pc", "dashboard", "home")


def envelope(type_: str, payload: dict[str, Any], id_: str | None = None) -> dict[str, Any]:
    return {"v": 1, "type": type_, "id": id_ or uuid.uuid4().hex[:12], "ts": time.time(), "payload": payload}


class Hub:
    def __init__(self) -> None:
        self.clients: dict[str, set[WebSocket]] = {role: set() for role in ROLES}

    def add(self, role: str, ws: WebSocket) -> None:
        self.clients[role].add(ws)

    def remove(self, role: str, ws: WebSocket) -> None:
        self.clients[role].discard(ws)

    def counts(self) -> dict[str, int]:
        return {role: len(sockets) for role, sockets in self.clients.items()}

    async def send(self, ws: WebSocket, type_: str, payload: dict[str, Any], id_: str | None = None) -> None:
        await ws.send_json(envelope(type_, payload, id_))

    async def send_raw(self, role: str, message: dict[str, Any]) -> int:
        """Send a ready-made envelope to every client with this role. Returns how many got it."""
        sent = 0
        for ws in list(self.clients[role]):
            try:
                await ws.send_json(message)
                sent += 1
            except Exception:
                self.remove(role, ws)
        return sent

    async def broadcast(self, role: str, type_: str, payload: dict[str, Any]) -> int:
        return await self.send_raw(role, envelope(type_, payload))

    async def dashboard_event(self, stage: str, text: str, extra: dict[str, Any] | None = None) -> None:
        await self.broadcast("dashboard", "event", {"stage": stage, "text": text, **(extra or {})})
