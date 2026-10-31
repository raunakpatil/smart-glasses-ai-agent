"""Picks a provider for each request and falls back down the route when one fails."""
from __future__ import annotations

import time
from dataclasses import replace
from typing import Awaitable, Callable

from ..config import Settings
from ..persona import SPOKEN_TASKS, clean_spoken, system_prompt
from .base import LLMRequest, LLMResult, Provider, ProviderError
from .budget import Budget

EventSink = Callable[[str, str, dict], Awaitable[None]]


class AllProvidersFailed(Exception):
    def __init__(self, tried: list[str]) -> None:
        super().__init__("No provider could answer: " + ("; ".join(tried) or "none available"))
        self.tried = tried


class Router:
    def __init__(
        self,
        settings: Settings,
        providers: dict[str, Provider],
        budget: Budget,
        on_event: EventSink | None = None,
    ) -> None:
        self.settings, self.providers, self.budget = settings, providers, budget
        self.on_event = on_event
        self.cooldown: dict[str, float] = {}  # provider -> Unix time its rate limit resets
        # Set by the app: returns the shared memory block for a prompt, so every provider sees the same memory.
        self.memory_context: Callable[[str], str] | None = None

    def cooling_until(self, name: str) -> float | None:
        until = self.cooldown.get(name)
        return until if until and until > time.time() else None

    async def _emit(self, stage: str, text: str, **extra) -> None:
        if self.on_event:
            await self.on_event(stage, text, extra)

    def route(self, task: str) -> list[str]:
        routes = self.settings["routes"]
        return routes.get(task) or routes["general"]

    def candidates(self, req: LLMRequest) -> tuple[list[Provider], list[str]]:
        """Providers to try in order, plus the reasons others were skipped."""
        premium = self.settings["premium"] if req.premium is None else req.premium
        chosen, skipped = [], []
        for name in self.route(req.task):
            p = self.providers.get(name)
            if p is None:
                continue
            if p.paid and not premium:
                continue  # silently: free mode is the normal case
            if not p.available():
                skipped.append(f"{name}: not set up")
            elif until := self.cooling_until(name):
                skipped.append(f"{name}: limit reached, back at {time.strftime('%H:%M', time.localtime(until))}")
            elif p.paid and not self.budget.allows(name):
                skipped.append(f"{name}: monthly budget used up")
            else:
                chosen.append(p)
        return chosen, skipped

    async def complete(self, req: LLMRequest) -> LLMResult:
        if not req.raw:
            parts = [system_prompt(req.task, self.settings)]
            if self.memory_context:
                parts.append(self.memory_context(req.prompt))
            if req.system:
                parts.append(req.system)
            req = replace(req, system="\n\n".join(parts))
        chosen, tried = self.candidates(req)
        for p in chosen:
            start = time.perf_counter()
            await self._emit("model", f"Trying {p.name} for {req.task}", provider=p.name)
            try:
                result = await p.complete(req)
            except ProviderError as e:
                if e.retry_after:
                    self.cooldown[p.name] = time.time() + e.retry_after
                tried.append(str(e))
                await self._emit("error", str(e), provider=p.name)
                continue
            result.ms = int((time.perf_counter() - start) * 1000)
            if req.task in SPOKEN_TASKS and not req.raw:
                result.text = clean_spoken(result.text)
            result.fallbacks = tried + result.fallbacks
            if p.paid:
                result.cost_usd = self.budget.cost(result.model, result.input_tokens, result.output_tokens)
                self.budget.record(p.name, result.cost_usd)
                if self.budget.near_limit(p.name):
                    await self._emit("model", f"{p.name} is above 80% of its monthly budget", provider=p.name)
            await self._emit(
                "memory" if req.raw else "answer",
                "Memory updated in the background" if req.raw else result.text,
                provider=result.provider,
                model=result.model,
                ms=result.ms,
                cost_usd=result.cost_usd,
            )
            return result
        raise AllProvidersFailed(tried)
