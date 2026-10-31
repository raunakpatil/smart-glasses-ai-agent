"""`ronie-brain` command: run the server, or manage API keys."""
from __future__ import annotations

import argparse
import getpass
import os
import json
import logging
import socket
import sys

from .config import PROVIDERS, Keys


def port_in_use(port: int) -> str | None:
    """Return the address already answering on this port (IPv4 or IPv6 localhost), if any."""
    for family, addr in ((socket.AF_INET, "127.0.0.1"), (socket.AF_INET6, "::1")):
        with socket.socket(family, socket.SOCK_STREAM) as s:
            s.settimeout(0.5)
            if s.connect_ex((addr, port)) == 0:
                return addr
    return None


def main() -> None:
    parser = argparse.ArgumentParser(prog="ronie-brain")
    sub = parser.add_subparsers(dest="cmd")

    run = sub.add_parser("run", help="Start the brain server (default)")
    run.add_argument("--host", default="0.0.0.0", help="0.0.0.0 lets the phone reach it on Wi-Fi")
    run.add_argument("--port", type=int, default=8790)

    key_providers = [p for p in PROVIDERS if p != "ollama"]
    set_key = sub.add_parser("set-key", help="Save an API key in the Windows Credential Manager")
    set_key.add_argument("provider", choices=key_providers)
    del_key = sub.add_parser("delete-key", help="Remove a saved API key")
    del_key.add_argument("provider", choices=key_providers)
    sub.add_parser("status", help="Show which providers are set up")
    agent = sub.add_parser("agent", help="On the laptop, when the brain runs on a server: do laptop actions for it")
    agent.add_argument("--url", required=True, help="The brain's public address, e.g. wss://1-2-3-4.sslip.io")
    agent.add_argument("--token", help="The server's pairing token (default: RONIE_TOKEN, else this computer's own)")
    code = sub.add_parser("pairing-code", help="Print the phone pairing code (paste it in the app: Devices)")
    code.add_argument("--url", help="The brain's public address, e.g. wss://1-2-3-4.sslip.io (also saved as public_url)")

    args = parser.parse_args()

    if args.cmd == "set-key":
        key = getpass.getpass(f"{args.provider} API key (input hidden): ").strip()
        if not key:
            parser.error("No key entered")
        Keys().set(args.provider, key)
        print(f"Saved the {args.provider} key.")
        return
    if args.cmd == "delete-key":
        Keys().delete(args.provider)
        print(f"Removed the {args.provider} key.")
        return
    if args.cmd == "agent":
        from .agent import main as agent_main

        from .pairing import Pairing

        # The server's pairing token: --token, RONIE_TOKEN (from cloud.env), or this computer's own pairing.
        agent_main(args.url, args.token or os.environ.get("RONIE_TOKEN") or Pairing().token)
        return
    if args.cmd == "pairing-code":
        from .config import Settings
        from .pairing import Pairing, lan_ip

        settings = Settings()
        if args.url:
            settings.update({"public_url": args.url})
        print(Pairing().qr_payload(lan_ip(), 8790, settings.data.get("public_url") or None))
        return
    if args.cmd == "status":
        from .config import Settings
        from .models.providers import build_providers

        settings = Settings()
        providers = build_providers(settings, Keys())
        print(json.dumps({n: {"ready": p.available(), "paid": p.paid} for n, p in providers.items()}, indent=2))
        return

    import uvicorn

    from .app import create_app

    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s")
    host = getattr(args, "host", "0.0.0.0")
    port = getattr(args, "port", 8790)
    if busy := port_in_use(port):
        sys.exit(
            f"Port {port} is already used by another program on {busy}. "
            f"Stop it, or start Ronie on another port: ronie-brain run --port {port + 1}"
        )
    print(f"Mission control: http://127.0.0.1:{port}/   Pair the phone: http://127.0.0.1:{port}/pair")
    print("Press Ctrl+C to stop.")
    # Open mission control tabs keep WebSockets alive; don't wait on them when stopping.
    uvicorn.run(create_app(port=port), host=host, port=port, timeout_graceful_shutdown=2)


if __name__ == "__main__":
    main()
