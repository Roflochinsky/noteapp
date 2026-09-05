#!/usr/bin/env python3
"""PreToolUse(Bash): напоминание только по делу — перед коммитом и перед заведением задачи.

Перенесено из workwatch (там условие переехало из полей "if" внутрь команды после
замера ww-zh60.6: echo-хуки печатались на каждый `ls`). Правила адаптированы под noteapp.
"""

import json
import re
import sys

RULES: list[tuple[str, str]] = [
    (
        r"\bgit\s+commit\b",
        "Напоминание перед коммитом (не блокирует): сообщение — на русском, содержательное; "
        "код — источник истины: обнови связанную документацию (CONTEXT.md — глоссарий, "
        "docs/adr/, docs/specs/ — скилл /update-docs); связанные bd-задачи должны иметь "
        "актуальный статус.",
    ),
    (
        r"\bbd\s+create\b",
        "Напоминание (не блокирует): первая строка описания bd-задачи — «Продукт: <ценность "
        "одним предложением>», затем пустая строка и техчасть по-русски (зачем, что, ссылки "
        "на спеку/план); у среза эпика последней строкой — машинная «Трогает: <пути через "
        "запятую>» (docs/agents/issue-tracker.md). Задача — срез спеки, не находка ревью.",
    ),
]


def reminders(command: str) -> list[str]:
    return [text for pattern, text in RULES if re.search(pattern, command)]


def main() -> None:
    try:
        data = json.load(sys.stdin)
    except Exception:
        return
    command = (data.get("tool_input") or {}).get("command") or ""
    hits = reminders(command)
    if not hits:
        return
    print(
        json.dumps(
            {
                "hookSpecificOutput": {
                    "hookEventName": "PreToolUse",
                    "additionalContext": "\n".join(hits),
                }
            },
            ensure_ascii=False,
        )
    )


if __name__ == "__main__":
    main()
