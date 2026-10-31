import base64
import io
import json
import zipfile

import pytest

from conftest import FakeKeys, FakeProvider
from ronie_brain.interview import ImportError_, Interview, extract_text
from ronie_brain.persona import live_config

PROFILE = {
    "name": "Asha", "headline": "Backend engineer, 4 years", "skills": ["Python", "Kafka"],
    "experience": [], "projects": [],
    "stories": [{"title": "Payments API launch", "themes": ["conflict"], "situation": "s", "task": "t",
                 "action": "load test", "result": "errors down 40%", "keywords": "payments API … load test … minus 40 percent"}],
}
GAPS = {"strengths": ["APIs"], "gaps": ["Kubernetes"], "likely_questions": ["Tell me about a conflict"], "prepare": []}


@pytest.fixture
def iv(home):
    return Interview(home / "interview.db")


def docx_bytes(text):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("word/document.xml", f"<w:document><w:body><w:p><w:r><w:t>{text}</w:t></w:r></w:p></w:body></w:document>")
    return buf.getvalue()


def test_extract_text_from_text_and_docx():
    assert extract_text("cv.txt", b"Asha  Rao\n\n\nEngineer") == "Asha Rao\nEngineer"
    assert extract_text("cv.docx", docx_bytes("Built the payments API")) == "Built the payments API"
    with pytest.raises(ImportError_):
        extract_text("empty.txt", b"   ")


async def test_build_profile_and_gap_check(iv):
    iv.add_file("cv.txt", base64.b64encode(b"Asha, backend engineer. Led the payments API launch.").decode())
    iv.set_job("Senior backend engineer, Kubernetes required")
    prompts = []

    async def fake(prompt):
        prompts.append(prompt)
        return json.dumps(PROFILE if len(prompts) == 1 else GAPS)

    await iv.build(fake)
    assert iv.profile["stories"][0]["title"] == "Payments API launch"
    assert iv.gaps["gaps"] == ["Kubernetes"]
    assert "Led the payments API launch" in prompts[0] and "Kubernetes required" in prompts[1]


async def test_build_needs_documents(iv):
    with pytest.raises(ImportError_):
        await iv.build(lambda p: None)


async def test_cue_uses_profile_and_is_logged(iv):
    iv._set("profile", PROFILE)

    async def fake(prompt):
        assert "Payments API launch" in prompt and "conflict with a teammate" in prompt
        return '{"type": "behavioral", "story": "Payments API launch", "keywords": "payments API … load test", "card": "Launch · disagreed · load test · minus 40%"}'

    cue = await iv.cue("Tell me about a conflict with a teammate", fake)
    assert cue["keywords"] == "payments API … load test"
    assert iv.recent_cues()[0]["question"] == "Tell me about a conflict with a teammate"


async def test_cue_without_profile_says_so(iv):
    cue = await iv.cue("Why this company?", None)
    assert cue["keywords"] == "no profile yet"


def test_voice_modes(settings, iv):
    live = live_config(settings, "memory block", mode="interview_live", interview=iv)
    assert [f["name"] for f in live["functions"]] == ["interview_cue"] and "memory block" not in live["system"]
    iv._set("profile", PROFILE)
    iv._set("gaps", GAPS)
    practice = live_config(settings, mode="interview_practice", interview=iv)
    assert "Tell me about a conflict" in practice["system"] and "Asha" in practice["system"]
    assert live_config(settings, "memory block")["mode"] == "assistant"


def test_interview_endpoints_and_live_start_mode(settings, budget, pairing, home):
    from fastapi.testclient import TestClient
    from ronie_brain.app import create_app

    keys = FakeKeys()
    keys.set("gemini", "k")

    async def token(api_key):
        return {"token": "t", "url": "wss://x", "start_by": 0, "expires_at": 0}

    iv = Interview(home / "iv.db")
    app = create_app(settings=settings, keys=keys, budget=budget, pairing=pairing, providers={"groq": FakeProvider("groq")},
                     local_hosts=frozenset({"testclient"}), page_hosts=frozenset({"testserver"}),
                     live_token=token, interview=iv, advertise=False)
    with TestClient(app) as c:
        r = c.post("/api/interview/docs", json={"name": "cv.txt", "data_b64": base64.b64encode(b"Asha, engineer").decode()})
        assert r.status_code == 200 and r.json()["docs"][0]["name"] == "cv.txt"
        assert c.put("/api/interview/job", json={"text": "Backend role"}).json()["job"] == "Backend role"
        assert c.post("/api/interview/docs", json={"name": "x"}).status_code == 422
        with c.websocket_connect("/ws/phone") as ws:
            ws.send_json({"v": 1, "type": "hello", "payload": {"role": "phone", "token": pairing.token}})
            ws.receive_json()
            ws.send_json({"v": 1, "type": "live.start", "id": "s", "payload": {"mode": "interview_live"}})
            ready = ws.receive_json()
        assert ready["payload"]["config"]["mode"] == "interview_live"


async def test_respond_answers_interviewer_and_ignores_candidate(iv):
    iv._set("profile", PROFILE)
    replies = iter([
        '{"interviewer_question": true, "question": "Tell me about yourself", "points": ["Asha, backend engineer", "four years"]}',
        '{"interviewer_question": false, "question": "", "answer": "", "points": ""}',
    ])
    seen = []

    async def fake(prompt):
        seen.append(prompt)
        return next(replies)

    first = await iv.respond("So, tell me about yourself.", fake)
    assert first["interviewer_question"] and first["phrases"] == ["Asha, backend engineer", "four years"]
    assert first["answer"] == "• Asha, backend engineer\n• four years"
    second = await iv.respond("I have been working in the field for a few years.", fake)
    assert not second["interviewer_question"]
    assert "So, tell me about yourself." in seen[1]  # earlier lines are given as context
    assert [c["question"] for c in iv.recent_cues()] == ["Tell me about yourself"]


async def test_respond_without_profile(iv):
    r = await iv.respond("Tell me about yourself", None)
    assert r["interviewer_question"] is False and "profile" in r["error"]


def test_interview_segment_message(settings, budget, pairing, home, monkeypatch):
    from fastapi.testclient import TestClient
    from ronie_brain import app as app_mod

    async def fake_transcribe(wav, keys, prompt=""):
        assert wav[:4] == b"RIFF"
        return "Tell me about yourself"

    monkeypatch.setattr(app_mod, "transcribe", fake_transcribe)
    iv = Interview(home / "seg.db")
    iv._set("profile", PROFILE)
    reply = '{"interviewer_question": true, "question": "Tell me about yourself", "points": "Asha · backend engineer"}'
    app = app_mod.create_app(settings=settings, keys=FakeKeys(), budget=budget, pairing=pairing,
                             providers={"groq": FakeProvider("groq", reply=reply)},
                             local_hosts=frozenset({"testclient"}), page_hosts=frozenset({"testserver"}),
                             interview=iv, advertise=False)
    with TestClient(app) as c, c.websocket_connect("/ws/phone") as ws:
        ws.send_json({"v": 1, "type": "hello", "payload": {"role": "phone", "token": pairing.token}})
        ws.receive_json()
        ws.send_json({"v": 1, "type": "interview.segment", "id": "g1", "payload": {"audio_b64": base64.b64encode(b"\x00\x01" * 800).decode()}})
        r = ws.receive_json()
    assert r["type"] == "interview.answer" and r["payload"]["phrases"] == ["Asha", "backend engineer"]  # " · " string works too


async def test_loudness_is_learned_and_candidate_never_answered(iv):
    iv._set("profile", PROFILE)
    prompts = []
    replies = iter([
        '{"speaker": "candidate", "interviewer_question": false}',
        '{"speaker": "interviewer", "interviewer_question": true, "question": "Why us?", "answer": "Because...", "points": ""}',
        '{"speaker": "candidate", "interviewer_question": true, "question": "Why me?", "answer": "x", "points": ""}',
    ])

    async def fake(prompt):
        prompts.append(prompt)
        return next(replies)

    await iv.respond("I worked at PayKart.", fake, level_db=-14.0)
    await iv.respond("Why do you want to join us?", fake, level_db=-31.0)
    third = await iv.respond("Why should they pick me?", fake, level_db=-15.0)
    assert iv.levels == {"candidate": [-14.0, -15.0], "interviewer": [-31.0]}
    assert "candidate so far averaged -14 dBFS" in prompts[1]
    assert len(prompts) == 2  # -15 dB is clearly the candidate's level now: decided without asking the model
    assert third["interviewer_question"] is False  # something the candidate said is never answered


async def test_clear_loudness_overrides_question_like_words(iv):
    iv._set("profile", PROFILE)
    iv.calibrate(-14.0)
    iv.levels["interviewer"].append(-31.0)
    called = []

    async def fake(prompt):
        called.append(prompt)
        return '{"speaker": "interviewer", "interviewer_question": true, "question": "q", "answer": "a"}'

    mine = await iv.respond("What was the hardest part? Let me think.", fake, level_db=-15.0)
    assert mine["interviewer_question"] is False and mine["speaker"] == "candidate" and called == []
    theirs = await iv.respond("Why do you want to leave?", fake, level_db=-30.0)
    assert theirs["interviewer_question"] is True and "Judging by loudness, this line is the interviewer" in called[0]


async def test_known_speaker_skips_loudness(iv):
    iv._set("profile", PROFILE)
    prompts = []

    async def fake(prompt):
        prompts.append(prompt)
        return '{"speaker": "candidate", "interviewer_question": true, "question": "Why us?", "answer": "Because.", "points": ""}'

    mine = await iv.respond("Can you repeat the question?", fake, level_db=-30.0, speaker="candidate")
    assert mine["interviewer_question"] is False and prompts == []
    theirs = await iv.respond("Why do you want to join us?", fake, level_db=-30.0, speaker="interviewer")
    assert theirs["interviewer_question"] is True and theirs["speaker"] == "interviewer"
    assert "certainly the INTERVIEWER" in prompts[0] and "Can you repeat the question?" in prompts[0]
    assert iv.levels == {"candidate": [], "interviewer": []}


def test_segmenter_cuts_utterances():
    import numpy as np
    from ronie_brain.room_audio import Segmenter

    seg = Segmenter()
    tone = (np.sin(np.arange(1600) / 5) * 3000).astype(np.int16).tobytes()
    quiet = bytes(3200)
    out = [seg.feed(c) for c in [quiet] * 3 + [tone] * 10 + [quiet] * 9]
    done = [o for o in out if o]
    assert len(done) == 1 and -25 < done[0][1] < -15
    assert len(done[0][0]) == (2 + 10 + 7) * 3200  # 300 ms lead-in (incl. the first loud chunk), 700 ms of quiet
    assert [o for o in (seg.feed(c) for c in [tone] * 3 + [quiet] * 9) if o] == []  # too short


def test_interview_listen_message(settings, budget, pairing, home, monkeypatch):
    from fastapi.testclient import TestClient
    from ronie_brain import app as app_mod

    started = []

    class FakeListener:
        def __init__(self, on_utterance, on_device=None, on_talking=None):
            self.on_utterance = on_utterance

        def start(self):
            started.append(True)
            return "Speakers"

        def stop(self):
            started.append(False)

    monkeypatch.setattr(app_mod, "LaptopListener", FakeListener)
    app = app_mod.create_app(settings=settings, keys=FakeKeys(), budget=budget, pairing=pairing,
                             providers={"groq": FakeProvider("groq")}, local_hosts=frozenset({"testclient"}),
                             page_hosts=frozenset({"testserver"}), interview=Interview(home / "l.db"), advertise=False)
    with TestClient(app) as c, c.websocket_connect("/ws/phone") as ws:
        ws.send_json({"v": 1, "type": "hello", "payload": {"role": "phone", "token": pairing.token}})
        ws.receive_json()
        ws.send_json({"v": 1, "type": "interview.listen", "id": "l1", "payload": {"on": True}})
        r = ws.receive_json()
        assert r["type"] == "interview.listening" and r["payload"]["device"] == "Speakers"
    assert started[0] is True and False in started  # stopped when the phone left


def test_glasses_hint_falls_back_without_call_audio(settings, budget, pairing, home, monkeypatch):
    """The laptop listens but hears no call (interviewer on another device): glasses lines are judged, not ignored."""
    from fastapi.testclient import TestClient
    from ronie_brain import app as app_mod

    async def fake_transcribe(wav, keys, prompt=""):
        return "Introduce yourself, please."

    monkeypatch.setattr(app_mod, "transcribe", fake_transcribe)
    iv = Interview(home / "fb.db")
    iv._set("profile", PROFILE)
    reply = '{"speaker": "interviewer", "interviewer_question": true, "question": "Introduce yourself", "points": ["I am Asha"]}'
    app = app_mod.create_app(settings=settings, keys=FakeKeys(), budget=budget, pairing=pairing,
                             providers={"groq": FakeProvider("groq", reply=reply)},
                             local_hosts=frozenset({"testclient"}), page_hosts=frozenset({"testserver"}),
                             interview=iv, advertise=False)
    with TestClient(app) as c, c.websocket_connect("/ws/phone") as ws:
        ws.send_json({"v": 1, "type": "hello", "payload": {"role": "phone", "token": pairing.token}})
        ws.receive_json()
        ws.send_json({"v": 1, "type": "interview.segment", "id": "g",
                      "payload": {"audio_b64": base64.b64encode(b"\x00\x01" * 800).decode(), "speaker": "candidate"}})
        r = ws.receive_json()
    assert r["payload"]["interviewer_question"] is True and r["payload"]["phrases"] == ["I am Asha"]


async def test_glasses_copy_of_laptop_line_is_dropped_even_if_it_arrives_first(settings, budget, pairing, home, monkeypatch):
    """The call plays on the laptop: the glasses' mic also catches the interviewer, sometimes before the laptop's copy
    is transcribed. That line must not be taken as the user's."""
    import asyncio
    from ronie_brain import app as app_mod

    captured = {}

    class FakeListener:
        def __init__(self, on_utterance, on_device=None, on_talking=None):
            captured["cb"] = on_utterance

        def start(self):
            return "Speakers"

        def stop(self):
            pass

    async def fake_transcribe(wav, keys, prompt=""):
        return "Tell me about yourself please"

    monkeypatch.setattr(app_mod, "LaptopListener", FakeListener)
    monkeypatch.setattr(app_mod, "transcribe", fake_transcribe)
    monkeypatch.setattr(app_mod, "DUPLICATE_WAIT_S", 2.0)
    iv = Interview(home / "dup.db")
    iv._set("profile", PROFILE)
    reply = '{"speaker": "interviewer", "interviewer_question": true, "question": "q", "answer": "I am Asha.", "points": ""}'
    from fastapi.testclient import TestClient
    app = app_mod.create_app(settings=settings, keys=FakeKeys(), budget=budget, pairing=pairing,
                             providers={"groq": FakeProvider("groq", reply=reply)},
                             local_hosts=frozenset({"testclient"}), page_hosts=frozenset({"testserver"}),
                             interview=iv, advertise=False)
    with TestClient(app) as c, c.websocket_connect("/ws/phone") as ws:
        ws.send_json({"v": 1, "type": "hello", "payload": {"role": "phone", "token": pairing.token}})
        ws.receive_json()
        c.portal.call(captured["cb"], b"\x00\x01" * 8000, -20.0)  # the call was heard once already (call is live)
        ws.receive_json()  # its answer
        # Glasses copy first, the laptop's copy 0.5 s later.
        ws.send_json({"v": 1, "type": "interview.segment", "id": "g",
                      "payload": {"audio_b64": base64.b64encode(b"\x00\x01" * 800).decode(), "speaker": "candidate"}})

        async def later():
            await asyncio.sleep(0.5)
            await captured["cb"](b"\x00\x01" * 8000, -20.0)

        c.portal.start_task_soon(later)
        got = [ws.receive_json(), ws.receive_json()]
    glasses = next(m for m in got if m.get("id") == "g")
    assert glasses["payload"]["heard"] == ""  # dropped as a duplicate, not shown as "you"


def test_whisper_noise_is_dropped():
    from ronie_brain.speech import is_noise

    assert is_noise("www.prop.com.au") and is_noise("Thanks for watching!") and is_noise("you")
    assert not is_noise("Tell me about yourself.") and not is_noise("Why did you leave your last job?")


def test_answer_is_split_into_phrases_to_repeat():
    from ronie_brain.interview import PHRASE_MAX_WORDS, phrases

    answer = ("Sure. So at Sigma AI, I led a team of four reviewers and acted as the data controller for more than "
              "fourteen multilingual training pipelines. The outputs kept drifting, which hurt the client's trust. "
              "It taught me to measure first.")
    got = phrases(answer)
    assert " ".join(got) == " ".join(answer.split())  # nothing lost or reordered
    assert got[0] == "Sure. So at Sigma AI,"  # "Sure." alone is merged into the next phrase
    assert all(len(p.split()) <= PHRASE_MAX_WORDS + 1 for p in got)
    assert "and acted as the data controller" in " | ".join(got).replace(" | and", " and") or any(
        p.startswith("and acted") for p in got)
    assert phrases("") == []


async def test_check_repeat(iv):
    from ronie_brain.interview import words_covered

    assert words_covered("and improved dataset accuracy by about eighteen percent.",
                         "and I improved the dataset accuracy by roughly eighteen percent") >= 0.6
    asked = []

    async def fake(prompt):
        asked.append(prompt)
        return '{"verdict": "wrong", "correction": "eighteen percent, not eighty"}'

    ok = await iv.check_repeat("we improved dataset accuracy by eighteen percent", "improved dataset accuracy by about eighteen percent.", "", fake)
    assert ok == {"verdict": "ok", "correction": ""} and asked == []  # close enough: no model call
    wrong = await iv.check_repeat("humne accuracy eighty percent badhayi", "improved dataset accuracy by about eighteen percent.", "full answer", fake)
    assert wrong == {"verdict": "wrong", "correction": "eighteen percent, not eighty"} and "Hindi" in asked[0]
    await iv.check_repeat("and improved dataset accuracy by about eighty percent",
                          "and improved dataset accuracy by about eighteen percent.", "", fake)
    assert len(asked) == 2  # same words but a different number: the model is asked


def test_segment_with_expected_phrase_gets_checked(settings, budget, pairing, home, monkeypatch):
    """The call plays on the laptop, so the glasses' mic is the user repeating a phrase: what they said is checked."""
    from fastapi.testclient import TestClient
    from ronie_brain import app as app_mod

    captured = {}

    class FakeListener:
        def __init__(self, on_utterance, on_device=None, on_talking=None):
            captured["cb"] = on_utterance

        def start(self):
            return "Speakers"

        def stop(self):
            pass

    heard = iter(["Tell me about your last job", "I worked at some company"])

    async def fake_transcribe(wav, keys, prompt=""):
        return next(heard)

    monkeypatch.setattr(app_mod, "LaptopListener", FakeListener)
    monkeypatch.setattr(app_mod, "transcribe", fake_transcribe)
    monkeypatch.setattr(app_mod, "DUPLICATE_WAIT_S", 0.1)
    iv = Interview(home / "chk.db")
    iv._set("profile", PROFILE)
    reply = '{"verdict": "wrong", "correction": "Sigma AI, not Sigma Labs"}'
    app = app_mod.create_app(settings=settings, keys=FakeKeys(), budget=budget, pairing=pairing,
                             providers={"groq": FakeProvider("groq", reply=reply)},
                             local_hosts=frozenset({"testclient"}), page_hosts=frozenset({"testserver"}),
                             interview=iv, advertise=False)
    with TestClient(app) as c, c.websocket_connect("/ws/phone") as ws:
        ws.send_json({"v": 1, "type": "hello", "payload": {"role": "phone", "token": pairing.token}})
        ws.receive_json()
        c.portal.call(captured["cb"], b"\x00\x01" * 8000, -20.0)  # the interviewer, on the call
        ws.receive_json()
        ws.send_json({"v": 1, "type": "interview.segment", "id": "c", "payload": {
            "audio_b64": base64.b64encode(b"\x00\x01" * 800).decode(), "speaker": "candidate",
            "expected": "I worked at Sigma AI for two years", "answer": "I worked at Sigma AI for two years."}})
        r = ws.receive_json()
    assert r["payload"]["speaker"] == "candidate"
    assert r["payload"]["check"] == {"verdict": "wrong", "correction": "Sigma AI, not Sigma Labs"}


def test_segmenter_says_when_talking_starts_and_stops():
    import numpy as np
    from ronie_brain.room_audio import Segmenter

    seg = Segmenter()
    tone = (np.sin(np.arange(1600) / 5) * 3000).astype(np.int16).tobytes()
    quiet = bytes(3200)
    events = []
    for c in [quiet] * 2 + [tone] * 8 + [quiet] * 8 + [tone] * 2 + [quiet] * 8:
        seg.feed(c)
        if seg.just_started:
            events.append("start")
        if seg.just_ended is not None:
            events.append(("end", seg.just_ended))
    # 8 chunks: started after 300 ms, ended and transcribed; a 200 ms blip never counts as talking.
    assert events == ["start", ("end", True)]


def test_phone_saves_the_job_description(settings, budget, pairing, home):
    from fastapi.testclient import TestClient
    from ronie_brain.app import create_app

    iv = Interview(home / "job.db")
    app = create_app(settings=settings, keys=FakeKeys(), budget=budget, pairing=pairing,
                     providers={"groq": FakeProvider("groq")}, local_hosts=frozenset({"testclient"}),
                     page_hosts=frozenset({"testserver"}), interview=iv, advertise=False)
    with TestClient(app) as c, c.websocket_connect("/ws/phone") as ws:
        ws.send_json({"v": 1, "type": "hello", "payload": {"role": "phone", "token": pairing.token}})
        ws.receive_json()
        ws.send_json({"v": 1, "type": "interview.job", "id": "j", "payload": {"text": "Backend engineer, Python"}})
        r = ws.receive_json()
    assert r["type"] == "interview.job.saved" and iv.job == "Backend engineer, Python"
