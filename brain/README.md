# Ronie brain server

FastAPI server that runs on the laptop. It routes AI requests across free and paid providers, pairs with the phone, relays messages to the PC companion, and serves mission control.

## Setup

```bash
python -m venv .venv
source .venv/Scripts/activate      # Git Bash on Windows
pip install -e ".[dev,claude]"     # drop ",claude" if you won't use Claude
```

## Commands

```bash
ronie-brain                  # start the server on port 8790
ronie-brain set-key gemini   # save a key in Windows Credential Manager (input hidden)
ronie-brain delete-key gemini
ronie-brain status           # which providers are ready
pytest                       # run the tests
```

Keys can also come from environment variables: `GEMINI_API_KEY`, `GROQ_API_KEY`, `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `DEEPSEEK_API_KEY`. The phone app sends keys with `keys.set` and they land in the same keyring.

## Pages (this computer only)

- `/` — mission control: live feed of what Ronie heard, which provider it tried, the answer, time and cost; a test box; provider status with **add, replace and remove API keys**; budget status. Key changes only work from this computer at `http://127.0.0.1:8790/`, and requests from other websites are refused. Saved keys are never shown again.
- `/pair` — QR code and pairing code for the companion app.

## Providers and routing

| Provider | Paid | How it's called |
|---|---|---|
| gemini | no (free tier) | Gemini REST API |
| groq | no (free tier) | OpenAI-compatible API |
| ollama | no (local) | OpenAI-compatible API at `ollama_url` |
| claude | yes | Anthropic Python SDK |
| openai | yes | OpenAI-compatible API (set a model first) |
| deepseek | yes | OpenAI-compatible API |

Each task (`general`, `vision`, `coding`, `interview`, `game`, `translate`) has a provider order in `routes`. Paid providers are tried only when premium mode is on (setting or per request), their key is set, and their monthly budget isn't used up. A failure or a declined request moves on to the next provider.

Settings are in `~/.ronie/settings.json`; spend in `~/.ronie/spend.json`; the pairing token in `~/.ronie/pairing.json`. Set `RONIE_HOME` to use another folder.

## Shared memory

Ronie's memory lives in the brain (`~/.ronie/memory.db`), not in any model, so switching models, providers or voice sessions never loses context:

- **Facts:** things the user asked Ronie to remember, preferences, people, places and plans.
- **Summary:** a running summary of older conversations.
- **Recent conversation:** the latest turns, word for word.

Every text request and every new Gemini Live session gets the same memory block (plus the date and time in the user's `timezone`, never the server's clock). When the recent conversation passes `memory_compact_tokens`, a background job folds the oldest turns into the summary and saves new facts, keeping the last `memory_keep_turns` turns word for word. Mission control shows the facts and summary, and lets you add, edit, forget or compact; the app's Memory tab can add, edit and forget too.

- **Which facts go in:** the ones that fit the question (or, as a voice session starts, what the user talked about lately), then anything saved in the last `memory_fresh_days` days, then the most important, up to `memory_max_facts`.
- **Time labels:** recent turns from earlier, or after a pause of `memory_turn_gap_minutes`, carry when they were said, so yesterday's "I'm at the airport" isn't taken as true now.
- **Topics and expiry:** a fact can carry a topic ("parking spot"): a new fact with the same topic replaces the old one at once. Plans, reminders and where something is right now carry the last date they matter, and are dropped after it.
- **Search:** recall and forget match words in any script, Hindi in Devanagari included. With a Gemini key they also match by meaning, using free Gemini embeddings (`memory_embed_model`), so a Hinglish question finds an English memory. Without a key, or when embeddings fail, search falls back to words.
- **Forget:** when more than one memory fits, or the match is only a guess, nothing is deleted: Ronie asks which one.
- **Mid-session updates:** facts learned while a voice session is running (the background fact check, another device) ride along with that session's next tool result as `memory_update`.

## Internet

`research_web` searches the web (Tavily when its key is saved, otherwise DuckDuckGo), reads the top pages and writes a spoken analysis with sources. Gemini Live's built-in Google Search isn't in the free tier, so it's off (`live_google_search`).

## Layout

```
ronie_brain/
├─ app.py          HTTP + WebSocket endpoints
├─ cli.py          ronie-brain command
├─ config.py       settings and keys
├─ hub.py          connected clients, message envelopes
├─ pairing.py      pairing token and QR code
├─ discovery.py    mDNS advertising (_ronie._tcp)
├─ memory.py       shared memory and compaction
├─ web.py          web search and page reading
├─ tools.py        tools the voice model calls (memory, web, ask_brain)
├─ models/         router, providers, budget
└─ dashboard/      mission control and pairing pages
```
