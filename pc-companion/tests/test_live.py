import asyncio
import base64
import json

from ronie_pc.brain_link import BrainError
from ronie_pc.live import LiveSession

CONFIG = {"system": "You are Ronie.", "voice": "", "functions": [{"name": "ask_brain"}], "google_search": True}


class FakeWs:
    def __init__(self):
        self.sent = []

    async def send(self, raw):
        self.sent.append(json.loads(raw))


class FakeOut:
    def __init__(self, busy=False):
        self.played, self.cleared, self._busy = bytearray(), 0, busy

    def play(self, pcm):
        self.played += pcm

    def clear(self):
        self.cleared += 1

    def busy(self):
        return self._busy


class FakeBrain:
    def __init__(self, fail=False):
        self.events, self.requests, self.fail, self.extras = [], [], fail, []

    async def event(self, stage, text, **extra):
        self.events.append((stage, text))
        self.extras.append(extra)

    async def request(self, type_, payload, timeout=60):
        self.requests.append((type_, payload))
        if self.fail:
            raise BrainError("brain server not running")
        return {"result": {"answer": "e4 is the classic choice."}}


def session(out=None, brain=None, headphones=False):
    return LiveSession("key", "gemini-3.1-flash-live-preview", CONFIG, brain or FakeBrain(), out or FakeOut(), headphones)


def test_setup_has_ronie_tools_and_transcripts():
    setup = session().setup_message()["setup"]
    assert setup["model"] == "models/gemini-3.1-flash-live-preview"
    assert setup["tools"][0]["functionDeclarations"][0]["name"] == "ask_brain"
    assert "inputAudioTranscription" in setup and "outputAudioTranscription" in setup
    assert {"googleSearch": {}} in setup["tools"]
    assert "slidingWindow" in setup["contextWindowCompression"]


async def test_plays_audio_and_reports_turn():
    brain, out = FakeBrain(), FakeOut()
    s = session(out, brain)
    shown = []
    s.on_transcript = lambda who, text: shown.append((who, text))
    audio = base64.b64encode(b"\x01\x02" * 10).decode()
    await s.handle(FakeWs(), {"serverContent": {"inputTranscription": {"text": "chai ya coffee?"}}})
    await s.handle(FakeWs(), {"serverContent": {"modelTurn": {"parts": [{"inlineData": {"data": audio}}]}, "outputTranscription": {"text": "Chai!"}}})
    await s.handle(FakeWs(), {"serverContent": {"turnComplete": True}})
    assert bytes(out.played) == b"\x01\x02" * 10
    assert shown == [("you", "chai ya coffee?"), ("ronie", "Chai!")]
    assert ("heard", "chai ya coffee?") in brain.events and ("answer", "Chai!") in brain.events
    assert brain.extras[-1]["source"] == "voice"


async def test_interruption_stops_playback():
    out = FakeOut()
    await session(out).handle(FakeWs(), {"serverContent": {"interrupted": True}})
    assert out.cleared == 1


async def test_tool_call_goes_to_brain_and_answers_live():
    brain, ws = FakeBrain(), FakeWs()
    s = session(brain=brain)
    await s._run_tool(ws, {"id": "c1", "name": "ask_brain", "args": {"task": "game", "request": "best first move?"}})
    assert brain.requests == [("tool.call", {"name": "ask_brain", "args": {"task": "game", "request": "best first move?"}})]
    response = ws.sent[0]["toolResponse"]["functionResponses"][0]
    assert response["id"] == "c1" and response["response"]["answer"].startswith("e4")


async def test_tool_call_reports_brain_down():
    ws = FakeWs()
    await session(brain=FakeBrain(fail=True))._run_tool(ws, {"id": "c2", "name": "ask_brain", "args": {"request": "x"}})
    assert "couldn't answer" in ws.sent[0]["toolResponse"]["functionResponses"][0]["response"]["error"]


async def test_mic_muted_while_speaking_on_laptop_speakers():
    async def mic():
        yield b"\x10\x27" * 1600  # loud chunk

    ws = FakeWs()
    await session(FakeOut(busy=True))._send_mic(ws, mic())
    assert ws.sent == []
    await session(FakeOut(busy=True), headphones=True)._send_mic(ws, mic())
    assert len(ws.sent) == 1 and ws.sent[0]["realtimeInput"]["audio"]["mimeType"] == "audio/pcm;rate=16000"


async def test_token_sign_in_uses_constrained_url(monkeypatch):
    import ronie_pc.live as live

    seen = {}

    async def fake_connect(url, additional_headers=None, **kw):
        seen["url"], seen["headers"] = url, additional_headers
        raise OSError("stop here")

    monkeypatch.setattr(live.websockets, "connect", fake_connect)
    s = LiveSession(None, "m", CONFIG, FakeBrain(), FakeOut(), token={"token": "auth_tokens/t1", "url": "wss://x/Constrained"})
    try:
        await s.run(None)
    except live.LiveSetupError:
        pass
    assert seen["url"] == "wss://x/Constrained?access_token=auth_tokens/t1" and seen["headers"] == {}


async def test_stop_closes_the_live_connection():
    closed = []

    class Ws:
        async def close(self):
            closed.append(True)

    s = session()
    s.stop()  # nothing open yet: no error
    s.ws = Ws()
    s.stop()
    await asyncio.sleep(0)
    assert closed == [True]
