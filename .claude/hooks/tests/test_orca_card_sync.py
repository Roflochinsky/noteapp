#!/usr/bin/env python3
"""Карточка Orca двигается только на claim и close настоящей bd-команды."""

import importlib.util
from pathlib import Path

_hook = Path(__file__).resolve().parent.parent / "orca-card-sync.py"
_spec = importlib.util.spec_from_file_location("orca_card_sync", _hook)
_mod = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_mod)
parse = _mod.parse


def test() -> None:
    for quiet in (
        "ls",
        "bd show nikitatrubaev-hcgg",
        "bd list --status in_progress",
        "bd update nikitatrubaev-hcgg --status blocked",
        'git commit -m "bd close nikitatrubaev-hcgg"',  # класс ошибки: закрытие из текста сообщения
    ):
        assert parse(quiet) is None, quiet

    assert parse("bd update nikitatrubaev-0rk.2 --claim") == ("nikitatrubaev-0rk.2", "claim")
    assert parse("bd close nikitatrubaev-0rk.2") == ("nikitatrubaev-0rk.2", "close")
    assert parse("cd app && bd close nikitatrubaev-0rk.6") == ("nikitatrubaev-0rk.6", "close")
    print("ok")


if __name__ == "__main__":
    test()
