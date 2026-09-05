#!/usr/bin/env python3
"""Страж nikitatrubaev-0rk.6: напоминания молчат на обычных командах и срабатывают по делу."""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from reminders import reminders  # noqa: E402


def test() -> None:
    # Класс ошибки, ради которого хук переписан: печаталось на каждый вызов Bash.
    for quiet in ("ls", "bd show nikitatrubaev-0rk", "git log --oneline", "bin/gate"):
        assert reminders(quiet) == [], quiet

    assert len(reminders("git commit -m 'правка'")) == 1
    assert len(reminders('bd create --title="x"')) == 1
    assert len(reminders("git add . && git commit -m x")) == 1
    print("ok")


if __name__ == "__main__":
    test()
