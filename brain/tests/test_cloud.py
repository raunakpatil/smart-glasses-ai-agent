import json
import os

import pytest
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from conftest import FakeKeys, FakeProvider
from ronie_brain.app import create_app
from ronie_brain.config import Keys
from ronie_brain.pairing import Pairing


@pytest.fixture
def client(settings, budget, pairing):
    app = create_app(settings=settings, keys=FakeKeys(), budget=budget, pairing=pairing,
                     providers={"groq": FakeProvider("groq")}, local_hosts=frozenset({"testclient"}),
                     page_hosts=frozenset({"testserver"}), advertise=False)
    with TestClient(app) as c:
        yield c


def test_requests_through_the_https_proxy_are_not_local(client):
    """On a cloud server Caddy connects from 127.0.0.1 too, but says who the real visitor is."""
    assert client.get("/api/memory").status_code == 200
    assert client.get("/api/memory", headers={"X-Forwarded-For": "203.0.113.9"}).status_code == 403


def test_pc_from_the_internet_needs_the_pairing_token(client, pairing):
    with pytest.raises(WebSocketDisconnect):
        with client.websocket_connect("/ws/pc", headers={"X-Forwarded-For": "203.0.113.9"}) as ws:
            ws.send_json({"v": 1, "type": "hello", "payload": {"role": "pc"}})
            ws.receive_json()
    with client.websocket_connect("/ws/pc", headers={"X-Forwarded-For": "203.0.113.9"}) as ws:
        ws.send_json({"v": 1, "type": "hello", "payload": {"role": "pc", "token": pairing.token}})
        assert ws.receive_json()["type"] == "welcome"


def test_keys_in_a_private_file(home, monkeypatch):
    monkeypatch.setenv("RONIE_KEY_STORE", "file")
    keys = Keys()
    keys.set("gemini", "abc")
    assert Keys().get("gemini") == "abc"
    assert json.loads((home / "keys.json").read_text()) == {"gemini": "abc"}
    keys.delete("gemini")
    assert Keys().get("gemini") is None


def test_pairing_code_with_public_url(home):
    data = json.loads(Pairing(home / "p.json").qr_payload("10.0.0.5", 8790, "wss://1-2-3-4.sslip.io/"))
    assert data["url"] == "wss://1-2-3-4.sslip.io" and data["host"] == "10.0.0.5"


def test_laptop_actions_go_through_the_agent(settings, budget, pairing):
    """Brain on a server: laptop tools are sent to the laptop agent; without it, Ronie says the laptop is off."""
    import threading

    settings.update({"laptop_mode": "agent", "laptop_browser": "brave"})
    app = create_app(settings=settings, keys=FakeKeys(), budget=budget, pairing=pairing,
                     providers={"groq": FakeProvider("groq")}, local_hosts=frozenset({"testclient"}),
                     page_hosts=frozenset({"testserver"}), advertise=False)
    run = {"tool": "laptop", "args": {"target": "youtube", "kind": "website"}}
    with TestClient(app) as c:
        off = c.post("/api/devices/run", json=run).json()
        assert "laptop is off" in off["error"]
        with c.websocket_connect("/ws/home") as agent:
            agent.send_json({"v": 1, "type": "hello", "payload": {"role": "home", "token": pairing.token}})
            assert agent.receive_json()["type"] == "welcome"
            box = {}
            t = threading.Thread(target=lambda: box.update(r=c.post("/api/devices/run", json=run).json()))
            t.start()
            ask = agent.receive_json()
            assert ask["type"] == "device.run"
            assert ask["payload"] == {"tool": "laptop", "args": {"target": "youtube", "kind": "website", "browser": "brave"}}
            agent.send_json({"v": 1, "type": "device.result", "id": ask["id"], "payload": {"result": {"done": "Opening youtube"}}})
            t.join(10)
    assert box["r"] == {"done": "Opening youtube"}


def _phone(client, pairing):
    ws = client.websocket_connect("/ws/phone", headers={"X-Forwarded-For": "203.0.113.9"})
    s = ws.__enter__()
    s.send_json({"v": 1, "type": "hello", "payload": {"role": "phone", "token": pairing.token}})
    s.receive_json()
    return ws, s


def test_phone_lists_and_forgets_memories_over_the_socket(client, pairing):
    """The REST memory API is local-only, so the app's Memory tab asks over its socket."""
    assert client.post("/api/memory/facts", json={"text": "I like dark roast coffee"}).status_code == 200
    ctx, ws = _phone(client, pairing)
    try:
        ws.send_json({"v": 1, "type": "memory.list", "id": "m", "payload": {}})
        r = ws.receive_json()
        assert r["type"] == "memory" and [f["text"] for f in r["payload"]["facts"]] == ["I like dark roast coffee"]
        ws.send_json({"v": 1, "type": "memory.forget", "id": "f", "payload": {"id": r["payload"]["facts"][0]["id"]}})
        assert ws.receive_json()["payload"]["ok"] is True
        ws.send_json({"v": 1, "type": "memory.list", "id": "m2", "payload": {}})
        assert ws.receive_json()["payload"]["facts"] == []
    finally:
        ctx.__exit__(None, None, None)


def test_phone_sees_which_keys_and_models_are_set_without_the_keys(client, pairing):
    ctx, ws = _phone(client, pairing)
    try:
        ws.send_json({"v": 1, "type": "status.get", "id": "s", "payload": {}})
        r = ws.receive_json()
    finally:
        ctx.__exit__(None, None, None)
    p = r["payload"]
    assert r["type"] == "status" and p["live_model"] and p["live_voice"]
    assert p["providers"]["groq"]["available"] is True and "tavily" in p["service_keys"]
    assert "key" not in json.dumps(p).replace("has_key", "").replace("service_keys", "")
