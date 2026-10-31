"""Internet access: search the web, read the top pages, and write a spoken analysis with sources.

Search uses Tavily when a key is saved (free tier, built for AI agents), otherwise DuckDuckGo's
plain HTML results page, then Bing's, which need no key.
"""
from __future__ import annotations

import asyncio
import base64
import html as htmllib
import re
from urllib.parse import parse_qs, urlparse

import httpx

from .config import Keys

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
TIMEOUT = httpx.Timeout(8.0, connect=4.0)
PAGE_CHARS = 3000

RESEARCH_INSTRUCTIONS = (
    "The user asked a question that needs current information from the internet. Below are search results and "
    "text from the top pages. Answer from these sources only. The user wants a real analysis, so you may use up to "
    "eight spoken sentences: lead with the answer, then the key details, numbers and what they mean. If the sources "
    "disagree or are out of date, say so. If they don't answer the question, say what you found instead. "
    "Don't read out web addresses."
)


class WebError(Exception):
    pass


def html_to_text(page: str) -> str:
    page = re.sub(r"(?is)<(script|style|noscript|svg|nav|footer|header|form)\b.*?</\1>", " ", page)
    page = re.sub(r"(?s)<!--.*?-->", " ", page)
    page = re.sub(r"(?i)<br\s*/?>|</(p|div|li|h\d|tr)>", "\n", page)
    page = re.sub(r"<[^>]+>", " ", page)
    page = htmllib.unescape(page)
    page = re.sub(r"[ \t\r\f\v]+", " ", page)
    page = re.sub(r" *\n *", "\n", page)
    return re.sub(r"\n{2,}", "\n", page).strip()


def parse_duckduckgo(page: str) -> list[dict]:
    results = []
    links = re.finditer(r'<a[^>]*class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>', page, re.S)
    snippets = re.findall(r'class="result__snippet"[^>]*>(.*?)</(?:a|td|div)>', page, re.S)
    for i, m in enumerate(links):
        href = htmllib.unescape(m.group(1))
        if "duckduckgo.com/l/" in href:  # redirect link; the real address is in uddg
            href = parse_qs(urlparse(href).query).get("uddg", [href])[0]
        if href.startswith("//"):
            href = "https:" + href
        if not href.startswith("http") or "duckduckgo.com/y.js" in href:  # skip ads
            continue
        snippet = html_to_text(snippets[i]) if i < len(snippets) else ""
        results.append({"title": html_to_text(m.group(2)), "url": href, "snippet": snippet})
    return results


def parse_bing(page: str) -> list[dict]:
    """Bing's results page: each result's real address is base64 inside its "u=a1..." redirect link."""
    results, seen = [], set()
    for block in re.findall(r'<li class="b_algo"(.*?)</li>', page, re.S):
        m = re.search(r'<h2[^>]*>\s*<a[^>]*href="([^"]+)"[^>]*>(.*?)</a>', block, re.S)
        if not m:
            continue
        href = htmllib.unescape(m.group(1))
        coded = re.search(r"[?&]u=a1([A-Za-z0-9_-]+)", href)
        if coded:
            try:
                href = base64.urlsafe_b64decode(coded.group(1) + "=" * (-len(coded.group(1)) % 4)).decode()
            except (ValueError, UnicodeDecodeError):
                continue
        if not href.startswith("http") or href in seen:
            continue
        seen.add(href)
        snippet = re.search(r"<p[^>]*>(.*?)</p>", block, re.S)
        results.append({"title": html_to_text(m.group(2)), "url": href,
                        "snippet": html_to_text(snippet.group(1)) if snippet else ""})
    return results


class Web:
    def __init__(self, keys: Keys) -> None:
        self.keys = keys
        self.client = httpx.AsyncClient(timeout=TIMEOUT, headers={"User-Agent": UA}, follow_redirects=True)

    async def search(self, query: str, n: int = 6) -> list[dict]:
        if key := self.keys.get("tavily"):
            try:
                r = await self.client.post(
                    "https://api.tavily.com/search",
                    headers={"Authorization": f"Bearer {key}"},
                    json={"query": query, "max_results": n, "search_depth": "basic"},
                )
                r.raise_for_status()
                return [
                    {"title": x.get("title", ""), "url": x["url"], "snippet": x.get("content", "")}
                    for x in r.json().get("results", [])
                ][:n]
            except (httpx.HTTPError, KeyError, ValueError):
                pass  # fall back to DuckDuckGo
        found: list[dict] = []
        try:
            r = await self.client.post("https://html.duckduckgo.com/html/", data={"q": query})
            r.raise_for_status()
            found = parse_duckduckgo(r.text)  # empty when DuckDuckGo is rate-limiting ("anomaly" page)
        except httpx.HTTPError:
            pass
        if not found:  # Bing as the backup
            try:
                r = await self.client.get("https://www.bing.com/search", params={"q": query, "setlang": "en"})
                r.raise_for_status()
                found = parse_bing(r.text)
            except httpx.HTTPError as e:
                raise WebError(f"search failed: {type(e).__name__}") from e
        return found[:n]

    async def read(self, url: str) -> str:
        try:
            r = await self.client.get(url)
            if r.status_code >= 400 or "html" not in r.headers.get("content-type", "html"):
                return ""
            return html_to_text(r.text)[:PAGE_CHARS]
        except httpx.HTTPError:
            return ""

    async def gather_sources(self, query: str, pages: int = 4) -> list[dict]:
        results = await self.search(query)
        if not results:
            raise WebError("no search results")
        texts = await asyncio.gather(*(self.read(r["url"]) for r in results[:pages]))
        for r, text in zip(results, texts):
            r["text"] = text
        return results

    @staticmethod
    def research_prompt(question: str, sources: list[dict]) -> str:
        blocks = []
        for i, s in enumerate(sources, 1):
            body = s.get("text") or s.get("snippet", "")
            blocks.append(f"[{i}] {s['title']}\n{s['url']}\n{body}")
        return f"Question: {question}\n\nSources:\n\n" + "\n\n".join(blocks)
