# Ronie message protocol (v1)

All three parts (Android app, brain server, PC companion) talk over WebSocket using JSON messages with the same envelope. The schema is in `schema/envelope.schema.json`.

```json
{ "v": 1, "type": "llm.request", "id": "a1b2c3", "ts": 1793318944.12, "payload": { } }
```

- `v` — protocol version, always `1` for now.
- `type` — message type (table below).
- `id` — sender-chosen id. Replies reuse the request's `id` so the sender can match them.
- `ts` — Unix time in seconds.
- `payload` — type-specific object.

## Connecting

| Endpoint | Who connects | Auth |
|---|---|---|
| `/ws/phone` | Android app | `hello` with the pairing token (from the `/pair` QR code) |
| `/ws/pc` | PC companion | Same machine: no token needed. Otherwise the pairing token. |
| `/ws/dashboard` | Mission control page | Same machine only |

The first message on `/ws/phone` and `/ws/pc` must be `hello`. The server replies `welcome` or closes the socket with code 4401.

## Message types

| Type | Direction | Payload |
|---|---|---|
| `hello` | client → brain | `{ "role": "phone" \| "pc", "token": "...", "device": "Pixel 9" }` |
| `welcome` | brain → client | `{ "server": "ronie-brain", "version": "0.1.0", "providers": [ ... ] }` |
| `llm.request` | phone/pc → brain | `{ "task": "general" \| "coding" \| "interview" \| "game" \| "vision" \| "translate", "prompt": "...", "system": "...", "history": [ {"role":"user","content":"..."} ], "max_tokens": 1024, "premium": null }` |
| `llm.response` | brain → requester | `{ "text": "...", "provider": "gemini", "model": "...", "cost_usd": 0.0, "ms": 812, "fallbacks": [] }` |
| `settings.update` | phone → brain | `{ "settings": { ...partial settings... } }` |
| `keys.set` | phone → brain | `{ "provider": "claude", "key": "..." }` — the key is never sent back |
| `keys.delete` | phone → brain | `{ "provider": "claude" }` |
| `ack` | brain → client | `{ "ok": true }` |
| `event` | any → brain → dashboards | `{ "stage": "heard" \| "language" \| "vision" \| "tool" \| "model" \| "answer" \| "error", "text": "...", "ms": 120, "extra": { } }` |
| `relay` | any → brain → role | `{ "to": "pc" \| "phone", "message": { ...envelope... } }` |
| `live.start` | phone/pc → brain | `{}` — ask to open a Gemini Live voice session |
| `live.ready` | brain → caller | `{ "token": "auth_tokens/…", "url": "wss://…Constrained", "start_by": 1793…, "expires_at": 1793…, "config": { "models": [...], "voice": "", "system": "…persona + shared memory…", "functions": [...], "google_search": false } }` — connect to `url?access_token=<token>` within 2 minutes; the token opens one session (up to 30 minutes). Ask again for every new session so the memory is fresh. The Gemini API key never leaves the brain. |
| `tool.call` | phone/pc → brain | `{ "name": "ask_brain" \| "remember" \| "recall" \| "forget" \| "research_web", "args": { } }` |
| `tool.result` | brain → caller | `{ "result": { ... } }` — reuses the call's `id` |
| `screen.capture.request` | phone → (relay) → pc | `{ "monitor": 1 }` |
| `screen.capture.result` | pc → (relay) → phone | `{ "png_base64": "..." }` — reuses the request's `id` |
| `interview.segment` | phone → brain | `{ "audio_b64": "<16 kHz mono PCM>", "level_db": -14.2, "calibrate": false, "speaker": "candidate" \| "" }` — one utterance from the glasses' mic in interview cues. `calibrate`: the warm-up line ("I'm ready") that sets the user's loudness. `speaker: "candidate"` while the laptop is hearing the interviewer (the mic is then only the user). While an answer is being fed: `"expected": "<the phrase>", "answer": "<whole answer>"` → the reply adds `"check": {"verdict": "ok" \| "wrong" \| "incomplete", "correction": "<few words to whisper>"}`. |
| `interview.answer` | brain → phone | `{ "heard": "...", "speaker": "interviewer" \| "candidate" \| "unclear", "interviewer_question": true, "question": "...", "answer": "• point
• point", "phrases": ["<cue point, 3–8 words>", "..."], "points": "a · b · c", "source": "laptop" }` — reply to a segment (same `id`), or a push with `source: "laptop"` for something heard on the call. |
| `interview.interviewer` | brain → phone | `{ "talking": true, "transcribing": false }` — the interviewer (heard on the laptop) started or stopped talking; on stop, `transcribing` says whether an `interview.answer` for it will follow. The phone stays silent while they talk. |
| `interview.listen` | phone → brain | `{ "on": true }` — record the laptop's own audio output (the call) as the interviewer; stops when `on` is false or no phone is connected. |
| `interview.listening` | brain → phone | `{ "on": true, "device": "Speakers (…)" }` — or an `error` with code `laptop_audio`. |
| `error` | brain → client | `{ "code": "all_providers_failed", "message": "..." }` |

New message types are added here first, then implemented in each part.
