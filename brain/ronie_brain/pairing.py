"""Pairing token and the QR code the companion app scans."""
from __future__ import annotations

import json
import secrets
import socket
from pathlib import Path

import qrcode
import qrcode.image.svg

from .config import data_dir


def lan_ip() -> str:
    """The laptop's address on the local network (no packets are sent)."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


class Pairing:
    def __init__(self, path: Path | None = None) -> None:
        self.path = path or data_dir() / "pairing.json"
        if self.path.exists():
            self.token = json.loads(self.path.read_text("utf-8"))["token"]
        else:
            self.token = secrets.token_urlsafe(24)
            self.path.write_text(json.dumps({"token": self.token}), "utf-8")

    def check(self, token: object) -> bool:
        return isinstance(token, str) and secrets.compare_digest(token, self.token)

    def qr_payload(self, host: str, port: int, url: str | None = None) -> str:
        """url: the brain's full public address (e.g. wss://… for a cloud server); phones use it over host:port."""
        data = {"app": "ronie", "v": 1, "host": host, "port": port, "token": self.token}
        if url:
            data["url"] = url.rstrip("/")
        return json.dumps(data)

    def qr_svg(self, host: str, port: int, url: str | None = None) -> str:
        img = qrcode.make(self.qr_payload(host, port, url), image_factory=qrcode.image.svg.SvgPathImage)
        svg = img.to_string()
        return svg.decode("utf-8") if isinstance(svg, bytes) else svg
