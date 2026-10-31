"""Monthly spend per paid provider, stored in ~/.ronie/spend.json."""
from __future__ import annotations

import json
import time
from pathlib import Path

from ..config import Settings, data_dir

WARN_AT = 0.8


class Budget:
    def __init__(self, settings: Settings, path: Path | None = None) -> None:
        self.settings = settings
        self.path = path or data_dir() / "spend.json"
        self.data: dict = json.loads(self.path.read_text("utf-8")) if self.path.exists() else {}

    @staticmethod
    def _month() -> str:
        return time.strftime("%Y-%m")

    def cost(self, model: str, input_tokens: int, output_tokens: int) -> float:
        price = self.settings["prices_per_mtok"].get(model)
        if not price:
            return 0.0
        return (input_tokens * price[0] + output_tokens * price[1]) / 1_000_000

    def spent(self, provider: str) -> float:
        return self.data.get(self._month(), {}).get(provider, 0.0)

    def cap(self, provider: str) -> float | None:
        return self.settings["budgets_usd"].get(provider)

    def allows(self, provider: str) -> bool:
        cap = self.cap(provider)
        return cap is None or self.spent(provider) < cap

    def near_limit(self, provider: str) -> bool:
        cap = self.cap(provider)
        return bool(cap) and self.spent(provider) >= cap * WARN_AT

    def record(self, provider: str, amount: float) -> None:
        if amount <= 0:
            return
        month = self.data.setdefault(self._month(), {})
        month[provider] = month.get(provider, 0.0) + amount
        self.path.write_text(json.dumps(self.data, indent=2), "utf-8")

    def summary(self) -> dict[str, dict]:
        return {
            p: {"spent_usd": round(self.spent(p), 4), "cap_usd": self.cap(p)}
            for p in self.settings["budgets_usd"]
        }
