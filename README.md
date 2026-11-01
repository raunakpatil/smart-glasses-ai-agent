<p align="center"><img src="docs/media/banner.gif" alt="Ronie: the actually smart AI agent for your glasses. Works with Gemini, Claude, ChatGPT and DeepSeek." width="100%"></p>

<p align="center">
  <a href="https://raunakpatil.com/"><img src="https://img.shields.io/badge/Website-7f77dd?style=for-the-badge&logo=globe&logoColor=white" alt="Website"></a>
  <a href="https://www.linkedin.com/in/raunakpatil/"><img src="https://img.shields.io/badge/LinkedIn-0A66C2?style=for-the-badge&logo=linkedin&logoColor=white" alt="LinkedIn"></a>
  <a href="https://www.instagram.com/raunakpatil/"><img src="https://img.shields.io/badge/Instagram-E4405F?style=for-the-badge&logo=instagram&logoColor=white" alt="Instagram"></a>
  <a href="https://www.youtube.com/@raunakpatil15"><img src="https://img.shields.io/badge/YouTube-FF0000?style=for-the-badge&logo=youtube&logoColor=white" alt="YouTube"></a>
  <a href="https://github.com/raunakpatil"><img src="https://img.shields.io/badge/GitHub-181717?style=for-the-badge&logo=github&logoColor=white" alt="GitHub"></a>
  <a href="mailto:raunakpatil15@gmail.com"><img src="https://img.shields.io/badge/Email-EA4335?style=for-the-badge&logo=gmail&logoColor=white" alt="Email"></a>
</p>

<h1 align="center">Smart Glasses AI Agent</h1>

<p align="center"><b>Your Ray-Ban Metas. Finally smart.</b></p>

<p align="center">An open-source, hands-free AI agent for Ray-Ban Meta glasses.<br>Say "Ronie" and just talk. It sees what you see, browses the web for live answers,<br>runs your TV and laptop, and remembers your life. Free to run. No subscription.</p>

<p align="center"><sub>Not affiliated with, endorsed by, or sponsored by Meta Platforms or Ray-Ban (EssilorLuxottica). "Ray-Ban Meta" only names the glasses this works with.</sub></p>

<br>

https://github.com/user-attachments/assets/90b6202b-6237-4d71-889a-12c43a5b61bb

<p align="center"><sub>The launch film · 1:38 · sound on</sub></p>

<br>

## Things you didn't know glasses could do.

**"Explain how black holes form."**<br>
You're on the sofa. The answer starts in your ear, a video starts on the TV, and an article opens on the laptop in front of you. It took a photo to see which screen you're facing.

**"Turn on the PlayStation."**<br>
The PS5 wakes, the TV switches to it, and the volume lands at exactly 15. You never touched a remote.

**"What's my best move?"**<br>
You glance at a chess board. A chess engine reads it and says the move. At a poker table it gives you the odds.

**"Switch to interview mode."**<br>
In a real interview, it listens through your laptop and whispers short answer points while you talk. Practise first with a mock interview.

**"What's this dish, and is it worth it?"**<br>
You look at a menu in another language. It translates it, says what to order, and roughly how many calories you're about to eat.

**"Which car is that?"**<br>
Make, model, roughly what it costs. Just from a look.

**"Remember, I parked on level 3, pillar B12."**<br>
Three hours later: "Where did I park?" It knows. It remembers your people, plans and preferences too, across every model you use.

**"Who won the race today?"**<br>
Ronie has the web at its fingertips. It searches, opens and reads the top pages itself, and gives you one clean sentence, not ten blue links. Scores, news, prices, opening hours: anything that changed today.

**"Stop."**<br>
It stops talking instantly and listens. Interrupt it any time, like a person.

**Hindi, English, and the mix of both.**<br>
Talk the way you talk. More languages are on the way.

<br>

## Compared with the built-in assistant.

| | This project | Built-in Meta AI |
|---|:---:|:---:|
| Controls your Android TV, apps and HDMI devices like a PS5 | ✅ Yes | ❌ No |
| Opens videos and pages on your laptop, across both displays | ✅ Yes | ❌ No |
| Shows things on whichever screen you're facing | ✅ Yes | ❌ No |
| Chess moves and poker odds from what you see | ✅ Yes | ❌ No |
| Quiet answer points in a real interview, and mock interviews | ✅ Yes | ❌ No |
| Long-term memory you can see, edit and delete | ✅ Yes | ❌ No |
| Pick your model: Gemini, Claude, ChatGPT, DeepSeek and more | ✅ Yes | ❌ No |
| Open source, on your own server, free APIs | ✅ Yes | ❌ No |

<br>

## How it works.

<p align="center"><img src="docs/media/how-it-works.gif" alt="How it works: the glasses talk to the Android app over Bluetooth; the app streams voice to the AI model and talks to the brain server over WebSocket; the brain runs tools and a model router and reaches the laptop agent; the phone controls the TV over home Wi-Fi." width="100%"></p>

Six pieces, one conversation. Here's the path a single sentence takes.

**1 · The glasses.** Ray-Ban Meta Gen 2, unchanged. Their microphones, speakers and camera reach your phone over Bluetooth. Nothing is installed on them.

**2 · The Android app.** It hears the wake word "Ronie" on the phone itself, then opens a live voice session and streams your voice. It takes photos with the glasses camera through the Meta Wearables SDK, knows when you're home from your Wi-Fi, and holds the TV remote.

**3 · The AI model.** Your voice goes to Gemini Live, which listens, speaks back and can be interrupted mid-sentence. When a question needs real thinking, the brain can hand it to Claude, ChatGPT, DeepSeek, Groq, OpenRouter or a local Ollama model.

**4 · The brain.** A small Python server (FastAPI) that you run. It holds the instructions, the tools (web search, vision, chess, memory, show-on-screen), your long-term memory and the model router, which is free by default. The app talks to it over an encrypted WebSocket.

**5 · The laptop agent.** A tiny Windows program. When the brain says "show this," it opens the page or video, lays windows out across your displays, controls media and volume, and in interview mode hears the call audio.

**6 · Your TV.** Any Android TV or Google TV. The phone talks to it directly over your home Wi-Fi: it opens apps, plays shows, sets the exact volume, and switches HDMI inputs to wake a PS5.

<br>

## It gets to know you.

<p align="center"><img src="docs/media/brain.gif" alt="The brain: what you say over days and months becomes key facts in memory and a growing profile of you, stored on your server and never shared." width="100%"></p>

One memory, shared by every model and every conversation.

- **It remembers what matters.** Say "remember…" and it's saved. It also picks up the details a friend would: people, places, plans, tastes.
- **Nothing gets lost.** Old conversations fold into a running summary. Facts that change are updated; facts that stop being true are dropped.
- **A picture of you takes shape.** Weeks in, it knows your routines. A year in, it knows you.
- **Every model starts from it.** Switch from Gemini to Claude and you never repeat yourself.
- **You're in control.** See, edit or delete any memory from the app.

<br>

## Private by design.

<p align="center"><img src="docs/media/security.gif" alt="Security and privacy: traffic is encrypted; only paired devices get in; memory, keys, settings and the admin page stay locked on your own server; only the question goes to the AI provider you choose." width="100%"></p>

There's no company in the middle. You run the brain, so your data lives where you put it.

- **Your server.** A free Oracle Cloud machine in *your* account, or your own computer at home.
- **Encrypted.** HTTPS everywhere, with a free Let's Encrypt certificate.
- **Paired devices only.** Each device gets a private token. Everything else is refused.
- **Your data stays put.** Memory, profile, settings and API keys live on your server. Keys never go back to the app.
- **No public admin page.** Mission control is reached only through a private SSH tunnel.
- **Only the question leaves.** It goes, with the memory it needs, to the AI provider you picked, on your own key.
- **On-device where possible.** The wake word runs on the phone. The "which screen" photo is taken only at home, used once, never stored.
- **Open source.** No analytics, no ads, no tracking. Read every line.

<br>

## Get started.

You need Ray-Ban Meta glasses, an Android 14+ phone, and a free Gemini API key. A Windows laptop and an Android / Google TV are optional.

1. Get free API keys: Gemini (required), Groq and OpenRouter (recommended).
2. Run the brain on your laptop, or on a free cloud server so it works away from home.
3. Build and install the Android app, then pair it with the brain.
4. Optional: pair your TV in the app, and add your Windows laptop with one PowerShell command:
   `irm https://raw.githubusercontent.com/raunakpatil/smart-glasses-ai-agent/main/install-agent.ps1 | iex`

The full guide is in **[docs/SETUP.md](docs/SETUP.md)**.

**Cost: nothing.** Gemini, Groq and OpenRouter free tiers, plus Oracle Cloud's Always Free server if you want it online. Claude, ChatGPT and DeepSeek are optional and only used if you add a key.

<br>

## Coming next.

<p align="center"><img src="docs/media/next.gif" alt="Coming next, in development: it makes calls for you, says awkward things for you, books the trip, names the song, watches for your bus, and remembers where you left your keys." width="100%"></p>

<br>

## Contributing

Issues and pull requests are welcome. Run the brain tests with `pytest` in `brain/`.

Fonts: Inter Tight and JetBrains Mono, under the SIL Open Font License (see `android-app/fonts-license/`). MIT licensed, see [LICENSE](LICENSE).
