import pytest

from ronie_brain.config import Settings
from ronie_brain.models.base import LLMRequest, LLMResult, Provider, ProviderError
from ronie_brain.models.budget import Budget
from ronie_brain.pairing import Pairing


class FakeKeys:
    def __init__(self):
        self.store = {}

    def get(self, provider):
        return self.store.get(provider)

    def set(self, provider, key):
        self.store[provider] = key

    def delete(self, provider):
        self.store.pop(provider, None)


class FakeProvider(Provider):
    def __init__(self, name, paid=False, ready=True, fail=False, reply=None, tokens=(100, 50)):
        self.name, self.paid, self.ready, self.fail = name, paid, ready, fail
        self.reply = reply or f"answer from {name}"
        self.tokens = tokens
        self.calls = 0

    def available(self):
        return self.ready

    async def complete(self, req: LLMRequest) -> LLMResult:
        self.calls += 1
        if self.fail:
            raise ProviderError(f"{self.name}: down")
        model = "claude-sonnet-5-5" if self.name == "claude" else f"{self.name}-model"
        return LLMResult(self.reply, self.name, model, *self.tokens)


@pytest.fixture
def home(tmp_path, monkeypatch):
    monkeypatch.setenv("RONIE_HOME", str(tmp_path))
    return tmp_path


@pytest.fixture
def settings(home):
    return Settings()


@pytest.fixture
def budget(settings, home):
    return Budget(settings)


@pytest.fixture
def pairing(home):
    return Pairing()
