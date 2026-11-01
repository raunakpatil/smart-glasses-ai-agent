"""Ronie's shared memory, kept by the brain server so every model and voice session sees the same thing.

Three layers, all in ~/.ronie/memory.db:
- facts:   long-term things worth keeping (what the user asked Ronie to remember, preferences, people, plans)
- summary: a running summary of older conversation, rewritten during compaction
- turns:   the conversation itself; recent turns go into every prompt word for word

A minute after the conversation goes quiet, the Compactor reads what was said since its last look and saves new
facts ("I'm a Real Madrid fan"), so they're remembered the same day. When the recent conversation grows past a size
limit, it also condenses the oldest turns into the summary. Both use the free model chain in the background.
"""
from __future__ import annotations

import asyncio
import json
import logging
import re
import sqlite3
import time
from pathlib import Path

from .config import Settings, data_dir

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


def _norm(text: str) -> str:
    return re.sub(r"[^\w]+", " ", text.lower()).strip()


def _fts_query(text: str) -> str | None:
    words = [w for w in re.findall(r"\w+", text.lower()) if len(w) > 2][:12]
    return " OR ".join(f'"{w}"' for w in words) or None


class Memory:
    def __init__(self, settings: Settings, path: Path | None = None) -> None:
        self.settings = settings
        self.db = sqlite3.connect(path or data_dir() / "memory.db", check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.db.executescript(SCHEMA)
        self.db.commit()

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

    def remember(self, text: str, kind: str = "note", importance: int = 4, source: str = "") -> int:
        """Save a fact. A fact with the same wording is refreshed instead of duplicated."""
        text = text.strip()
        kind = kind if kind in KINDS else "note"
        importance = max(1, min(5, int(importance)))
        for row in self.db.execute("SELECT id, text, importance FROM facts"):
            if _norm(row["text"]) == _norm(text):
                self.db.execute(
                    "UPDATE facts SET updated = ?, importance = MAX(importance, ?) WHERE id = ?",
                    (time.time(), importance, row["id"]),
                )
                self.db.commit()
                return row["id"]
        now = time.time()
        cur = self.db.execute(
            "INSERT INTO facts (ts, updated, text, kind, importance, source) VALUES (?, ?, ?, ?, ?, ?)",
            (now, now, text, kind, importance, source),
        )
        self.db.commit()
        return cur.lastrowid

    def update_fact(self, fact_id: int, text: str) -> None:
        self.db.execute("UPDATE facts SET text = ?, updated = ? WHERE id = ?", (text.strip(), time.time(), fact_id))
        self.db.commit()

    def forget(self, fact_id: int) -> bool:
        cur = self.db.execute("DELETE FROM facts WHERE id = ?", (fact_id,))
        self.db.commit()
        return cur.rowcount > 0

    def facts(self) -> list[sqlite3.Row]:
        return self.db.execute("SELECT * FROM facts ORDER BY importance DESC, updated DESC").fetchall()

    def search(self, query: str, limit: int = 8) -> dict[str, list]:
        q = _fts_query(query)
        if not q:
            return {"facts": [], "turns": []}
        facts = self.db.execute(
            "SELECT f.* FROM facts_fts JOIN facts f ON f.id = facts_fts.rowid WHERE facts_fts MATCH ? ORDER BY rank LIMIT ?",
            (q, limit),
        ).fetchall()
        turns = self.db.execute(
            "SELECT t.* FROM turns_fts JOIN turns t ON t.id = turns_fts.rowid WHERE turns_fts MATCH ? ORDER BY rank LIMIT ?",
            (q, limit),
        ).fetchall()
        return {"facts": facts, "turns": turns}

    # ---------- summary ----------

    def summary(self) -> str:
        row = self.db.execute("SELECT value FROM meta WHERE key = 'summary'").fetchone()
        return row["value"] if row else ""

    def set_summary(self, text: str) -> None:
        self.db.execute("INSERT OR REPLACE INTO meta (key, value) VALUES ('summary', ?)", (text.strip(),))
        self.db.commit()

    # ---------- what every model sees ----------

    def context(self, query: str = "") -> str:
        """The memory block added to every prompt and voice session, so all models share one memory."""
        max_facts = self.settings["memory_max_facts"]
        chosen: dict[int, sqlite3.Row] = {}
        if query:
            for row in self.search(query, limit=10)["facts"]:
                chosen[row["id"]] = row
        for row in self.facts():
            if len(chosen) >= max_facts:
                break
            chosen.setdefault(row["id"], row)

        parts = [time.strftime("Current date and time: %A %d %B %Y, %I:%M %p (user's local time).")]
        if chosen:
            lines = "\n".join(f"- {r['text']}" for r in sorted(chosen.values(), key=lambda r: -r["importance"]))
            parts.append("What you know about the user, from earlier conversations:\n" + lines)
        if summary := self.summary():
            parts.append("Summary of earlier conversations:\n" + summary)
        recent = self.recent_turns(self.settings["memory_recent_turns"])
        if recent:
            convo = "\n".join(f"{'User' if r['role'] == 'user' else 'Ronie'}: {r['text']}" for r in recent)
            parts.append("Most recent conversation, oldest first:\n" + convo)
        parts.append(
            "Use this memory naturally, as a friend who remembers. Never mention a memory system, "
            "summaries, models or providers."
        )
        return "\n\n".join(parts)


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
"new_facts": a list of new long-term facts worth keeping, each {{"text": "...", "kind": one of {kinds}, "importance": 1-5}}. {fact_rules};
"updated_facts": a list of {{"id": number, "text": "..."}} for saved facts that changed;
"obsolete_fact_ids": ids of saved facts that are no longer true."""


FACT_RULES = (
    "Keep facts about the user's life: anything they asked to remember (importance 5), likes and dislikes, teams and "
    "fandoms, people, places, work, studies, health, routines and plans with dates. Write each as one short sentence "
    "about the user. Skip small talk, one-off requests (play this, open that), what Ronie said or did, device or app "
    "states (battery, volume, settings, pairing, developer mode), passwords, keys and tokens, and anything already saved"
)

FACTS_PROMPT = """You keep the long-term memory of Ronie, a personal voice assistant.
Today is {today}. Write every date as an absolute date (e.g. "Friday 31 October 2026"), never "tomorrow" or "next week".

Facts already saved (id: text):
{facts}

Conversation since the last check:
{turns}

Return only a JSON object with these keys:
"new_facts": a list of new long-term facts, each {{"text": "...", "kind": one of {kinds}, "importance": 1-5}}. {fact_rules}. Usually there are none; return [] then;
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
                today=time.strftime("%A %d %B %Y"), facts=facts, turns=convo, kinds=", ".join(KINDS), fact_rules=FACT_RULES,
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
                self.memory.remember(f["text"], f.get("kind", "note"), f.get("importance", 3), source="compaction")
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
                today=time.strftime("%A %d %B %Y"),
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
