"""`ronie-voice`: talk to Ronie with the laptop's mic and speakers through Gemini Live.

This is the PC version of the voice loop the phone app will run with the glasses.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import logging
import os
import sys
import urllib.request

from .brain_link import BrainError, BrainLink
from .live import LiveSession, LiveSetupError

log = logging.getLogger("ronie.voice")

FALLBACK_CONFIG = {
    "models": ["gemini-3.1-flash-live-preview", "gemini-3.8-live"],
    "voice": "",
    "system": "You are Ronie, a friendly voice assistant. Reply briefly, in the language the user speaks.",
    "functions": [],
}


def gemini_key() -> str | None:
    try:
        import keyring

        if key := keyring.get_password("ronie", "gemini"):
            return key
    except Exception:
        pass
    return os.environ.get("GEMINI_API_KEY")


def live_config(brain_http: str) -> dict:
    try:
        with urllib.request.urlopen(f"{brain_http}/api/live-config", timeout=3) as r:
            return json.load(r)
    except OSError as e:
        log.warning("Couldn't get Ronie's settings from the brain server (%s); using basic settings.", e)
        return FALLBACK_CONFIG


async def run(args) -> None:
    from .audio import Microphone, Speaker

    brain = BrainLink(args.brain.replace("http", "ws", 1) + "/ws/pc")
    await brain.connect()
    key = None if brain.connected else gemini_key()
    if not brain.connected and not key:
        sys.exit("The brain server isn't running and no Gemini key was found. Start start-ronie.cmd first.")

    config = live_config(args.brain)
    models = [args.model] if args.model else config["models"]

    speaker = Speaker(args.output_device)
    mic = Microphone(args.input_device)
    print("\nRonie is listening. Speak normally; press Ctrl+C to stop.")
    if not args.headphones:
        print("Using laptop speakers: Ronie mutes the mic while talking. Use --headphones to allow interrupting.\n")

    def show(who: str, text: str) -> None:
        print(f"{'You' if who == 'you' else 'Ronie'}: {text}")

    try:
        model_index = 0
        while model_index < len(models):
            # Fresh settings and shared memory for every session, so a new session (or a different
            # Live model) picks up exactly where the last one left off.
            token = None
            if brain.connected:
                # Same path as the phone: a one-use token, settings and shared memory from the brain.
                try:
                    ready = await brain.request("live.start", {}, timeout=15)
                    token, config = ready, ready["config"]
                except (BrainError, asyncio.TimeoutError) as e:
                    log.warning("Brain couldn't start a voice session (%s); using the local key", e)
                    key = key or gemini_key()
                    config = live_config(args.brain)
            else:
                config = live_config(args.brain)
            session = LiveSession(key, models[model_index], config, brain, speaker, headphones=args.headphones, token=token)
            brain.on_push = lambda msg, s=session: msg.get("type") == "live.restart" and (print("(Switching voice…)"), s.stop())
            session.on_transcript = show
            try:
                await session.run(mic.chunks())
                # The server ended the session (sessions have a time limit); start a fresh one on the same model.
            except LiveSetupError as e:
                log.warning("%s didn't start: %s", models[model_index], e)
                await brain.event("error", f"Voice model {models[model_index]} didn't start: {e}")
                model_index += 1
        sys.exit("No Gemini Live model could start a session. Check the key and its free quota.")
    finally:
        mic.close()
        speaker.close()
        await brain.close()


def main() -> None:
    parser = argparse.ArgumentParser(prog="ronie-voice", description="Talk to Ronie with the laptop mic and speakers.")
    parser.add_argument("--brain", default="http://127.0.0.1:8790", help="brain server address")
    parser.add_argument("--model", help="use this Gemini Live model instead of the configured chain")
    parser.add_argument("--headphones", action="store_true", help="keep the mic open while Ronie talks (allows interrupting)")
    parser.add_argument("--input-device", type=int, help="microphone device number (see --list-devices)")
    parser.add_argument("--output-device", type=int, help="speaker device number (see --list-devices)")
    parser.add_argument("--list-devices", action="store_true", help="show audio devices and exit")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s")
    if args.list_devices:
        import sounddevice as sd

        print(sd.query_devices())
        return
    try:
        asyncio.run(run(args))
    except KeyboardInterrupt:
        print("\nStopped.")


if __name__ == "__main__":
    main()
