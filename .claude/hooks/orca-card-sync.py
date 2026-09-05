#!/usr/bin/env python3
"""PostToolUse(Bash): зеркалит статус bd-задачи на карточку Orca.

`bd update <id> --claim` → карточка «в работе» с заголовком задачи;
`bd close <id>` → «на проверке» (в нашей петле за закрытием среза идёт верификация,
а следующий claim вернёт «в работе»). Вне панели Orca хук молча выходит —
признак панели: переменная ORCA_WORKTREE_ID.
"""

import json
import os
import re
import shutil
import subprocess
import sys

# Якорь на начало команды или на разделитель: иначе `git commit -m "bd close ww-x"`
# двигал бы карточку.
_CMD = re.compile(r"(?:^|[;&|]\s*)bd\s+(update|close)\s+(nikitatrubaev-[a-z0-9.]+)([^;&|]*)")

_STATUS = {"claim": "in-progress", "close": "in-review"}


def parse(command: str) -> tuple[str, str] | None:
    """(id задачи, действие) для команд, меняющих её состояние; иначе None."""
    match = _CMD.search(command)
    if not match:
        return None
    action, issue, rest = match.group(1), match.group(2), match.group(3)
    if action == "close":
        return issue, "close"
    return (issue, "claim") if "--claim" in rest else None


def title(issue: str, cwd: str | None) -> str:
    try:
        out = subprocess.run(
            ["bd", "show", issue, "--json"],
            capture_output=True, text=True, timeout=20, check=False, cwd=cwd,
        ).stdout
        rows = json.loads(out)
    except (OSError, subprocess.SubprocessError, json.JSONDecodeError, ValueError):
        return ""
    return rows[0].get("title", "") if rows else ""


def main() -> int:
    if not os.environ.get("ORCA_WORKTREE_ID"):
        return 0
    orca = shutil.which("orca-ide")
    if not orca:
        return 0
    try:
        payload = json.load(sys.stdin)
    except (json.JSONDecodeError, ValueError):
        return 0
    parsed = parse(payload.get("tool_input", {}).get("command", ""))
    if not parsed:
        return 0
    issue, action = parsed
    cwd = os.environ.get("CLAUDE_PROJECT_DIR")
    comment = f"{issue} — {title(issue, cwd)}" if action == "claim" else f"{issue} закрыт"
    subprocess.run(
        [orca, "worktree", "set", "--worktree", "active",
         "--workspace-status", _STATUS[action], "--comment", comment[:100]],
        capture_output=True, timeout=30, check=False,
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
