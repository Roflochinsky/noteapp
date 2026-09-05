#!/usr/bin/env python3
"""Роутер скиллов: подсказка по теме запроса, без ложных срабатываний на словах проекта."""

import json
import subprocess
import sys
from pathlib import Path

HOOK = Path(__file__).resolve().parent.parent / "skill-router.py"


def route(prompt: str) -> str:
    out = subprocess.run(
        [sys.executable, str(HOOK)], input=json.dumps({"prompt": prompt}), capture_output=True, text=True
    ).stdout
    return json.loads(out)["hookSpecificOutput"]["additionalContext"]


def test() -> None:
    assert "discuss" in route("давай обсудим, как хранить словарь имён")
    assert "adr" in route("меняем формат заметки: имя файла с секундами")
    assert "review-intent" in route("сделай ревью замысла по спеке v3")
    assert "epic-orca" in route("гони эпик на Orca")
    # слово проекта, не пилот: RepoWriteWorker/PushWorker — не Orca
    assert "epic-orca" not in route("воркер записи падает на 304")
    assert "правила под эту тему нет" in route("привет")
    print("ok")


if __name__ == "__main__":
    test()
