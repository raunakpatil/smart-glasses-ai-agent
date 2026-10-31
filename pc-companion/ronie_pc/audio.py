"""Laptop microphone (16 kHz in) and speaker (24 kHz out) for the voice client."""
from __future__ import annotations

import asyncio
import threading
import time

import numpy as np
import sounddevice as sd

MIC_RATE = 16000
SPEAKER_RATE = 24000
ECHO_TAIL_S = 0.4  # keep the mic closed a moment after Ronie stops, so the room's echo isn't sent


class Speaker:
    def __init__(self, device: int | None = None) -> None:
        self._buf = bytearray()
        self._lock = threading.Lock()
        self._last_sound = 0.0
        self.stream = sd.RawOutputStream(
            samplerate=SPEAKER_RATE, channels=1, dtype="int16", device=device, callback=self._callback, blocksize=1200
        )
        self.stream.start()

    def _callback(self, outdata, frames, time_info, status) -> None:
        n = frames * 2
        with self._lock:
            chunk = bytes(self._buf[:n])
            del self._buf[:n]
            if chunk:
                self._last_sound = time.monotonic()
        outdata[: len(chunk)] = chunk
        outdata[len(chunk):] = b"\x00" * (n - len(chunk))

    def play(self, pcm: bytes) -> None:
        with self._lock:
            self._buf.extend(pcm)

    def clear(self) -> None:
        with self._lock:
            self._buf.clear()

    def busy(self) -> bool:
        with self._lock:
            pending = bool(self._buf)
        return pending or time.monotonic() - self._last_sound < ECHO_TAIL_S

    def close(self) -> None:
        self.stream.stop()
        self.stream.close()


class Microphone:
    """Yields 100 ms chunks of 16 kHz mono PCM. Resamples if the device can't record at 16 kHz."""

    def __init__(self, device: int | None = None) -> None:
        self.queue: asyncio.Queue[bytes] = asyncio.Queue(maxsize=50)
        self.loop = asyncio.get_running_loop()
        try:
            self.rate = MIC_RATE
            self.stream = self._open(device, MIC_RATE)
        except sd.PortAudioError:
            self.rate = int(sd.query_devices(device, "input")["default_samplerate"])
            self.stream = self._open(device, self.rate)
        self.stream.start()

    def _open(self, device, rate) -> sd.RawInputStream:
        return sd.RawInputStream(
            samplerate=rate, channels=1, dtype="int16", device=device, blocksize=rate // 10, callback=self._callback
        )

    def _callback(self, indata, frames, time_info, status) -> None:
        pcm = bytes(indata)
        if self.rate != MIC_RATE:
            samples = np.frombuffer(pcm, dtype=np.int16).astype(np.float32)
            x_new = np.linspace(0, len(samples), int(len(samples) * MIC_RATE / self.rate), endpoint=False)
            pcm = np.interp(x_new, np.arange(len(samples)), samples).astype(np.int16).tobytes()
        self.loop.call_soon_threadsafe(self._put, pcm)

    def _put(self, pcm: bytes) -> None:
        try:
            self.queue.put_nowait(pcm)
        except asyncio.QueueFull:
            pass  # drop audio rather than fall behind

    async def chunks(self):
        while True:
            yield await self.queue.get()

    def close(self) -> None:
        self.stream.stop()
        self.stream.close()
