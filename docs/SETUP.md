# Set up your own smart glasses AI agent

This guide sets up everything with your own free accounts: the brain server, the phone app, and optionally your TV and laptop. It takes about an hour the first time.

## What you need

- Ray-Ban Meta glasses, paired with your phone in the Meta AI app
- An Android 14+ phone
- A computer to build the app on (Windows, macOS or Linux) with [Android Studio](https://developer.android.com/studio)
- Python 3.11 or newer
- Optional: an Android TV or Google TV on your Wi-Fi, and a Windows laptop for laptop control

## 1. Get free API keys

| Key | Where | Needed? |
|---|---|---|
| Gemini | [Google AI Studio → API keys](https://aistudio.google.com/apikey) | Yes: real-time voice and vision |
| Groq | [console.groq.com/keys](https://console.groq.com/keys) | Recommended: fast answers, and speech-to-text for interview mode |
| OpenRouter | [openrouter.ai/keys](https://openrouter.ai/keys) | Recommended: more free models as a fallback |
| Tavily | [tavily.com](https://tavily.com) | Optional: better web search |

Paid keys (Claude, OpenAI, DeepSeek) are optional. They're only used when you turn on Premium in the app.

## 2. Run the brain server

Pick one option.

- **A. On your laptop:** quickest to start. It only works while the laptop is on and the phone is on the same Wi-Fi.
- **B. On a free cloud server:** works anywhere, any time. It's what you want for everyday use.

### Option A: on your laptop

```bash
git clone https://github.com/<you>/smart-glasses-ai-agent.git
cd smart-glasses-ai-agent/brain
python -m venv .venv
source .venv/Scripts/activate        # Windows Git Bash; on macOS/Linux: source .venv/bin/activate
pip install -e ".[dev]"
ronie-brain set-key gemini           # paste the key when asked (it's hidden); repeat for groq, openrouter
ronie-brain                          # starts on port 8790
```

Open http://127.0.0.1:8790/ for mission control, a live view of what the agent hears and does. Open http://127.0.0.1:8790/pair for the pairing code you'll paste into the phone app.

### Option B: on a free cloud server (Oracle Cloud Always Free)

Oracle's Always Free tier includes small virtual machines that don't expire. Create your own account and server; don't share one with anyone.

1. **Create an account** at [oracle.com/cloud/free](https://www.oracle.com/cloud/free/). It asks for a card to verify you, but Always Free resources aren't charged. Don't upgrade the account to Pay As You Go unless you mean to.
2. **Create a VM:** Compute → Instances → Create.
   - Image: Ubuntu 24.04. Shape: `VM.Standard.E2.1.Micro`, or `VM.Standard.A1.Flex` if it has capacity. Both are Always Free.
   - Add your SSH public key, or let Oracle make one and save the private key.
   - Note the VM's public IP address. Below it's written as `1.2.3.4`.
3. **Open ports 80 and 443:**
   - In the Oracle console, go to Networking → your VCN → Security Lists → Default → Add Ingress Rules. Add source `0.0.0.0/0`, TCP, destination ports `80` and `443`.
   - Then on the VM:
     ```bash
     sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80 -j ACCEPT
     sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 443 -j ACCEPT
     sudo netfilter-persistent save
     ```
4. **Add swap.** The 1 GB machine needs it:
   ```bash
   sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
   echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
   ```
5. **Install the brain:**
   ```bash
   sudo apt update && sudo apt install -y python3-venv git
   git clone https://github.com/<you>/smart-glasses-ai-agent.git ~/ronie
   cd ~/ronie/brain && python3 -m venv .venv && .venv/bin/pip install .
   RONIE_KEY_STORE=file .venv/bin/ronie-brain set-key gemini    # and groq, openrouter
   ```
   `RONIE_KEY_STORE=file` keeps keys in `~/.ronie/keys.json`, readable only by your user, because a server has no desktop keyring.
6. **Run it as a service.** Create `/etc/systemd/system/ronie-brain.service`:
   ```ini
   [Unit]
   Description=Ronie brain
   After=network-online.target
   Wants=network-online.target

   [Service]
   User=ubuntu
   WorkingDirectory=/home/ubuntu/ronie/brain
   Environment=RONIE_KEY_STORE=file
   Environment=PYTHONUNBUFFERED=1
   ExecStart=/home/ubuntu/ronie/brain/.venv/bin/ronie-brain run --host 127.0.0.1 --port 8790
   Restart=always
   RestartSec=3
   MemoryMax=600M

   [Install]
   WantedBy=multi-user.target
   ```
   Then run `sudo systemctl enable --now ronie-brain`.
7. **Add HTTPS with Caddy.** [sslip.io](https://sslip.io) gives your IP a free hostname, `1-2-3-4.sslip.io` for `1.2.3.4`, so no domain is needed. Install Caddy ([instructions](https://caddyserver.com/docs/install#debian-ubuntu-raspbian)) and put this in `/etc/caddy/Caddyfile`:
   ```
   {
   	key_type rsa2048
   	cert_issuer acme {
   		preferred_chains {
   			root_common_name "ISRG Root X1"
   		}
   	}
   }

   1-2-3-4.sslip.io {
   	@agent path /ws/phone /ws/pc /ws/home /health
   	handle @agent {
   		reverse_proxy 127.0.0.1:8790
   	}
   	handle {
   		respond "OK" 404
   	}
   }
   ```
   Then run `sudo systemctl reload caddy`.
   - Only the app's connections are passed through. Mission control and key management stay private on the server.
   - The RSA key and "ISRG Root X1" chain avoid a certificate error some phones and Python versions show with the newer chain.
8. **Get your pairing code:**
   ```bash
   cd ~/ronie/brain && RONIE_KEY_STORE=file .venv/bin/ronie-brain pairing-code --url wss://1-2-3-4.sslip.io
   ```
   Copy the code it prints for step 3.
9. **Check it from anywhere:** open `https://1-2-3-4.sslip.io/health`. It should show `{"ok":true,...}`.

To open mission control on the server, use an SSH tunnel: `ssh -N -L 8791:127.0.0.1:8790 ubuntu@1.2.3.4`, then open http://127.0.0.1:8791/.

## 3. Build and install the phone app

1. Open the `android-app/` folder in Android Studio and let it sync. Meta's Wearables SDK downloads from Maven Central, with no token needed.
2. Turn on Developer options and USB debugging on your phone, connect it, and press Run. Or build from the command line with `./gradlew assembleDebug` and install `app/build/outputs/apk/debug/app-debug.apk`.
3. In the Meta AI app, turn on **Developer Mode** (Settings → App info → tap the version number 5 times). This lets the app use the glasses' camera.
4. Open the app → **Devices** → paste the pairing code from step 2. The status shows **Online**.
5. Allow the microphone, Bluetooth and notifications when asked. In Devices, tap **Allow Camera** for the glasses camera.
6. Put on the glasses and say **"Hey Ronie"**.

## 4. Optional: your TV

Any Android TV or Google TV on the same Wi-Fi. No developer mode is needed: the phone uses the TV's own remote service, like the Google TV remote app.

1. Find the TV's IP address (Settings → Network on the TV).
2. In the app: **Devices → Smart TV Remote** → enter the IP → **Pair**. The TV shows a 6-character code; type it into the app.
3. Try "Play lofi music on the TV" or "Turn on the PlayStation".

## 5. Optional: your Windows laptop

With the brain on your laptop (option A), laptop control works straight away. With the brain on a cloud server (option B), run the **laptop agent** so the server can reach your laptop:

1. Set up `brain/` on the laptop as in option A. You don't need to start the server.
2. Copy `cloud.env.example` to `cloud.env` and fill in your server and its pairing token: `RONIE_SERVER=ubuntu@1.2.3.4`, `RONIE_URL=wss://1-2-3-4.sslip.io`, and `RONIE_TOKEN=` set to the `token` value inside the pairing code from step 2. This file isn't committed.
3. Double-click `start-agent.cmd`. Mission control on the server shows the laptop as connected.
4. To start it with Windows, press Win+R, type `shell:startup`, and create a shortcut there to `start-agent-hidden.vbs`.
5. Pick your browser in the app: **Devices → Laptop**.

At home, explanations then appear on the screen you're facing. On the laptop you get a video on one display and an article on the laptop screen, or side by side with one display.

## 6. Updating the cloud server

After pulling new code on your computer, `./deploy-cloud.sh` (Git Bash) copies the brain to your server, installs it and restarts it. It reads your server from `cloud.env` and leaves your memory, keys and settings alone.

## Settings you might want

- **Devices → Clearer voice:** replies sound clearer over the glasses' music link, but you can't interrupt. Off by default.
- **Modes:** Assistant, Interview mode (quiet answer points during a real interview; pair it with the laptop agent to hear call audio), Interview practice, Game and more. Or just say "switch to game mode".
- **Your own words:** add names, places and shows the voice model should expect to `"vocabulary": [...]` in the server's `~/.ronie/settings.json`, then restart the brain.

## Troubleshooting

- **Offline in the app:** check `https://<your-host>/health`, and that the pairing code has the right `wss://` address.
- **TV not responding:** make sure the phone is on the same Wi-Fi as the TV, then **Re-Pair with TV**.
- **Wrong time or city in answers:** set `"timezone"` (for example `"Asia/Kolkata"`) in `~/.ronie/settings.json` on the server.
- **Phone logs:** the app writes a short diagnostic log, readable with `adb exec-out run-as com.ronie.app cat files/ronie.log`.
