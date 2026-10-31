"""One Gemini Live voice session: stream mic audio in, play Ronie's audio out, run tool calls through the brain.

Kept free of audio-device code so it can be tested with fake audio.
"""
from __future__ import annotations

import asyncio
import base64
import json
import logging
import time
from typing import AsyncIterator, Protocol

import websockets

from .brain_link import BrainError, BrainLink

log = logging.getLogger("ronie.live")

LIVE_URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
MIC_RATE = 16000
SPEECH_PEAK = 1500  # 16-bit sample level above which a mic chunk counts as speech


class Output(Protocol):
    def play(self, pcm: bytes) -> None: ...
    def clear(self) -> None: ...
    def busy(self) -> bool: ...


class LiveSetupError(Exception):
    """The model refused the session (not available, quota used up, bad key)."""


class LiveSession:
    def __init__(
        self, key: str | None, model: str, config: dict, brain: BrainLink, out: Output,
        headphones: bool = False, token: dict | None = None,
    ) -> None:
        """Signs in with a one-use token from the brain (like the phone) or, without one, the API key."""
        self.key, self.model, self.config, self.brain, self.out = key, model, config, brain, out
        self.token = token
        self.ws = None
        self.headphones = headphones
        self.heard = ""
        self.said = ""
        self.last_voice_at = 0.0
        self.first_audio_ms: int | None = None
        self.on_transcript = lambda who, text: None  # set by the caller to print the conversation

    def setup_message(self) -> dict:
        generation: dict = {"responseModalities": ["AUDIO"]}
        if self.config.get("voice"):
            generation["speechConfig"] = {"voiceConfig": {"prebuiltVoiceConfig": {"voiceName": self.config["voice"]}}}
        return {
            "setup": {
                "model": f"models/{self.model}",
                "generationConfig": generation,
                "systemInstruction": {"parts": [{"text": self.config["system"]}]},
                "tools": self.tools(),
                "inputAudioTranscription": {},
                "outputAudioTranscription": {},
                # Long conversations: let Gemini drop the oldest audio instead of ending the session.
                # Nothing is lost: the brain's shared memory keeps the conversation.
                "contextWindowCompression": {"slidingWindow": {}},
                # Decide sooner that the user has finished speaking (Gemini's default waits longer).
                "realtimeInputConfig": {
                    "automaticActivityDetection": {
                        "endOfSpeechSensitivity": "END_SENSITIVITY_HIGH",
                        "silenceDurationMs": self.config.get("silence_ms", 500),
                    }
                },
            }
        }

    def tools(self) -> list[dict]:
        tools: list[dict] = [{"functionDeclarations": self.config.get("functions", [])}]
        if self.config.get("google_search"):
            tools.append({"googleSearch": {}})  # quick facts straight from Google, inside the voice model
        return tools

    async def run(self, mic: AsyncIterator[bytes]) -> None:
        """Runs until the server ends the session (time limit, goAway) or the mic stream ends."""
        if self.token:
            url, headers = f"{self.token['url']}?access_token={self.token['token']}", {}
        else:
            url, headers = LIVE_URL, {"x-goog-api-key": self.key}
        try:
            ws = await websockets.connect(url, additional_headers=headers, max_size=None, open_timeout=10)
        except (OSError, websockets.WebSocketException) as e:
            raise LiveSetupError(f"can't connect: {e}") from e
        self.ws = ws
        async with ws:
            await ws.send(json.dumps(self.setup_message()))
            try:
                first = json.loads(await asyncio.wait_for(ws.recv(), 15))
            except websockets.ConnectionClosed as e:
                reason = e.rcvd.reason if e.rcvd else str(e)
                raise LiveSetupError(reason or "closed during setup") from e
            if "setupComplete" not in first:
                raise LiveSetupError(str(first)[:200])
            await self.brain.event("connect", f"Voice session started on {self.model}")

            sender = asyncio.create_task(self._send_mic(ws, mic))
            try:
                async for raw in ws:
                    await self.handle(ws, json.loads(raw))
            except websockets.ConnectionClosed:
                pass
            finally:
                sender.cancel()

    def stop(self) -> None:
        """End this session (e.g. the voice changed); the caller starts a new one with fresh settings."""
        if self.ws is not None:
            asyncio.ensure_future(self.ws.close())

    async def _send_mic(self, ws, mic: AsyncIterator[bytes]) -> None:
        async for chunk in mic:
            # On laptop speakers the mic hears Ronie; don't send that back or Ronie interrupts itself.
            if not self.headphones and self.out.busy():
                continue
            if chunk and max(abs(s) for s in memoryview(chunk).cast("h")) > SPEECH_PEAK:
                self.last_voice_at = time.perf_counter()  # for measuring reply delay
            await ws.send(json.dumps({
                "realtimeInput": {"audio": {"data": base64.b64encode(chunk).decode(), "mimeType": f"audio/pcm;rate={MIC_RATE}"}}
            }))

    async def handle(self, ws, msg: dict) -> None:
        content = msg.get("serverContent")
        if content:
            if text := content.get("inputTranscription", {}).get("text"):
                self.heard += text
            if text := content.get("outputTranscription", {}).get("text"):
                self.said += text
            for part in content.get("modelTurn", {}).get("parts", []):
                if data := part.get("inlineData", {}).get("data"):
                    if self.first_audio_ms is None and self.last_voice_at:
                        self.first_audio_ms = int((time.perf_counter() - self.last_voice_at) * 1000)
                    self.out.play(base64.b64decode(data))
            if content.get("interrupted"):
                self.out.clear()
            if content.get("turnComplete"):
                await self._finish_turn()

        if call := msg.get("toolCall"):
            for fc in call.get("functionCalls", []):
                asyncio.create_task(self._run_tool(ws, fc))

        if "goAway" in msg:
            log.info("Live session ending soon (server time limit); reconnecting")

    async def _finish_turn(self) -> None:
        heard, said = self.heard.strip(), self.said.strip()
        if heard:
            self.on_transcript("you", heard)
            await self.brain.event("heard", heard, source="voice")
        if said:
            self.on_transcript("ronie", said)
            extra = {"provider": "gemini live", "model": self.model, "source": "voice"}
            if self.first_audio_ms is not None:
                extra["ms"] = self.first_audio_ms
            await self.brain.event("answer", said, **extra)
        self.heard, self.said, self.first_audio_ms, self.last_voice_at = "", "", None, 0.0

    async def _run_tool(self, ws, fc: dict) -> None:
        name, args = fc.get("name"), fc.get("args") or {}
        # Every tool runs in the brain, so all voice models share the same memory, web access and models.
        try:
            reply = await self.brain.request("tool.call", {"name": name, "args": args}, timeout=90)
            result = reply["result"]
        except (BrainError, asyncio.TimeoutError) as e:
            result = {"error": f"Ronie's brain couldn't answer: {e}"}
        try:
            await ws.send(json.dumps({"toolResponse": {"functionResponses": [{"id": fc.get("id"), "name": name, "response": result}]}}))
        except websockets.ConnectionClosed:
            pass
