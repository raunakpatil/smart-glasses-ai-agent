"""Interview cues from a call on this laptop: record what the laptop plays (the interviewer's voice on Meet, Zoom,
Teams, a phone link…) and cut it into utterances. The glasses' microphone is tuned to the wearer and drops other
voices, so for an online interview this is the clean way to hear the interviewer."""
from __future__ import annotations

import asyncio
import logging
import math
import threading
from collections import deque
from typing import Awaitable, Callable

log = logging.getLogger(__name__)

RATE = 16_000
CHUNK = RATE // 10  # 100 ms
LOUD_DB = -48.0  # loopback audio is clean: anything above this is someone talking
END_OF_UTTERANCE_MS = 700  # this much quiet ends what the interviewer said (shorter = answers sooner)
STARTED_MS = 300  # this much speech means the interviewer has started talking (not a click)
MAX_UTTERANCE_MS = 30_000
MIN_SPEECH_MS = 500

OnUtterance = Callable[[bytes, float], Awaitable[None]]
OnTalking = Callable[[bool, bool], Awaitable[None]]  # (talking, being transcribed): the interviewer started / stopped


class Segmenter:
    """Energy-based utterance cutter for 100 ms chunks of 16 kHz int16 PCM (same rules as the phone's)."""

    def __init__(self) -> None:
        self.before: deque[bytes] = deque(maxlen=3)
        self.segment = bytearray()
        self.in_speech = False
        self.speech_ms = self.quiet_ms = self.voiced_ms = 0
        self.energy, self.samples = 0.0, 0
        self.talking = False  # announced as talking (STARTED_MS of speech so far)
        self.just_started = False  # set by the last feed()
        self.just_ended: bool | None = None  # set by the last feed(): ended, and whether it's being transcribed

    @staticmethod
    def level(chunk: bytes) -> float:
        import numpy as np

        a = np.frombuffer(chunk, dtype=np.int16).astype(np.float64)
        rms = math.sqrt(float((a * a).mean())) / 32768 if len(a) else 0.0
        return 20 * math.log10(max(rms, 1e-6))

    def feed(self, chunk: bytes) -> tuple[bytes, float] | None:
        """Returns (pcm, level dBFS) when an utterance ends. Also sets just_started / just_ended."""
        self.just_started, self.just_ended = False, None
        loud = self.level(chunk) > LOUD_DB
        if not self.in_speech:
            self.before.append(chunk)
            if not loud:
                return None
            self.in_speech = True
            self.segment = bytearray(b"".join(self.before))
            self.before.clear()
            self.speech_ms = self.quiet_ms = self.voiced_ms = 0
            self.energy, self.samples = 0.0, 0
        else:
            self.segment += chunk
        self.speech_ms += 100
        self.quiet_ms = 0 if loud else self.quiet_ms + 100
        if loud:
            self.voiced_ms += 100
            if not self.talking and self.voiced_ms >= STARTED_MS:
                self.talking = self.just_started = True
            import numpy as np

            a = np.frombuffer(chunk, dtype=np.int16).astype(np.float64)
            self.energy += float((a * a).sum())
            self.samples += len(a)
        if self.quiet_ms >= END_OF_UTTERANCE_MS or self.speech_ms >= MAX_UTTERANCE_MS:
            voiced = self.speech_ms - self.quiet_ms
            pcm = bytes(self.segment)
            rms = math.sqrt(self.energy / self.samples) / 32768 if self.samples else 1e-6
            self.in_speech = False
            self.segment = bytearray()
            if self.talking:
                self.talking = False
                self.just_ended = voiced >= MIN_SPEECH_MS
            if voiced >= MIN_SPEECH_MS:
                return pcm, 20 * math.log10(max(rms, 1e-6))
        return None


class LaptopListener:
    """Records what every output device plays (WASAPI loopback, one thread each) and hands utterances to the loop.

    Devices come and go during a call: a Bluetooth speaker or headset switches to its "Hands-Free" output as soon as
    a browser call uses its microphone, and that output only exists while the call runs. So a watcher re-checks the
    outputs every couple of seconds and records any new one (or one that dropped out and came back)."""

    RESCAN_S = 2.0
    RETRY_FAILED_S = 30.0  # a device that can't be recorded (e.g. a Wi-Fi speaker) is tried again this rarely

    def __init__(self, on_utterance: OnUtterance, on_device: Callable[[str], Awaitable[None]] | None = None,
                 on_talking: OnTalking | None = None) -> None:
        self.on_utterance = on_utterance
        self.on_talking = on_talking  # told the moment the interviewer starts and stops talking
        self.on_device = on_device  # told when an output starts being recorded
        self.device = ""
        self._stop = threading.Event()
        self._watcher: threading.Thread | None = None

    @property
    def running(self) -> bool:
        return self._watcher is not None and self._watcher.is_alive() and not self._stop.is_set()

    def start(self) -> str:
        """Starts listening to every output device (any app, any browser, whichever device it plays on); returns
        their names. Raises RuntimeError if the laptop's audio can't be recorded."""
        try:
            import soundcard as sc

            speakers = sc.all_speakers()
        except Exception as e:  # missing library, no audio device
            raise RuntimeError(f"Can't record the laptop's audio ({e})") from e
        if not speakers:
            raise RuntimeError("The laptop has no sound output to listen to")
        self.device = ", ".join(sp.name for sp in speakers)
        if self.running:
            return self.device
        if self._watcher is not None:
            self._watcher.join(timeout=1)  # a stop just before: let the old watcher finish
        self._stop = threading.Event()
        self._watcher = threading.Thread(target=self._watch, args=(asyncio.get_running_loop(), self._stop),
                                         name="laptop-audio", daemon=True)
        self._watcher.start()
        return self.device

    def stop(self) -> None:
        self._stop.set()

    def _watch(self, loop: asyncio.AbstractEventLoop, stop: threading.Event) -> None:
        import time

        import soundcard as sc

        threads: dict[str, threading.Thread] = {}
        failed_at: dict[str, float] = {}
        while not stop.is_set():
            try:
                speakers = sc.all_speakers()
            except Exception as e:
                log.info("can't list the laptop's outputs: %s", e)
                speakers = []
            for sp in speakers:
                t = threads.get(sp.id)
                if t is not None and t.is_alive():
                    continue
                if t is not None:  # it stopped: the device went away or failed
                    failed_at[sp.id] = time.monotonic()
                    threads.pop(sp.id)
                if time.monotonic() - failed_at.get(sp.id, -1e9) < self.RETRY_FAILED_S:
                    continue
                threads[sp.id] = threading.Thread(target=self._run, args=(loop, stop, sp.id, sp.name),
                                                  name=f"laptop-{sp.name}", daemon=True)
                threads[sp.id].start()
            stop.wait(self.RESCAN_S)

    def _run(self, loop: asyncio.AbstractEventLoop, stop: threading.Event, device_id, name: str) -> None:
        import numpy as np
        import soundcard as sc

        seg = Segmenter()
        try:
            mic = sc.get_microphone(device_id, include_loopback=True)
            with mic.recorder(samplerate=RATE, channels=1, blocksize=CHUNK) as rec:
                if self.on_device:
                    asyncio.run_coroutine_threadsafe(self.on_device(name), loop)
                while not stop.is_set():
                    frames = rec.record(numframes=CHUNK)
                    chunk = (np.clip(frames[:, 0], -1, 1) * 32767).astype(np.int16).tobytes()
                    done = seg.feed(chunk)
                    if self.on_talking and seg.just_started:
                        asyncio.run_coroutine_threadsafe(self.on_talking(True, False), loop)
                    if self.on_talking and seg.just_ended is not None:
                        asyncio.run_coroutine_threadsafe(self.on_talking(False, seg.just_ended), loop)
                    if done:
                        asyncio.run_coroutine_threadsafe(self.on_utterance(*done), loop)
        except Exception as e:  # e.g. a device that's off, gone, or can't be shared
            log.info("not recording %s: %s", name, e)
