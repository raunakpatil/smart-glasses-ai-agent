"""Ronie's shared memory, kept by the brain server so every model and voice session sees the same thing.

Three layers, all in ~/.ronie/memory.db:
- facts:   long-term things worth keeping (what the user asked Ronie to remember, preferences, people, plans)
- summary: a running summary of older conversation, rewritten during compaction
- turns:   the conversation itself; recent turns go into every prompt word for word

A minute after the conversation goes quiet, the Compactor reads what was said since its last look and saves new
facts ("I'm a Real Madrid fan"), so they're remembered the same day. When the recent conversation grows past a size
limit, it also condenses the oldest turns into the summary. Both use the free model chain in the background.

Facts can carry a topic ("parking spot"): a new fact with the same topic replaces the old one at once. Plans and
reminders can carry an expiry date, after which they're dropped. Search matches words in any script (Hindi in
Devanagari too) and, with a Gemini key, also matches by meaning, so a Hinglish question finds an English memory.
"""
from __future__ import annotations

import asyncio
import json
import logging
import math
import re
import sqlite3
import time
from array import array
from datetime import datetime, timedelta
from pathlib import Path
from typing import Awaitable, Callable

from .config import Settings, data_dir, local_now, user_tz

log = logging.getLogger("ronie.memory")

SCHEMA = """
CREATE TABLE IF NOT EXISTS turns (
    id INTEGER PRIMARY KEY, ts REAL NOT NULL, role TEXT NOT NULL, text TEXT NOT NULL,
    source TEXT NOT NULL DEFAULT '', compacted INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS facts (
    id INTEGER PRIMARY KEY, ts REAL NOT NULL, updated REAL NOT NULL, text TEXT NOT NULL,
    kind TEXT NOT NULL DEFAULT 'note', importance INTEGER NOT NULL DEFAULT 3, source TEXT NOT NULL DEFAULT ''
);
CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS fact_vectors (id INTEGER PRIMARY KEY, text TEXT NOT NULL, vec BLOB NOT NULL);
CREATE VIRTUAL TABLE IF NOT EXISTS facts_fts USING fts5(text, content='facts', content_rowid='id');
CREATE VIRTUAL TABLE IF NOT EXISTS turns_fts USING fts5(text, content='turns', content_rowid='id');
CREATE TRIGGER IF NOT EXISTS facts_ai AFTER INSERT ON facts BEGIN
    INSERT INTO facts_fts(rowid, text) VALUES (new.id, new.text); END;
CREATE TRIGGER IF NOT EXISTS facts_ad AFTER DELETE ON facts BEGIN
    INSERT INTO facts_fts(facts_fts, rowid, text) VALUES ('delete', old.id, old.text); END;
CREATE TRIGGER IF NOT EXISTS facts_au AFTER UPDATE OF text ON facts BEGIN
    INSERT INTO facts_fts(facts_fts, rowid, text) VALUES ('delete', old.id, old.text);
    INSERT INTO facts_fts(rowid, text) VALUES (new.id, new.text); END;
CREATE TRIGGER IF NOT EXISTS turns_ai AFTER INSERT ON turns BEGIN
    INSERT INTO turns_fts(rowid, text) VALUES (new.id, new.text); END;
"""

KINDS = ("note", "preference", "person", "place", "plan", "reminder", "fact")


def tokens(text: str) -> int:
    """Rough token count (about 4 characters per token), enough to decide when to compact."""
    return len(text) // 4 + 1


# A word: letters and digits plus the vowel signs of Indian scripts. Python's \w leaves out those signs (Unicode
# marks), which cut "गाड़ी" into single letters, and search then found nothing for any Hindi in Devanagari.
WORD = re.compile(r"[\w\u0300-\u036f\u0900-\u0963\u0966-\u0dff]+")  # not the danda (।), a full stop


def words(text: str) -> list[str]:
    return [w for w in WORD.findall(text.lower()) if w != "_"]


def _norm(text: str) -> str:
    return " ".join(words(text))


def _fts_query(text: str, op: str = "OR") -> str | None:
    # Short English words (is, my, to) say nothing; short Hindi words (घर, कल) often do.
    kept = [w.replace('"', "") for w in words(text) if len(w) > 2 or not w.isascii()][:12]
    return f" {op} ".join(f'"{w}"' for w in kept) or None


def _cosine(a, b) -> float:
    dot = sum(x * y for x, y in zip(a, b))
    na, nb = math.sqrt(sum(x * x for x in a)), math.sqrt(sum(y * y for y in b))
    return dot / (na * nb) if na and nb else 0.0


def _date(text: str) -> str:
    """A YYYY-MM-DD date, or "" when the model wrote something else."""
    text = str(text or "").strip()[:10]
    try:
        datetime.strptime(text, "%Y-%m-%d")
        return text
    except ValueError:
        return ""


# async (texts, task) -> one vector per text, or None when there's no key. task: RETRIEVAL_DOCUMENT or RETRIEVAL_QUERY.
Embedder = Callable[[list[str], str], Awaitable["list[list[float]] | None"]]


class Memory:
    def __init__(self, settings: Settings, path: Path | None = None) -> None:
        self.settings = settings
        self.db = sqlite3.connect(path or data_dir() / "memory.db", check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.db.executescript(SCHEMA)
        columns = {r["name"] for r in self.db.execute("PRAGMA table_info(facts)")}
        for column in ("topic", "expires"):  # added after the first release; old databases get them here
            if column not in columns:
                self.db.execute(f"ALTER TABLE facts ADD COLUMN {column} TEXT NOT NULL DEFAULT ''")
        self.db.commit()
        self.embedder: Embedder | None = None  # set by the app when search by meaning is possible
        self._embed_off_until = 0.0  # after a failed embedding call, words-only search for a while

    # ---------- conversation ----------

    def add_turn(self, role: str, text: str, source: str = "") -> int | None:
        text = text.strip()
        if not text:
            return None
        cur = self.db.execute("INSERT INTO turns (ts, role, text, source) VALUES (?, ?, ?, ?)", (time.time(), role, text, source))
        self.db.commit()
        return cur.lastrowid

    def recent_turns(self, n: int) -> list[sqlite3.Row]:
        rows = self.db.execute("SELECT * FROM turns ORDER BY id DESC LIMIT ?", (n,)).fetchall()
        return rows[::-1]

    def uncompacted(self) -> list[sqlite3.Row]:
        return self.db.execute("SELECT * FROM turns WHERE compacted = 0 ORDER BY id").fetchall()

    def uncompacted_tokens(self) -> int:
        return sum(tokens(r["text"]) for r in self.uncompacted())

    def turns_after(self, turn_id: int) -> list[sqlite3.Row]:
        return self.db.execute("SELECT * FROM turns WHERE id > ? ORDER BY id", (turn_id,)).fetchall()

    def facts_scanned(self) -> int:
        """The last turn already read for new facts. Before the first scan: the last compacted turn."""
        row = self.db.execute("SELECT value FROM meta WHERE key = 'facts_scanned'").fetchone()
        if row:
            return int(row["value"])
        row = self.db.execute("SELECT MAX(id) AS id FROM turns WHERE compacted = 1").fetchone()
        return row["id"] or 0

    def set_facts_scanned(self, turn_id: int) -> None:
        self.db.execute("INSERT OR REPLACE INTO meta (key, value) VALUES ('facts_scanned', ?)", (str(turn_id),))
        self.db.commit()

    def mark_compacted(self, up_to_id: int) -> None:
        self.db.execute("UPDATE turns SET compacted = 1 WHERE id <= ?", (up_to_id,))
        self.db.commit()

    # ---------- long-term facts ----------

    def remember(self, text: str, kind: str = "note", importance: int = 4, source: str = "", topic: str = "",
                 expires: str = "") -> int:
        """Save a fact. A fact with the same wording is refreshed instead of duplicated, and one with the same topic
        ("parking spot") is replaced: the newest is the true one. expires: YYYY-MM-DD, the last day it matters."""
        text = text.strip()
        kind = kind if kind in KINDS else "note"
        try:
            importance = max(1, min(5, int(importance)))
        except (TypeError, ValueError):
            importance = 3
        topic, expires = _norm(topic or ""), _date(expires)
        now = time.time()
        fact_id = None
        for row in self.db.execute("SELECT id, text FROM facts").fetchall():
            if _norm(row["text"]) == _norm(text):
                self.db.execute(
                    "UPDATE facts SET updated = ?, importance = MAX(importance, ?), "
                    "topic = CASE WHEN ? != '' THEN ? ELSE topic END, expires = ? WHERE id = ?",
                    (now, importance, topic, topic, expires, row["id"]),
                )
                fact_id = row["id"]
                break
        if fact_id is None:
            cur = self.db.execute(
                "INSERT INTO facts (ts, updated, text, kind, importance, source, topic, expires) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                (now, now, text, kind, importance, source, topic, expires),
            )
            fact_id = cur.lastrowid
        if topic:
            for old in self.db.execute("SELECT id FROM facts WHERE topic = ? AND id != ?", (topic, fact_id)).fetchall():
                self._delete(old["id"])
        self.db.commit()
        return fact_id

    def update_fact(self, fact_id: int, text: str) -> bool:
        cur = self.db.execute("UPDATE facts SET text = ?, updated = ? WHERE id = ?", (text.strip(), time.time(), fact_id))
        self.db.commit()
        return cur.rowcount > 0

    def _delete(self, fact_id: int) -> int:
        self.db.execute("DELETE FROM fact_vectors WHERE id = ?", (fact_id,))
        return self.db.execute("DELETE FROM facts WHERE id = ?", (fact_id,)).rowcount

    def forget(self, fact_id: int) -> bool:
        gone = self._delete(fact_id)
        self.db.commit()
        return gone > 0

    def fact(self, fact_id: int) -> sqlite3.Row | None:
        return self.db.execute("SELECT * FROM facts WHERE id = ?", (fact_id,)).fetchone()

    def facts(self) -> list[sqlite3.Row]:
        return self.db.execute("SELECT * FROM facts ORDER BY importance DESC, updated DESC").fetchall()

    def facts_changed_since(self, ts: float) -> list[sqlite3.Row]:
        return self.db.execute("SELECT * FROM facts WHERE updated > ? ORDER BY updated", (ts,)).fetchall()

    def today(self) -> str:
        return local_now(self.settings).date().isoformat()

    def prune_expired(self) -> int:
        """Drops plans and reminders whose day has passed (last week's parking spot, a trip that's over)."""
        old = self.db.execute("SELECT id FROM facts WHERE expires != '' AND expires < ?", (self.today(),)).fetchall()
        for row in old:
            self._delete(row["id"])
        if old:
            self.db.commit()
            log.info("Dropped %d expired memories", len(old))
        return len(old)

    def _fts_facts(self, query: str, limit: int, op: str = "OR") -> list[sqlite3.Row]:
        q = _fts_query(query, op)
        if not q:
            return []
        return self.db.execute(
            "SELECT f.* FROM facts_fts JOIN facts f ON f.id = facts_fts.rowid WHERE facts_fts MATCH ? ORDER BY rank LIMIT ?",
            (q, limit),
        ).fetchall()

    def search(self, query: str, limit: int = 8) -> dict[str, list]:
        """Matching words, in any script."""
        q = _fts_query(query)
        if not q:
            return {"facts": [], "turns": []}
        turns = self.db.execute(
            "SELECT t.* FROM turns_fts JOIN turns t ON t.id = turns_fts.rowid WHERE turns_fts MATCH ? ORDER BY rank LIMIT ?",
            (q, limit),
        ).fetchall()
        return {"facts": self._fts_facts(query, limit), "turns": turns}

    # ---------- search by meaning ----------

    async def _embed(self, texts: list[str], task: str) -> list[list[float]] | None:
        if self.embedder is None or time.time() < self._embed_off_until:
            return None
        try:
            return await asyncio.wait_for(self.embedder(texts, task), timeout=8)
        except Exception as e:  # no network, quota used up: words-only for ten minutes, so recall stays fast
            log.info("Search by meaning is off for now: %s", e)
            self._embed_off_until = time.time() + 600
            return None

    async def semantic(self, query: str, limit: int = 8) -> list[sqlite3.Row]:
        """Facts close in meaning to the query, closest first, in any language. Empty without a Gemini key."""
        if not query.strip() or self.embedder is None or time.time() < self._embed_off_until:
            return []
        facts = self.facts()
        if not facts:
            return []
        stored = {r["id"]: r for r in self.db.execute("SELECT * FROM fact_vectors")}
        missing = [f for f in facts if f["id"] not in stored or stored[f["id"]]["text"] != f["text"]]
        for i in range(0, len(missing), 100):  # Google takes up to 100 texts per call
            batch = missing[i : i + 100]
            vectors = await self._embed([f["text"] for f in batch], "RETRIEVAL_DOCUMENT")
            if not vectors:
                return []
            for f, v in zip(batch, vectors):
                self.db.execute("INSERT OR REPLACE INTO fact_vectors (id, text, vec) VALUES (?, ?, ?)",
                                (f["id"], f["text"], array("f", v).tobytes()))
            self.db.commit()
        q = await self._embed([query], "RETRIEVAL_QUERY")
        if not q:
            return []
        vectors = {r["id"]: array("f", r["vec"]) for r in self.db.execute("SELECT id, vec FROM fact_vectors")}
        floor = float(self.settings["memory_semantic_min"])
        scored = sorted(((_cosine(q[0], vectors[f["id"]]), f) for f in facts if f["id"] in vectors),
                        key=lambda x: -x[0])
        return [f for score, f in scored[:limit] if score >= floor]

    async def find(self, query: str, limit: int = 8) -> dict[str, list]:
        """What recall uses: memories matching the words or the meaning, plus past conversation with those words."""
        found = self.search(query, limit)
        merged: dict[int, sqlite3.Row] = {}
        for row in [*found["facts"], *await self.semantic(query, limit)]:
            merged.setdefault(row["id"], row)
        return {"facts": list(merged.values())[:limit], "turns": found["turns"]}

    async def candidates_to_forget(self, query: str) -> tuple[list[sqlite3.Row], bool]:
        """Facts that fit what the user wants forgotten, and whether a single one is a sure match. All the words
        first ("mom birthday" must not pick the sister's birthday); failing that, the meaning or any one word,
        which are only guesses, so Ronie checks with the user before deleting."""
        if sure := self._fts_facts(query, 3, "AND"):
            return sure, True
        return (await self.semantic(query, 3) or self._fts_facts(query, 3, "OR")), False

    # ---------- summary ----------

    def summary(self) -> str:
        row = self.db.execute("SELECT value FROM meta WHERE key = 'summary'").fetchone()
        return row["value"] if row else ""

    def set_summary(self, text: str) -> None:
        self.db.execute("INSERT OR REPLACE INTO meta (key, value) VALUES ('summary', ?)", (text.strip(),))
        self.db.commit()

    # ---------- what every model sees ----------

    def recent_user_text(self, n: int = 4) -> str:
        """What the user talked about lately: the best guess at what's relevant before they've asked anything."""
        rows = self.db.execute("SELECT text FROM turns WHERE role = 'user' ORDER BY id DESC LIMIT ?", (n,)).fetchall()
        return " ".join(r["text"] for r in rows)

    def _when(self, ts: float) -> str:
        tz = user_tz(self.settings)
        then, now = datetime.fromtimestamp(ts, tz), local_now(self.settings)
        clock = then.strftime("%I:%M %p").lstrip("0")
        if then.date() == now.date():
            return f"today {clock}"
        if then.date() == now.date() - timedelta(days=1):
            return f"yesterday {clock}"
        return f"{then.strftime('%A %d %B').replace(' 0', ' ')}, {clock}"

    def _recent_conversation(self) -> str:
        """Recent turns, with a time label wherever there was a pause, so yesterday's "I'm at the airport" isn't
        taken as true now."""
        recent = self.recent_turns(self.settings["memory_recent_turns"])
        gap = self.settings["memory_turn_gap_minutes"] * 60
        lines, prev = [], None
        for r in recent:
            since = r["ts"] - prev if prev is not None else time.time() - r["ts"]
            if since > gap:
                lines.append(f"[{self._when(r['ts'])}]")
            lines.append(f"{'User' if r['role'] == 'user' else 'Ronie'}: {r['text']}")
            prev = r["ts"]
        return "\n".join(lines)

    def context(self, query: str = "", also: list[int] | None = None) -> str:
        """The memory block added to every prompt and voice session, so all models share one memory.
        Facts that fit the query (or, with no query, what the user said lately) come first, then anything saved in
        the last few days, then the most important. also: ids of facts already found relevant by meaning."""
        self.prune_expired()
        max_facts = self.settings["memory_max_facts"]
        chosen: dict[int, sqlite3.Row] = {}
        for fid in also or []:
            if (row := self.fact(fid)) is not None:
                chosen[fid] = row
        for row in self._fts_facts(query or self.recent_user_text(), 10):
            chosen.setdefault(row["id"], row)
        fresh_since = time.time() - self.settings["memory_fresh_days"] * 86400
        fresh = [r for r in self.facts_changed_since(fresh_since)][::-1]  # newest first
        for row in [*fresh, *self.facts()]:
            if len(chosen) >= max_facts:
                break
            chosen.setdefault(row["id"], row)

        now = local_now(self.settings)
        parts = [f"Current date and time: {now.strftime('%A %d %B %Y, %I:%M %p').replace(' 0', ' ')} (user's local time)."]
        if chosen:
            lines = "\n".join(f"- {r['text']}" for r in sorted(chosen.values(), key=lambda r: -r["importance"]))
            parts.append("What you know about the user, from earlier conversations:\n" + lines)
        if summary := self.summary():
            parts.append("Summary of earlier conversations:\n" + summary)
        if convo := self._recent_conversation():
            parts.append(
                "Most recent conversation, oldest first. A [time] marks when the lines after it were said; what was "
                "true then (where the user was, what they were doing) may not be true now:\n" + convo
            )
        parts.append(
            "Use this memory naturally, as a friend who remembers. Never mention a memory system, "
            "summaries, models or providers."
        )
        return "\n\n".join(parts)

    async def session_context(self) -> str:
        """The memory for a voice session that's starting: like context(), plus facts that fit by meaning what the
        user talked about lately (a Hinglish chat finds the English facts it's about)."""
        recent = self.recent_user_text()
        also: list[int] = []
        if recent:
            try:  # the voice session waits for this: never hold it up for long
                also = [r["id"] for r in await asyncio.wait_for(self.semantic(recent, 8), timeout=2.5)]
            except asyncio.TimeoutError:
                pass
        return self.context(also=also)


COMPACT_PROMPT = """You maintain the long-term memory of Ronie, a personal voice assistant.
Today is {today}. Write every date as an absolute date (e.g. "Friday 31 October 2026"), never "tomorrow" or "next week".

Current summary of earlier conversations:
{summary}

Facts already saved (id: text):
{facts}

Older conversation to fold into memory now:
{turns}

Return only a JSON object with these keys:
"summary": an updated summary of all earlier conversations, at most 200 words, in English, keeping names, dates, plans, decisions and anything the user may ask about later;
"new_facts": a list of new long-term facts worth keeping, each {{"text": "...", "kind": one of {kinds}, "importance": 1-5, "topic": "...", "expires": "YYYY-MM-DD"}}. {fact_rules};
"updated_facts": a list of {{"id": number, "text": "..."}} for saved facts that changed;
"obsolete_fact_ids": ids of saved facts that are no longer true."""


FACT_RULES = (
    "Keep facts about the user's life: anything they asked to remember (importance 5), likes and dislikes, teams and "
    "fandoms, people, places, work, studies, health, routines and plans with dates. Write each as one short sentence "
    "about the user. Skip small talk, one-off requests (play this, open that), what Ronie said or did, device or app "
    "states (battery, volume, settings, pairing, developer mode), passwords, keys and tokens, and anything already saved. "
    "Write facts in English even when the user spoke Hindi or Hinglish. Give \"topic\" a short label (\"parking spot\", "
    "\"current city\", \"job\") when a newer fact on it should replace the old one, else \"\". Give \"expires\" the "
    "last date a fact matters for plans, reminders and where something is right now (a parking spot: today), else \"\""
)

FACTS_PROMPT = """You keep the long-term memory of Ronie, a personal voice assistant.
Today is {today}. Write every date as an absolute date (e.g. "Friday 31 October 2026"), never "tomorrow" or "next week".

Facts already saved (id: text):
{facts}

Conversation since the last check:
{turns}

Return only a JSON object with these keys:
"new_facts": a list of new long-term facts, each {{"text": "...", "kind": one of {kinds}, "importance": 1-5, "topic": "...", "expires": "YYYY-MM-DD"}}. {fact_rules}. Usually there are none; return [] then;
"updated_facts": a list of {{"id": number, "text": "..."}} for saved facts this conversation changed;
"obsolete_fact_ids": ids of saved facts this conversation shows are no longer true."""


def parse_json_object(text: str) -> dict:
    """Models sometimes wrap JSON in prose or code fences; take the outermost object."""
    start, end = text.find("{"), text.rfind("}")
    if start < 0 or end <= start:
        raise ValueError("no JSON object in reply")
    return json.loads(text[start : end + 1])


class Compactor:
    def __init__(self, memory: Memory, settings: Settings, complete) -> None:
        """complete: async (prompt) -> reply text, using the free model chain."""
        self.memory, self.settings, self.complete = memory, settings, complete
        self.lock = asyncio.Lock()
        self.task: asyncio.Task | None = None
        self.facts_task: asyncio.Task | None = None

    def today(self) -> str:
        """Today in the user's time zone: on a cloud server the clock is UTC, and late at night in India that's
        still yesterday, so "tomorrow" would be saved as the wrong date."""
        now = local_now(self.settings)
        return f"{now.strftime('%A %d %B %Y').replace(' 0', ' ')} ({now.date().isoformat()})"

    def needed(self) -> bool:
        return self.memory.uncompacted_tokens() > self.settings["memory_compact_tokens"]

    def maybe_schedule(self) -> None:
        if self.needed() and not self.lock.locked() and not (self.task and not self.task.done()):
            self.task = asyncio.create_task(self.compact())

    def note_turn(self) -> None:
        """A turn was added: (re)start the quiet timer; when it runs out, look for new facts."""
        if self.facts_task and not self.facts_task.done():
            self.facts_task.cancel()
        self.facts_task = asyncio.create_task(self._facts_after_quiet())

    async def _facts_after_quiet(self) -> None:
        await asyncio.sleep(self.settings["memory_fact_delay"])
        await self.extract_facts()

    async def extract_facts(self) -> dict:
        """Read what was said since the last look and save new facts (no summary change)."""
        async with self.lock:
            turns = self.memory.turns_after(self.memory.facts_scanned())
            if not any(t["role"] == "user" for t in turns):
                if turns:
                    self.memory.set_facts_scanned(turns[-1]["id"])
                return {"scanned": len(turns), "facts_added": 0}
            facts = "\n".join(f"{f['id']}: {f['text']}" for f in self.memory.facts()) or "(none)"
            convo = "\n".join(f"{'User' if t['role'] == 'user' else 'Ronie'}: {t['text']}" for t in turns)
            prompt = FACTS_PROMPT.format(
                today=self.today(), facts=facts, turns=convo, kinds=", ".join(KINDS), fact_rules=FACT_RULES,
            )
            try:
                data = parse_json_object(await self.complete(prompt))
            except Exception as e:  # try again after the next turn
                log.warning("Fact check failed: %s", e)
                return {"scanned": 0, "error": str(e)}
            added = self._apply_facts(data)
            self.memory.set_facts_scanned(turns[-1]["id"])
            log.info("Fact check: %d turns read, %d facts added", len(turns), added)
            return {"scanned": len(turns), "facts_added": added}

    def _apply_facts(self, data: dict) -> int:
        added = 0
        for f in data.get("new_facts") or []:
            if isinstance(f, dict) and str(f.get("text", "")).strip():
                self.memory.remember(f["text"], f.get("kind", "note"), f.get("importance", 3), source="compaction",
                                     topic=str(f.get("topic") or ""), expires=str(f.get("expires") or ""))
                added += 1
        for f in data.get("updated_facts") or []:
            if isinstance(f, dict) and isinstance(f.get("id"), int) and str(f.get("text", "")).strip():
                self.memory.update_fact(f["id"], f["text"])
        for fid in data.get("obsolete_fact_ids") or []:
            if isinstance(fid, int):
                self.memory.forget(fid)
        return added

    async def compact(self) -> dict:
        async with self.lock:
            turns = self.memory.uncompacted()
            keep = self.settings["memory_keep_turns"]
            to_fold = turns[:-keep] if keep else turns
            if not to_fold:
                return {"folded": 0}
            facts = "\n".join(f"{f['id']}: {f['text']}" for f in self.memory.facts()) or "(none)"
            convo = "\n".join(f"{'User' if t['role'] == 'user' else 'Ronie'}: {t['text']}" for t in to_fold)
            prompt = COMPACT_PROMPT.format(
                today=self.today(),
                summary=self.memory.summary() or "(none yet)", facts=facts, turns=convo, kinds=", ".join(KINDS),
                fact_rules=FACT_RULES,
            )
            try:
                data = parse_json_object(await self.complete(prompt))
            except Exception as e:  # keep the turns uncompacted and try again later
                log.warning("Memory compaction failed: %s", e)
                return {"folded": 0, "error": str(e)}
            if isinstance(data.get("summary"), str) and data["summary"].strip():
                self.memory.set_summary(data["summary"])
            added = self._apply_facts(data)
            self.memory.mark_compacted(to_fold[-1]["id"])
            log.info("Memory compacted: %d turns folded, %d facts added", len(to_fold), added)
            return {"folded": len(to_fold), "facts_added": added}
