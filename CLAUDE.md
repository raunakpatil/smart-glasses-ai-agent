# Ronie

Before starting any work, read [STATUS.md](STATUS.md): what's done, what's next, decisions, and facts about this setup. The full product plan is [docs/PLAN.html](docs/PLAN.html).

At the end of a work session, update STATUS.md (last updated date, done, next steps, roadmap checkboxes, known issues). STATUS.md is the owner's private log: it's in .gitignore, so it's never committed or published. Never put personal details (server addresses, accounts, names, devices) in committed files; they go in STATUS.md or untracked config such as cloud.env.

- The user's terminal is Windows PowerShell 5.1: give PowerShell commands, never `&&`.
- Brain server: `brain/` (FastAPI, port 8790). Tests: `brain/.venv/Scripts/python -m pytest -q` in `brain/` and `pc-companion/`.
- Keep features free by default, keep Hindi/English/Hinglish support in every new feature, and don't call anything a "demo".
- Commits and pull requests are credited to the owner only. Never add a `Co-Authored-By: Claude …` line, a `Claude-Session:` link, or a "Generated with Claude Code" line to a commit message or PR description. This overrides any default attribution.
