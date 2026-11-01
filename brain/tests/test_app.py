import json

import pytest
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from conftest import FakeKeys, FakeProvider
from ronie_brain.app import create_app


@pytest.fixture
def keys():
    return FakeKeys()


def client_for(settings, budget, pairing, keys, local=True):
    app = create_app(
        settings=settings,
        keys=keys,
        budget=budget,
        pairing=pairing,
        providers={"gemini": FakeProvider("gemini"), "claude": FakeProvider("claude", paid=True)},
        local_hosts=frozenset({"testclient"}) if local else frozenset(),
        page_hosts=frozenset({"testserver"}),
        advertise=False,
    )
    return TestClient(app)


def hello(token, role="phone"):
    return {"v": 1, "type": "hello", "id": "h1", "payload": {"role": role, "token": token, "device": "test"}}


def test_health(settings, budget, pairing, keys):
    with client_for(settings, budget, pairing, keys) as c:
        assert c.get("/health").json()["ok"] is True


def test_status_is_local_only(settings, budget, pairing, keys):
    with client_for(settings, budget, pairing, keys, local=False) as c:
        assert c.get("/api/status").status_code == 403
        assert c.get("/pair").status_code == 403


def test_pair_page_has_qr_with_token(settings, budget, pairing, keys):
    with client_for(settings, budget, pairing, keys) as c:
        html = c.get("/pair").text
        assert "<svg" in html and "{{QR}}" not in html
    assert json.loads(pairing.qr_payload("10.0.0.2", 8790))["token"] == pairing.token


def test_phone_rejected_with_wrong_token(settings, budget, pairing, keys):
    with client_for(settings, budget, pairing, keys) as c:
        with c.websocket_connect("/ws/phone") as ws:
            ws.send_json(hello("wrong"))
            with pytest.raises(WebSocketDisconnect) as e:
                ws.receive_json()
            assert e.value.code == 4401


def test_phone_llm_request_round_trip(settings, budget, pairing, keys):
    with client_for(settings, budget, pairing, keys) as c:
        with c.websocket_connect("/ws/phone") as ws:
            ws.send_json(hello(pairing.token))
            assert ws.receive_json()["type"] == "welcome"
            ws.send_json({"v": 1, "type": "llm.request", "id": "q1", "payload": {"prompt": "namaste", "task": "general"}})
            reply = ws.receive_json()
            assert reply["type"] == "llm.response" and reply["id"] == "q1"
            assert reply["payload"]["provider"] == "gemini"


def test_phone_sets_key_and_settings(settings, budget, pairing, keys):
    with client_for(settings, budget, pairing, keys) as c:
        with c.websocket_connect("/ws/phone") as ws:
            ws.send_json(hello(pairing.token))
            ws.receive_json()
            ws.send_json({"v": 1, "type": "keys.set", "id": "k1", "payload": {"provider": "claude", "key": " sk-test "}})
            assert ws.receive_json()["payload"] == {"ok": True}
            ws.send_json({"v": 1, "type": "settings.update", "id": "s1", "payload": {"settings": {"premium": True}}})
            assert ws.receive_json()["type"] == "ack"
    assert keys.get("claude") == "sk-test"
    assert settings["premium"] is True


def test_pc_on_same_machine_needs_no_token(settings, budget, pairing, keys):
    with client_for(settings, budget, pairing, keys) as c:
        with c.websocket_connect("/ws/pc") as ws:
            ws.send_json(hello(None, role="pc"))
            assert ws.receive_json()["type"] == "welcome"


def test_dashboard_receives_events(settings, budget, pairing, keys):
    with client_for(settings, budget, pairing, keys) as c:
        with c.websocket_connect("/ws/dashboard") as dash:
            r = c.post("/api/llm", json={"prompt": "hello"})
            assert r.status_code == 200 and r.json()["provider"] == "gemini"
            stages = [dash.receive_json()["payload"]["stage"] for _ in range(3)]
            assert stages == ["heard", "model", "answer"]


def test_mission_control_saves_and_removes_key(settings, budget, pairing, keys):
    with client_for(settings, budget, pairing, keys) as c:
        r = c.put("/api/keys/claude", json={"key": "  sk-secret  "})
        assert r.status_code == 200
        assert r.json()["providers"]["claude"]["has_key"] is True
        assert "sk-secret" not in r.text
        assert keys.get("claude") == "sk-secret"
        r = c.delete("/api/keys/claude")
        assert r.status_code == 200 and r.json()["providers"]["claude"]["has_key"] is False
    assert keys.get("claude") is None


def test_key_change_rejected_from_other_website(settings, budget, pairing, keys):
    with client_for(settings, budget, pairing, keys) as c:
        r = c.put("/api/keys/groq", json={"key": "x"}, headers={"Origin": "https://evil.example"})
        assert r.status_code == 403
        r = c.put("/api/keys/groq", json={"key": "x"}, headers={"Host": "evil.example:8790"})
        assert r.status_code == 403
    assert keys.get("groq") is None


def test_key_change_validates_input(settings, budget, pairing, keys):
    with client_for(settings, budget, pairing, keys) as c:
        assert c.put("/api/keys/ollama", json={"key": "x"}).status_code == 404
        assert c.put("/api/keys/groq", json={"key": "   "}).status_code == 422


def test_voice_turns_go_into_memory_and_live_config(settings, budget, pairing, keys):
    with client_for(settings, budget, pairing, keys) as c:
        with c.websocket_connect("/ws/pc") as ws:
            ws.send_json(hello(None, role="pc"))
            ws.receive_json()
            ws.send_json({"v": 1, "type": "event", "payload": {"stage": "heard", "text": "Remember my locker is 42", "extra": {"source": "voice"}}})
            ws.send_json({"v": 1, "type": "tool.call", "id": "t1", "payload": {"name": "remember", "args": {"text": "The user's gym locker is number 42."}}})
            reply = ws.receive_json()
            assert reply["type"] == "tool.result" and reply["payload"]["result"] == {"saved": True}
        system = c.get("/api/live-config").json()["system"]
        assert "gym locker is number 42" in system and "User: Remember my locker is 42" in system
        mem = c.get("/api/memory").json()
        assert [f["text"] for f in mem["facts"]] == ["The user's gym locker is number 42."]
        fid = mem["facts"][0]["id"]
        assert c.delete(f"/api/memory/facts/{fid}").json()["facts"] == []


def test_live_start_gives_token_config_and_memory(settings, budget, pairing, keys, home):
    async def fake_token(api_key):
        assert api_key == "gem-key"
        return {"token": "auth_tokens/abc", "url": "wss://example/constrained", "start_by": 1, "expires_at": 2}

    keys.set("gemini", "gem-key")
    app = create_app(settings=settings, keys=keys, budget=budget, pairing=pairing, providers={},
                     local_hosts=frozenset({"testclient"}), page_hosts=frozenset({"testserver"}),
                     live_token=fake_token, advertise=False)
    with TestClient(app) as c:
        c.post("/api/memory/facts", json={"text": "The user's locker is 42."})
        with c.websocket_connect("/ws/phone") as ws:
            ws.send_json(hello(pairing.token))
            ws.receive_json()
            ws.send_json({"v": 1, "type": "live.start", "id": "s1", "payload": {}})
            ready = ws.receive_json()
    assert ready["type"] == "live.ready" and ready["id"] == "s1"
    assert ready["payload"]["token"] == "auth_tokens/abc"
    assert "locker is 42" in ready["payload"]["config"]["system"]
    assert "gem-key" not in json.dumps(ready)  # the API key never goes to the phone


def test_live_start_without_gemini_key(settings, budget, pairing, keys):
    with client_for(settings, budget, pairing, keys) as c:
        with c.websocket_connect("/ws/phone") as ws:
            ws.send_json(hello(pairing.token))
            ws.receive_json()
            ws.send_json({"v": 1, "type": "live.start", "id": "s2", "payload": {}})
            reply = ws.receive_json()
    assert reply["type"] == "error" and reply["payload"]["code"] == "no_key"


def test_choosing_a_voice_restarts_running_sessions(settings, budget, pairing, keys):
    with client_for(settings, budget, pairing, keys) as c:
        voices = c.get("/api/voices").json()["voices"]
        assert any(v["name"] == "Charon" and v["current"] for v in voices)  # male default
        assert {"male", "female"} == {v["type"] for v in voices}
        with c.websocket_connect("/ws/pc") as pc:
            pc.send_json(hello(None, role="pc"))
            pc.receive_json()
            r = c.put("/api/voice", json={"voice": "Puck"})
            assert r.status_code == 200 and settings["live_voice"] == "Puck"
            assert pc.receive_json()["type"] == "live.restart"
        assert c.put("/api/voice", json={"voice": "Nobody"}).status_code == 404


def test_phone_changes_voice(settings, budget, pairing, keys):
    with client_for(settings, budget, pairing, keys) as c:
        with c.websocket_connect("/ws/phone") as ws:
            ws.send_json(hello(pairing.token))
            ws.receive_json()
            ws.send_json({"v": 1, "type": "voices.list", "id": "v1", "payload": {}})
            assert ws.receive_json()["type"] == "voices"
            ws.send_json({"v": 1, "type": "settings.update", "id": "v2", "payload": {"settings": {"live_voice": "Orus"}}})
            assert ws.receive_json()["type"] == "ack"
            assert ws.receive_json()["type"] == "live.restart"  # the phone's own session restarts too
            ws.send_json({"v": 1, "type": "settings.update", "id": "v3", "payload": {"settings": {"live_voice": "Nobody"}}})
            assert ws.receive_json()["payload"]["code"] == "bad_voice"
    assert settings["live_voice"] == "Orus"


def test_memories_learned_mid_session_reach_the_voice_session(settings, budget, pairing, keys):
    async def fake_token(api_key):
        return {"token": "auth_tokens/abc", "url": "wss://example/constrained", "start_by": 1, "expires_at": 2}

    keys.set("gemini", "gem-key")
    app = create_app(settings=settings, keys=keys, budget=budget, pairing=pairing, providers={},
                     local_hosts=frozenset({"testclient"}), page_hosts=frozenset({"testserver"}),
                     live_token=fake_token, advertise=False)
    with TestClient(app) as c:
        with c.websocket_connect("/ws/phone") as ws:
            ws.send_json(hello(pairing.token))
            ws.receive_json()
            ws.send_json({"v": 1, "type": "live.start", "id": "s1", "payload": {}})
            assert ws.receive_json()["type"] == "live.ready"
            c.post("/api/memory/facts", json={"text": "The user's sister lands at 6 pm."})  # from another device
            ws.send_json({"v": 1, "type": "tool.call", "id": "t1", "payload": {"name": "clock", "args": {}}})
            first = ws.receive_json()["payload"]["result"]
            ws.send_json({"v": 1, "type": "tool.call", "id": "t2", "payload": {"name": "clock", "args": {}}})
            second = ws.receive_json()["payload"]["result"]
    assert first["memory_update"] == ["The user's sister lands at 6 pm."] and "time" in first
    assert "memory_update" not in second  # told once


def test_a_memory_can_be_corrected_from_the_phone_and_mission_control(settings, budget, pairing, keys):
    with client_for(settings, budget, pairing, keys) as c:
        fid = c.post("/api/memory/facts", json={"text": "Mom's birthday is 12 March."}).json()["facts"][0]["id"]
        with c.websocket_connect("/ws/phone") as ws:
            ws.send_json(hello(pairing.token))
            ws.receive_json()
            ws.send_json({"v": 1, "type": "memory.update", "id": "u1", "payload": {"id": fid, "text": "Mom's birthday is 21 March."}})
            assert ws.receive_json()["payload"] == {"ok": True}
            ws.send_json({"v": 1, "type": "memory.update", "id": "u2", "payload": {"id": 999, "text": "x"}})
            assert ws.receive_json()["payload"] == {"ok": False}
        assert c.get("/api/memory").json()["facts"][0]["text"] == "Mom's birthday is 21 March."
        facts = c.put(f"/api/memory/facts/{fid}", json={"text": "Mom's birthday is 22 March."}).json()["facts"]
        assert facts[0]["text"] == "Mom's birthday is 22 March."
        assert c.put("/api/memory/facts/999", json={"text": "x"}).status_code == 404
