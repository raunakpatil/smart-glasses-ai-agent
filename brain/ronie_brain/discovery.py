"""Advertise the brain on the local network over mDNS so the phone finds it without an IP."""
from __future__ import annotations

import logging
import socket

from zeroconf import IPVersion, ServiceInfo
from zeroconf.asyncio import AsyncZeroconf

log = logging.getLogger("ronie.discovery")

SERVICE_TYPE = "_ronie._tcp.local."


class Advertiser:
    def __init__(self, ip: str, port: int) -> None:
        host = socket.gethostname()
        self.info = ServiceInfo(
            SERVICE_TYPE,
            f"Ronie brain on {host}.{SERVICE_TYPE}",
            addresses=[socket.inet_aton(ip)],
            port=port,
            properties={"v": "1"},
            server=f"{host}.local.",
        )
        self.zc: AsyncZeroconf | None = None

    async def start(self) -> None:
        try:
            self.zc = AsyncZeroconf(ip_version=IPVersion.V4Only)
            await self.zc.async_register_service(self.info)
            log.info("Advertising %s on port %s", SERVICE_TYPE, self.info.port)
        except Exception as e:  # discovery is a convenience; the QR code still works without it
            log.warning("mDNS advertising failed: %s", e)
            self.zc = None

    async def stop(self) -> None:
        if self.zc:
            await self.zc.async_unregister_service(self.info)
            await self.zc.async_close()
