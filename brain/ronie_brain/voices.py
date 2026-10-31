"""Gemini Live's built-in voices, and short preview clips so the user can hear one before choosing it.

Previews are made once with a real Live session and cached in ~/.ronie/voice_previews/.
"""
from __future__ import annotations

import base64
import io
import json
import wave
from pathlib import Path

import websockets

from .config import data_dir

LIVE_URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
PREVIEW_MODEL = "gemini-3.1-flash-live-preview"
PREVIEW_TEXT = "Namaste! Main Ronie hoon. Aaj main aapki kya madad kar sakta hoon?"

# name, how it sounds (Google's description), voice type. Listen to the previews: descriptions are approximate.
VOICES = [
    ("Charon", "Informative, calm", "male"),
    ("Puck", "Upbeat", "male"),
    ("Orus", "Firm", "male"),
    ("Fenrir", "Excitable", "male"),
    ("Iapetus", "Clear", "male"),
    ("Umbriel", "Easy-going", "male"),
    ("Algieba", "Smooth", "male"),
    ("Algenib", "Gravelly", "male"),
    ("Rasalgethi", "Informative", "male"),
    ("Alnilam", "Firm", "male"),
    ("Schedar", "Even", "male"),
    ("Achird", "Friendly", "male"),
    ("Zubenelgenubi", "Casual", "male"),
    ("Sadachbia", "Lively", "male"),
    ("Sadaltager", "Knowledgeable", "male"),
    ("Enceladus", "Breathy", "male"),
    ("Kore", "Firm", "female"),
    ("Aoede", "Breezy", "female"),
    ("Zephyr", "Bright", "female"),
    ("Leda", "Youthful", "female"),
    ("Sulafat", "Warm", "female"),
    ("Despina", "Smooth", "female"),
    ("Vindemiatrix", "Gentle", "female"),
    ("Achernar", "Soft", "female"),
    ("Callirrhoe", "Easy-going", "female"),
    ("Autonoe", "Bright", "female"),
    ("Erinome", "Clear", "female"),
    ("Laomedeia", "Upbeat", "female"),
    ("Gacrux", "Mature", "female"),
    ("Pulcherrima", "Forward", "female"),
]
VOICE_NAMES = {v[0] for v in VOICES}


class PreviewError(Exception):
    pass


def voice_list(current: str) -> list[dict]:
    return [{"name": n, "style": s, "type": t, "current": n == current} for n, s, t in VOICES]


def _wav(pcm: bytes, rate: int = 24000) -> bytes:
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(pcm)
    return buf.getvalue()


async def preview(voice: str, api_key: str, folder: Path | None = None) -> bytes:
    """A WAV clip of Ronie saying a short line in this voice. Cached after the first time."""
    if voice not in VOICE_NAMES:
        raise PreviewError(f"Unknown voice {voice}")
    folder = folder or data_dir() / "voice_previews"
    folder.mkdir(parents=True, exist_ok=True)
    cached = folder / f"{voice}.wav"
    if cached.exists():
        return cached.read_bytes()
    setup = {
        "setup": {
            "model": f"models/{PREVIEW_MODEL}",
            "generationConfig": {
                "responseModalities": ["AUDIO"],
                "speechConfig": {"voiceConfig": {"prebuiltVoiceConfig": {"voiceName": voice}}},
            },
            "systemInstruction": {"parts": [{"text": "Say the user's text exactly, warmly and naturally. Say nothing else."}]},
        }
    }
    pcm = bytearray()
    try:
        async with websockets.connect(LIVE_URL, additional_headers={"x-goog-api-key": api_key}, max_size=None, open_timeout=10) as ws:
            await ws.send(json.dumps(setup))
            if "setupComplete" not in json.loads(await ws.recv()):
                raise PreviewError("Gemini didn't accept this voice")
            await ws.send(json.dumps({"clientContent": {"turns": [{"role": "user", "parts": [{"text": PREVIEW_TEXT}]}], "turnComplete": True}}))
            async for raw in ws:
                content = json.loads(raw).get("serverContent", {})
                for part in content.get("modelTurn", {}).get("parts", []):
                    if data := part.get("inlineData", {}).get("data"):
                        pcm += base64.b64decode(data)
                if content.get("turnComplete"):
                    break
    except websockets.ConnectionClosed as e:
        raise PreviewError(e.rcvd.reason if e.rcvd else "Gemini closed the connection") from e
    except OSError as e:
        raise PreviewError(f"can't reach Gemini ({e})") from e
    if not pcm:
        raise PreviewError("no audio came back")
    clip = _wav(bytes(pcm))
    cached.write_bytes(clip)
    return clip
