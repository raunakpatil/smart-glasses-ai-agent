"""Interview mode: the user's career knowledge base, a gap check against a job, and live answer cues.

Everything Ronie says in an interview comes from the user's own documents. When nothing matches, the cue
says so instead of inventing experience.
"""
from __future__ import annotations

import base64
import io
import json
import re
import sqlite3
import time
import zipfile
from pathlib import Path
from typing import Awaitable, Callable

from .config import data_dir
from .memory import parse_json_object

Complete = Callable[[str], Awaitable[str]]  # prompt -> model reply text

SCHEMA = """
CREATE TABLE IF NOT EXISTS docs (id INTEGER PRIMARY KEY, ts REAL NOT NULL, name TEXT NOT NULL, text TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS cues (
    id INTEGER PRIMARY KEY, ts REAL NOT NULL, question TEXT NOT NULL, keywords TEXT NOT NULL, card TEXT NOT NULL,
    story TEXT
);
"""

MAX_DOC_CHARS = 40_000
# Speaker by loudness: the two voices must be at least this far apart (dB) before loudness decides on its own,
# and a line must be this far past the middle point.
SEPARATION_DB = 6.0
MARGIN_DB = 2.0


class ImportError_(Exception):
    pass


def extract_text(filename: str, data: bytes) -> str:
    """Plain text from a PDF, Word (.docx), or text file."""
    name = filename.lower()
    if name.endswith(".pdf"):
        from pypdf import PdfReader

        try:
            reader = PdfReader(io.BytesIO(data))
            text = "\n".join(page.extract_text() or "" for page in reader.pages)
        except Exception as e:
            raise ImportError_(f"Couldn't read that PDF ({e})") from e
    elif name.endswith(".docx"):
        try:
            with zipfile.ZipFile(io.BytesIO(data)) as z:
                xml = z.read("word/document.xml").decode("utf-8", "ignore")
        except (zipfile.BadZipFile, KeyError) as e:
            raise ImportError_("Couldn't read that Word file") from e
        xml = re.sub(r"</w:p>", "\n", xml)
        text = re.sub(r"<[^>]+>", "", xml)
    else:
        text = data.decode("utf-8", "ignore")
    text = re.sub(r"[ \t]+", " ", text)
    text = re.sub(r"\n\s*\n+", "\n", text).strip()
    if not text:
        raise ImportError_("No text found. If it's a scanned PDF, paste the text instead.")
    return text[:MAX_DOC_CHARS]


PROFILE_PROMPT = """Build a structured career profile from the user's documents below. Use only facts that appear in them.

Documents:
{docs}

Return only a JSON object:
{{"name": "...", "headline": "one line: role and years of experience",
  "skills": ["..."],
  "experience": [{{"role": "...", "company": "...", "dates": "...", "highlights": ["... with numbers when given ..."]}}],
  "projects": [{{"name": "...", "summary": "...", "numbers": ["e.g. cut latency 40%"]}}],
  "stories": [{{"title": "short name", "themes": ["conflict", "failure", "leadership", "deadline", "ownership", "teamwork", "impact", "learning", "technical challenge", "customer"],
               "situation": "...", "task": "...", "action": "...", "result": "... with numbers when given",
               "keywords": "2 to 4 short spoken cue words, e.g. payments API … load test … minus 40 percent"}}]}}
Write 4 to 8 STAR stories from the strongest experiences. Don't invent numbers, names or events."""

GAP_PROMPT = """Compare the candidate's profile with the job description.

Profile:
{profile}

Job description:
{jd}

Return only a JSON object:
{{"strengths": ["where the profile matches the job well"],
  "gaps": ["requirements the profile doesn't show"],
  "likely_questions": ["8 to 10 questions this interviewer is likely to ask, hardest first"],
  "prepare": ["what to prepare for each gap, briefly"]}}"""

CUE_PROMPT = """You help the user answer an interview question in real time from their own experience.

Their profile (the only source of facts you may use):
{profile}

{jd_part}The interviewer just asked: "{question}"

Return only a JSON object:
{{"type": "behavioral | technical | about you | motivation | salary | other",
  "story": "title of the best matching story, or null",
  "keywords": "2 to 4 very short spoken cues separated by ' … ', from the user's real experience",
  "card": "3 to 5 short bullet-like lines separated by ' · ': the points to make, in order, with real numbers"}}
Strict honesty rules:
- Every fact, tool, number, company and responsibility in keywords and card must appear in the profile above.
- Never claim experience, familiarity or responsibilities the profile doesn't show, not even "related" ones.
- If nothing in the profile fits, set story to null and keywords to "no matching story". The card then says honestly
  that they haven't done it, names only the closest real experience from the profile, and how they would learn or
  approach it ("I'd start by…"), clearly as a plan, not as something they did."""


ANSWER_PROMPT = """You are the candidate's private earpiece during a live job interview. You hear everything in the room,
transcribed, through a microphone on the CANDIDATE's glasses. Some lines are the INTERVIEWER talking to the candidate;
others are the CANDIDATE talking (answering, thinking aloud, small talk), or the candidate talking to their assistant.

Loudness helps tell them apart: the candidate's own voice is right next to the microphone, so it is usually much
louder than the interviewer's (who is across the table, on a laptop or a phone speaker).
{loudness}

The candidate's profile (the only source of facts about them):
{profile}

{jd_part}Recent conversation in the room, oldest first:
{recent}

New line just heard: "{text}"

First decide who said the new line, using both the loudness and the words. Then decide: is the new line the
INTERVIEWER asking the candidate something or asking them to talk about something
(a question, "tell me about yourself", "walk me through...", "why should we hire you", a follow-up like "can you
elaborate")? Lines where someone describes their own experience in first person ("I worked on...", "my role was")
are the CANDIDATE answering, not a question.

Return only a JSON object:
{{"speaker": "interviewer" or "candidate" or "unclear",
  "interviewer_question": true or false,
  "question": "the question, cleaned up",
  "points": ["if interviewer_question: 3 to 5 cue points, in the order to say them. These are NOT sentences to read
              out: the candidate hears one at a time and talks about it in their own words. Each point is 3 to 8
              words: the idea plus its key fact (a number, company, tool or result), e.g. 'Sigma AI, led four
              reviewers', 'accuracy up eighteen percent'. The first point is the angle to open with; for behavioural
              questions follow situation, task, action, result (never say those labels). Numbers written the way
              they're said ('eighteen percent'). Same language as the interviewer (English, Hindi or Hinglish).
              Empty list if not interviewer_question"]}}

The points must answer the actual question, as one line of thought (each point follows from the one before), not a
list of the candidate's highlights. Points are whispered aloud: no labels like "Honest gap:" or "Result:". Shapes for
classic questions:
- Weakness (also "an area you're working to improve", "what would your manager say you could do better", "what
  feedback have you had"): one real, non-critical weakness, preferably a work habit (over-polishing before sharing, finding it hard to
  delegate, saying yes to too much, going deep before checking in). A skill gap from the gap check only if it is clearly
  not central to this job; never one of the job's main requirements. Then when it showed up at work, what they do
  about it now, and a concrete sign it's improving. Never a disguised strength.
- Strength / why hire you: the strength, the best proof from the profile, why it matters for this role.
- Tell me about yourself: present role and focus, the strongest achievement, what they want next and why this role.
- Why leave / why this company: positive reasons only (growth, the work); never criticise an employer.
- Conflict, failure, mistake, challenge: situation, what they did, the result, what they learned.

Honesty rules: every fact, company, tool, number and responsibility in the points must be in the profile. Never claim
experience the profile doesn't show. If the profile has nothing relevant, answer honestly that you haven't done that
yet, mention only the closest real experience, and how you would approach it, clearly as a plan."""


# Phrases the candidate repeats: long enough to sound natural, short enough to hold in mind after one hearing.
PHRASE_MIN_WORDS = 4
PHRASE_MAX_WORDS = 9
JOINERS = {"and", "but", "so", "because", "which", "where", "while", "when", "then", "after", "before", "using",
           "aur", "lekin", "toh", "kyunki", "jab"}


def phrases(answer: str) -> list[str]:
    """Split an answer into short spoken phrases at natural breaks: sentence ends, commas, then joining words
    ("and", "because"…), and only as a last resort mid-clause. Short leftovers are merged into their neighbour."""
    out: list[str] = []
    for sentence in re.split(r"(?<=[.!?])\s+", re.sub(r"\s+", " ", answer).strip()):
        words = sentence.split()
        if not words:
            continue
        if len(words) <= PHRASE_MAX_WORDS:
            out.append(sentence)
            continue
        parts: list[list[str]] = [[]]
        for i, w in enumerate(words):
            left = len(words) - i  # words from here to the end, this one included
            cur = parts[-1]
            if len(cur) >= PHRASE_MIN_WORDS and left >= PHRASE_MIN_WORDS and (
                    w.lower().strip(",") in JOINERS or len(cur) >= PHRASE_MAX_WORDS + 1):
                parts.append([])
            parts[-1].append(w)
            if (w.endswith((",", ";", ":")) and len(parts[-1]) >= PHRASE_MIN_WORDS
                    and left - 1 >= PHRASE_MIN_WORDS):
                parts.append([])
        for p in parts:
            if len(p) > PHRASE_MAX_WORDS + 1:  # no natural break: cut it in half
                half = (len(p) + 1) // 2
                out += [" ".join(p[:half]), " ".join(p[half:])]
            elif p:
                out.append(" ".join(p))
    merged: list[str] = []  # "Sure." on its own is too short to be worth a pause
    for p in out:
        if merged and len(merged[-1].split()) <= 2:
            merged[-1] += " " + p
        else:
            merged.append(p)
    return merged


CHECK_PROMPT = """A job candidate in an interview is given cue points one at a time and talks about each in their own
words. All the points: "{answer}"
The point they just got: "{expected}"
What they actually said about it (speech-to-text, may have small errors): "{heard}"

Expanding, rephrasing, adding detail, or speaking Hindi or Hinglish is exactly what they should do: judge only facts.
Return only a JSON object:
{{"verdict": "ok" | "wrong",
  "correction": "if wrong: 3 to 8 words to whisper so they can fix it smoothly, e.g. 'eighteen percent, not eighty';
                 else empty"}}
"wrong" only if they stated a fact from this point differently (a number, company, tool, role, result) or said
something that contradicts the points. Leaving things out or adding their own reasonable detail is "ok"."""

STOP_WORDS = {"a", "an", "the", "and", "or", "but", "so", "to", "of", "in", "on", "at", "for", "with", "by", "is", "was",
              "are", "were", "be", "it", "that", "this", "i", "my", "me", "we", "our", "as", "about", "also", "just",
              "really", "very", "then", "which", "where", "when", "while", "because"}


NUMBER_WORDS = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
                "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty", "thirty",
                "forty", "fifty", "sixty", "seventy", "eighty", "ninety", "hundred", "thousand", "million", "half"}


def numbers_in(text: str) -> set[str]:
    return {w for w in re.findall(r"[a-z0-9]+", text.lower()) if w.isdigit() or w in NUMBER_WORDS}


def words_covered(expected: str, heard: str) -> float:
    """Share of the phrase's meaningful words that the user actually said (0..1)."""
    def content(text: str) -> set[str]:
        return {w for w in re.findall(r"[a-z0-9']+", text.lower()) if w not in STOP_WORDS}

    want = content(expected)
    return len(want & content(heard)) / len(want) if want else 1.0


class Interview:
    def __init__(self, path: Path | None = None) -> None:
        self.room: list[str] = []  # what was heard recently in interview cues mode, for context
        # Loudness (dBFS) of lines already attributed to each speaker, to learn who is louder on this mic.
        self.levels: dict[str, list[float]] = {"candidate": [], "interviewer": []}
        self.db = sqlite3.connect(path or data_dir() / "interview.db", check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.db.executescript(SCHEMA)
        self.db.commit()

    # ---------- documents ----------

    def add_doc(self, name: str, text: str) -> int:
        cur = self.db.execute("INSERT INTO docs (ts, name, text) VALUES (?, ?, ?)", (time.time(), name, text))
        self.db.commit()
        return cur.lastrowid

    def add_file(self, filename: str, data_b64: str) -> int:
        return self.add_doc(filename, extract_text(filename, base64.b64decode(data_b64)))

    def remove_doc(self, doc_id: int) -> bool:
        cur = self.db.execute("DELETE FROM docs WHERE id = ?", (doc_id,))
        self.db.commit()
        return cur.rowcount > 0

    def docs(self) -> list[dict]:
        return [
            {"id": r["id"], "name": r["name"], "chars": len(r["text"])}
            for r in self.db.execute("SELECT * FROM docs ORDER BY id")
        ]

    # ---------- stored values ----------

    def _get(self, key: str):
        row = self.db.execute("SELECT value FROM meta WHERE key = ?", (key,)).fetchone()
        return json.loads(row["value"]) if row else None

    def _set(self, key: str, value) -> None:
        self.db.execute("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)", (key, json.dumps(value, ensure_ascii=False)))
        self.db.commit()

    @property
    def profile(self) -> dict | None:
        return self._get("profile")

    @property
    def job(self) -> str:
        return self._get("job") or ""

    def set_job(self, text: str) -> None:
        self._set("job", text.strip())
        self._set("gaps", None)

    @property
    def gaps(self) -> dict | None:
        return self._get("gaps")

    # ---------- building ----------

    async def build(self, complete: Complete) -> dict:
        """Profile and STAR stories from the documents, then the gap check if a job description is set."""
        rows = self.db.execute("SELECT name, text FROM docs ORDER BY id").fetchall()
        if not rows:
            raise ImportError_("Add a resume or other document first")
        docs = "\n\n".join(f"### {r['name']}\n{r['text']}" for r in rows)
        profile = parse_json_object(await complete(PROFILE_PROMPT.format(docs=docs)))
        self._set("profile", profile)
        if self.job:
            gaps = parse_json_object(await complete(GAP_PROMPT.format(profile=json.dumps(profile, ensure_ascii=False), jd=self.job)))
            self._set("gaps", gaps)
        return profile

    # ---------- live cues ----------

    async def cue(self, question: str, complete: Complete) -> dict:
        profile = self.profile
        if not profile:
            return {"keywords": "no profile yet", "card": "Import your resume in mission control and build your profile first."}
        jd_part = f"The job: {self.job[:3000]}\n\n" if self.job else ""
        data = parse_json_object(await complete(CUE_PROMPT.format(
            profile=json.dumps(profile, ensure_ascii=False), jd_part=jd_part, question=question.strip(),
        )))
        result = {
            "type": str(data.get("type", "other")),
            "story": data.get("story"),
            "keywords": str(data.get("keywords", "")).strip(),
            "card": str(data.get("card", "")).strip(),
        }
        self.db.execute(
            "INSERT INTO cues (ts, question, keywords, card, story) VALUES (?, ?, ?, ?, ?)",
            (time.time(), question.strip(), result["keywords"], result["card"], result["story"]),
        )
        self.db.commit()
        return result

    def loudness_verdict(self, level_db: float | None) -> str | None:
        """Who spoke, by loudness alone, once both voices have been heard and are clearly apart (else None)."""
        mine, theirs = self.levels["candidate"][-10:], self.levels["interviewer"][-10:]
        if level_db is None or not mine or not theirs:
            return None
        me, them = sum(mine) / len(mine), sum(theirs) / len(theirs)
        if me - them < SEPARATION_DB:  # too close to tell apart on this microphone
            return None
        middle = (me + them) / 2
        if level_db > middle + MARGIN_DB:
            return "candidate"
        if level_db < middle - MARGIN_DB:
            return "interviewer"
        return None

    def calibrate(self, level_db: float) -> None:
        """The user said something to start ("I'm ready"): that's how loud their own voice is on this mic."""
        self.levels["candidate"].append(level_db)

    def loudness_hint(self, level_db: float | None) -> str:
        if level_db is None:
            return "Loudness of the new line: unknown."
        parts = [f"Loudness of the new line: {level_db:.0f} dBFS (higher, i.e. closer to 0, is louder)."]
        for who, levels in self.levels.items():
            if levels:
                recent = levels[-10:]
                parts.append(f"Lines judged to be the {who} so far averaged {sum(recent) / len(recent):.0f} dBFS ({len(recent)} lines).")
        if len(parts) == 1:
            parts.append("No earlier lines to compare yet, so rely more on the words.")
        return " ".join(parts)

    async def respond(self, text: str, complete: Complete, level_db: float | None = None,
                      speaker: str | None = None) -> dict:
        """Interview cues mode: work out who spoke; if it's the interviewer asking something, answer as the candidate.
        speaker: already known when the interviewer is heard from the laptop's call audio (the glasses' mic is then
        only the candidate), so loudness isn't needed."""
        text = text.strip()
        recent = "\n".join(self.room[-8:]) or "(nothing yet)"
        verdict = speaker or self.loudness_verdict(level_db)
        if verdict == "candidate":
            # The user's own voice: never answer it, whatever the words (e.g. repeating the question).
            if speaker is None:
                self.levels["candidate"].append(level_db)
            self.room.append(f"- [candidate] {text}")
            return {"speaker": "candidate", "interviewer_question": False, "question": "", "answer": "", "points": ""}
        if not self.profile:
            return {"interviewer_question": False, "error": "Build your interview profile in mission control first."}
        jd_part = f"The job: {self.job[:3000]}\n\n" if self.job else ""
        if self.gaps and self.gaps.get("gaps"):
            jd_part += f"Gap check (honest weak spots for this kind of role): {json.dumps(self.gaps['gaps'], ensure_ascii=False)}\n\n"
        if speaker == "interviewer":
            loudness = "This line is certainly the INTERVIEWER: it came from the call audio, not the candidate's mic."
        else:
            loudness = self.loudness_hint(level_db) + (
                " Judging by loudness, this line is the interviewer." if verdict == "interviewer" else "")
        data = parse_json_object(await complete(ANSWER_PROMPT.format(
            profile=json.dumps(self.profile, ensure_ascii=False), jd_part=jd_part, recent=recent, text=text,
            loudness=loudness,
        )))
        speaker_known = speaker is not None
        speaker = verdict or str(data.get("speaker") or "unclear")
        if not speaker_known and speaker in self.levels and level_db is not None:
            self.levels[speaker].append(level_db)
        loud = f", {level_db:.0f} dB" if level_db is not None and not speaker_known else ""
        self.room.append(f"- [{speaker}{loud}] {text}")
        result = {
            "speaker": speaker,
            "interviewer_question": bool(data.get("interviewer_question")) and speaker != "candidate",
            "question": str(data.get("question") or text).strip(),
        }
        raw = data.get("points") or []
        if isinstance(raw, str):  # some models send "a · b · c" instead of a list
            raw = re.split(r"\s*[·•|\n]\s*", raw)
        points = [str(p).strip().strip("-•").strip().replace("‑", "-") for p in raw if str(p).strip()][:5]
        if not result["interviewer_question"]:
            points = []
        # Cue points, said one at a time; the user expands each in their own words.
        result["phrases"] = points
        result["points"] = " · ".join(points)
        result["answer"] = "\n".join(f"• {p}" for p in points)
        if result["interviewer_question"] and result["answer"]:
            self.db.execute(
                "INSERT INTO cues (ts, question, keywords, card, story) VALUES (?, ?, ?, ?, ?)",
                (time.time(), result["question"], result["points"], result["answer"], None),
            )
            self.db.commit()
        return result

    async def check_repeat(self, heard: str, expected: str, answer: str, complete: Complete) -> dict:
        """The user talked about a cue point (in any words or language): if they got one of its facts wrong, a short
        correction to whisper."""
        # Clearly said it (and every number right, since one wrong number is the costly mistake): no model needed.
        if words_covered(expected, heard) >= 0.6 and numbers_in(heard) == numbers_in(expected):
            return {"verdict": "ok", "correction": ""}
        data = parse_json_object(await complete(CHECK_PROMPT.format(answer=answer[:1500], expected=expected,
                                                                    heard=heard)))
        verdict = str(data.get("verdict") or "ok")
        if verdict not in ("ok", "wrong"):
            verdict = "ok"
        correction = str(data.get("correction") or "").strip() if verdict != "ok" else ""
        return {"verdict": verdict if correction else "ok", "correction": correction}

    def recent_cues(self, limit: int = 30) -> list[dict]:
        return [dict(r) for r in self.db.execute("SELECT * FROM cues ORDER BY id DESC LIMIT ?", (limit,))]

    def summary(self) -> str:
        """A compact profile for the practice interviewer's instructions."""
        p = self.profile or {}
        stories = "; ".join(s.get("title", "") for s in p.get("stories", []))
        return (
            f"Candidate: {p.get('name', 'the user')}, {p.get('headline', '')}. Skills: {', '.join(p.get('skills', [])[:20])}. "
            f"Their stories: {stories}."
        )
