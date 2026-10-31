"""Speech to text for short recorded utterances (interview mode), using Groq's free Whisper."""
from __future__ import annotations

import io
import wave

import httpx

from .config import Keys
from .models.providers_common import shared_client

GROQ_TRANSCRIBE = "https://api.groq.com/openai/v1/audio/transcriptions"
# Fastest first; the large model is the fallback when the turbo one is rate-limited.
WHISPER_MODELS = ("whisper-large-v3-turbo", "whisper-large-v3")


class SpeechError(Exception):
    pass


def pcm_to_wav(pcm: bytes, rate: int = 16_000) -> bytes:
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(pcm)
    return buf.getvalue()


# What Whisper tends to write for a click, a beep or silence (it was trained on subtitled videos).
NOISE_PHRASES = ("thank you for watching", "thanks for watching", "subscribe", "subtitles by", "amara.org",
                 "you", "thank you.", "bye.", ".")


def is_noise(text: str) -> bool:
    t = text.strip().lower()
    if not t or t in NOISE_PHRASES or any(p in t for p in NOISE_PHRASES[:5]):
        return True
    return " " not in t and ("www." in t or ".com" in t or "http" in t)  # a lone web address


async def transcribe(wav: bytes, keys: Keys, prompt: str = "") -> str:
    """Text of a short WAV clip. prompt: words likely to appear (names, job terms) to help recognition."""
    key = keys.get("groq")
    if not key:
        raise SpeechError("Interview mode needs a Groq key (free) in mission control")
    if len(wav) < 44 + 16_000:  # under half a second of audio: nothing worth transcribing
        return ""
    last = "no Whisper model answered"
    rejected = 0
    for model in WHISPER_MODELS:
        data = {"model": model, "response_format": "json", "temperature": "0"}
        if prompt:
            data["prompt"] = prompt[:400]
        try:
            r = await shared_client().post(
                GROQ_TRANSCRIBE,
                headers={"Authorization": f"Bearer {key}"},
                data=data,
                files={"file": ("speech.wav", wav, "audio/wav")},
                timeout=20,
            )
        except httpx.HTTPError as e:
            last = f"can't reach Groq ({type(e).__name__})"
            continue
        if r.status_code == 200:
            text = (r.json().get("text") or "").strip()
            return "" if is_noise(text) else text
        last = f"Groq {model} answered {r.status_code}"
        rejected += r.status_code == 400
    if rejected == len(WHISPER_MODELS):  # the clip itself was unusable (silence, noise): treat as nothing heard
        return ""
    raise SpeechError(last)
