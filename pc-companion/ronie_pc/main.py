"""Connects to the brain server on this computer and answers screen capture requests.

Week 1: connect, stay connected, capture the screen on request.
Week 2 adds the code overlay, the VS Code extension bridge and the coding-agent runner.
"""
from __future__ import annotations

import argparse
import asyncio
import base64
import json
import logging
import platform
import time
import uuid

import mss
import mss.tools
import websockets

log = logging.getLogger("ronie.pc")


def envelope(type_: str, payload: dict, id_: str | None = None) -> dict:
    return {"v": 1, "type": type_, "id": id_ or uuid.uuid4().hex[:12], "ts": time.time(), "payload": payload}


def capture_png(monitor: int = 1) -> bytes:
    with mss.mss() as sct:
        shot = sct.grab(sct.monitors[monitor])
        return mss.tools.to_png(shot.rgb, shot.size)


async def handle(ws, msg: dict) -> None:
    if msg.get("type") == "screen.capture.request":
        monitor = int((msg.get("payload") or {}).get("monitor", 1))
        png = await asyncio.to_thread(capture_png, monitor)
        reply = envelope("screen.capture.result", {"png_base64": base64.b64encode(png).decode()}, msg.get("id"))
        await ws.send(json.dumps(envelope("relay", {"to": "phone", "message": reply})))
        await ws.send(json.dumps(envelope("event", {"stage": "vision", "text": f"Screen captured ({len(png) // 1024} KB)"})))
    elif msg.get("type") == "error":
        log.warning("Brain error: %s", (msg.get("payload") or {}).get("message"))


async def run(url: str) -> None:
    delay = 1
    while True:
        try:
            async with websockets.connect(url, max_size=16 * 1024 * 1024) as ws:
                await ws.send(json.dumps(envelope("hello", {"role": "pc", "device": platform.node()})))
                welcome = json.loads(await ws.recv())
                if welcome.get("type") != "welcome":
                    raise RuntimeError(f"Unexpected reply: {welcome.get('type')}")
                log.info("Connected to the brain server")
                delay = 1
                async for raw in ws:
                    await handle(ws, json.loads(raw))
        except (OSError, websockets.ConnectionClosed, RuntimeError) as e:
            log.info("Not connected (%s). Retrying in %ss", e, delay)
            await asyncio.sleep(delay)
            delay = min(delay * 2, 30)


def main() -> None:
    parser = argparse.ArgumentParser(prog="ronie-pc")
    parser.add_argument("--url", default="ws://127.0.0.1:8790/ws/pc")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s")
    try:
        asyncio.run(run(args.url))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
