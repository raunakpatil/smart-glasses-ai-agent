<p align="center"><img src="docs/media/banner.gif" alt="Ronie: the actually smart AI agent for your glasses. Black wayfarer smart glasses floating on black." width="100%"></p>

# Smart Glasses AI Agent

**Make your Ray-Ban Metas do what Meta AI can't.**

An open-source, hands-free AI agent for Ray-Ban Meta smart glasses. Say "Ronie" and just talk. It sees what you see, plays things on your TV, opens them on your laptop, and answers live. It runs on free AI APIs, so there's no subscription.

> Not affiliated with, endorsed by, or sponsored by Meta Platforms or Ray-Ban (EssilorLuxottica). "Ray-Ban Meta" is used only to say which glasses this works with.

**▶ The launch film (1:38, turn the sound on):**

https://github.com/user-attachments/assets/90b6202b-6237-4d71-889a-12c43a5b61bb

## What it does

| Say | What happens |
|---|---|
| "Ronie, play Stranger Things on the TV" | Opens the show on your Android / Google TV and starts it |
| "Play some lofi on my laptop" | Plays it on YouTube in your browser on the laptop |
| "Explain how black holes form" (at home) | Answers in your ear, and puts a video plus an article on the screen you're facing |
| "What am I looking at?" | Takes a photo with the glasses and tells you |
| "Turn on the PlayStation" | Wakes the PS5 and switches the TV to it |
| "Set the TV volume to 15" | Sets that exact volume |
| "What's the weather today?" / "Latest F1 result?" | Searches the web and gives you the short answer |
| "Remember, my locker number is 42" | Remembers it for later, across every session |
| "What's my best move?" (chess board in view) | Reads the board and suggests the move |
| "Switch to interview mode" | Gives quiet answer points in your ear during a real interview |
| Speak your own language | Understands several languages and accents: Hindi and English today, with more on the way |
| "Stop" | Stops talking at once and listens |

### Compared with the built-in assistant

| | This project | Built-in Meta AI |
|---|---|---|
| Control your Android TV, apps and HDMI devices (PS5) | Yes | No |
| Open videos and pages on your laptop, across both displays | Yes | No |
| Shows things on the screen you're facing, at home | Yes | No |
| Chess-engine moves and poker odds from what you see | Yes | No |
| Quiet answer points during a real interview, and mock interviews | Yes | No |
| Open source, runs on your own server, free APIs | Yes | No |

## How it works

<p align="center"><img src="docs/media/how-it-works.gif" alt="How it works: the glasses talk to the Android app over Bluetooth; the app streams voice to the AI model and talks to the brain server over WebSocket; the brain runs tools and a model router (Gemini, Claude, ChatGPT, DeepSeek, Groq, OpenRouter, Ollama) and reaches the laptop agent; the phone controls the TV over home Wi-Fi." width="100%"></p>

- **Android app** (Kotlin, Jetpack Compose): wake word, the voice session with Gemini Live, the glasses camera (Meta Wearables SDK), and the TV remote.
- **Brain server** (Python, FastAPI): instructions, tools, shared memory, web research and pairing, plus a model router: free by default (Gemini, Groq, OpenRouter, local Ollama), with Claude, ChatGPT and DeepSeek when you add their keys. Real-time voice runs on Gemini Live.
- **Laptop agent** (Windows): opens sites and apps, controls media and volume, lays out windows across displays, and hears call audio for interview mode.

## The brain: it gets to know you

<p align="center"><img src="docs/media/brain.gif" alt="The brain: what you say over days and months becomes key facts in memory and a growing profile of you (people, places, routines, preferences, health, work, interests), stored on your server and never shared." width="100%"></p>

Ronie has a long-term memory that every model and every conversation shares, so it gets better the more you use it.

- **It remembers what matters.** Say "remember…" and it saves it. It also picks up key details on its own as you talk: people, places, plans, preferences, the things you'd expect a friend to remember.
- **Nothing gets lost.** Older conversations are folded into a running summary. Facts that change are updated, and facts that stop being true are dropped.
- **A profile of you takes shape.** Over weeks and months it builds a picture of your life: the people you mention, your routines, your tastes, your work and interests. A year in, it knows you inside out.
- **Every answer uses it.** Gemini, Claude, ChatGPT or any other model you switch to starts with the same memory, so you never have to repeat yourself.
- **You stay in control.** See, edit or delete any memory from the app's Memory tab or the brain's mission control page.

## Get started

You need Ray-Ban Meta glasses, an Android 14+ phone, and a free Gemini API key. A Windows laptop and an Android / Google TV are optional.

The full step-by-step guide is in **[docs/SETUP.md](docs/SETUP.md)**. In short:

1. Get free API keys: Gemini (required), Groq and OpenRouter (recommended).
2. Run the brain server on your laptop, or on a free cloud server so it works away from home.
3. Build and install the Android app, then pair it with the brain.
4. Optional: pair your TV in the app, and run the laptop agent.

## Cost

Everything runs on free tiers: Gemini, Groq and OpenRouter free models, plus Oracle Cloud's Always Free server if you want it online. Paid models (Claude, OpenAI, DeepSeek) are optional and only used if you add a key and turn on Premium.

## Security and privacy

<p align="center"><img src="docs/media/security.gif" alt="Security and privacy: voice leaves the phone encrypted over HTTPS; only paired devices with a private token get in and everyone else is turned away; memory, API keys, settings and the admin page stay locked on your own server; only the question goes to the AI provider you choose; or run the server on your own computer." width="100%"></p>

There is no Ronie company in the middle. You run the brain yourself, so your data lives where you put it.

- **Your own server.** The brain runs on a free Oracle Cloud machine in *your* account, or on your own computer at home if you'd rather skip the cloud entirely. Nobody else hosts or sees it.
- **Encrypted in transit.** The phone and laptop talk to the brain over HTTPS (TLS), with a free certificate from Let's Encrypt.
- **Only your devices get in.** Each device is paired once with a private token. Connections without it are refused.
- **Your data stays home.** Memory, your profile, settings and API keys are stored on your server. Keys are kept in a file only your user can read, and they're never sent back to the app.
- **The admin page isn't public.** Mission control is not exposed to the internet; you reach it through a private SSH tunnel.
- **Only the question leaves.** To answer you, the brain sends the question (and the memory it needs) to the AI provider you chose, such as Google Gemini, using your own key and account. Their privacy terms apply to that request; nothing else goes anywhere.
- **On-device where it can be.** The wake word "Hey Ronie" is detected on the phone. The "which screen are you facing" photo is taken only at home, used once and not stored.
- **Open source.** Every line is in this repo for you to read, and nothing you store ever is: no analytics, no ads, no tracking.

## Project layout

| Folder | What's in it |
|---|---|
| `android-app/` | The phone app |
| `brain/` | The brain server, laptop agent and tests |
| `pc-companion/` | Optional desktop voice client |
| `shared/` | The message protocol between the parts |
| `docs/` | Setup guide and product plan |

## Contributing

Issues and pull requests are welcome. Run the brain tests with `pytest` in `brain/`.

Fonts: Inter Tight and JetBrains Mono, under the SIL Open Font License (see `android-app/fonts-license/`).

## License

MIT, see [LICENSE](LICENSE).
