"""Тесты scripts/spec-check.py — правило синхронизации двух частей спеки (R1 §7.2)."""

import subprocess
import sys
from pathlib import Path

import pytest

CHECK = Path(__file__).resolve().parent.parent / "spec-check.py"

HUMAN = """# Спека: динамика раздела

## Зачем это

Руководитель не видит, изменилось ли что-нибудь между двумя периодами.

## Решение 1. Ведущая метрика — зачтённый длительный простой

Раздел ведёт зачтённый длительный простой в минутах на смену, потому что на нём держатся
статусы и рейтинги, а активность остаётся справочным числом без нормы и цвета.

Увидишь: открыл раздел → в шапке минуты на смену, активность второй строкой.

## Решение 2. Несопоставимые строки не сравниваются

Если у сотрудника в одном из окон нет ни одной смены, строка показывается как пропуск и
нулём не подставляется, чтобы сдвиг не считался от выдуманного значения.

Увидишь: у сотрудника нет смен в июле → в таблице прочерк, а не −100 %.
"""

AGENT = """
## Критерии приёмки

| Критерий | Источник |
|---|---|
| Шапка показывает минуты на смену | Решение 1 |
| Пропуск вместо нуля | Решение 2 |

## Слои

| Слой | Что меняется |
|---|---|
| API | ручка /dynamics/compare — Решение 1, Решение 2 |
"""

GOOD = HUMAN + "\n---\n" + AGENT


def _write(tmp_path: Path, body: str) -> Path:
    path = tmp_path / "spec.md"
    path.write_text(body, encoding="utf-8")
    return path


def _run(path: Path) -> "subprocess.CompletedProcess[str]":
    return subprocess.run(
        [sys.executable, str(CHECK), str(path)], capture_output=True, text=True, check=False
    )


def test_good_spec_passes(tmp_path: Path) -> None:
    done = _run(_write(tmp_path, GOOD))
    assert done.returncode == 0, done.stdout + done.stderr


def test_decision_not_referenced_fails(tmp_path: Path) -> None:
    agent = AGENT.replace("| Пропуск вместо нуля | Решение 2 |", "| Пропуск вместо нуля | — |")
    agent = agent.replace("Решение 1, Решение 2", "Решение 1")
    done = _run(_write(tmp_path, HUMAN + "\n---\n" + agent))
    assert done.returncode == 1
    assert "Решение 2" in done.stdout


def test_verbatim_paragraph_fails(tmp_path: Path) -> None:
    copied = (
        "Раздел ведёт зачтённый длительный простой в минутах на смену, потому что на нём "
        "держатся статусы и рейтинги, а активность остаётся справочным числом без нормы и цвета."
    )
    done = _run(_write(tmp_path, HUMAN + "\n---\n" + AGENT + "\n" + copied + "\n"))
    assert done.returncode == 1
    assert "дословно" in done.stdout


def test_paraphrase_passes(tmp_path: Path) -> None:
    retold = "Ведущая величина берётся из Решение 1; поле `metric` в ответе ручки."
    done = _run(_write(tmp_path, HUMAN + "\n---\n" + AGENT + "\n" + retold + "\n"))
    assert done.returncode == 0, done.stdout + done.stderr


def test_short_repeated_line_passes(tmp_path: Path) -> None:
    """Короткий кусок (< 12 слов) повторять можно — это заголовок или подпись, не пересказ."""
    done = _run(_write(tmp_path, HUMAN + "\n---\n" + AGENT + "\nВедущая метрика Решение 1.\n"))
    assert done.returncode == 0, done.stdout + done.stderr


def test_missing_separator_fails(tmp_path: Path) -> None:
    done = _run(_write(tmp_path, HUMAN + "\n" + AGENT))
    assert done.returncode == 1
    assert "черт" in done.stdout


def test_no_decisions_fails(tmp_path: Path) -> None:
    done = _run(_write(tmp_path, "# Спека\n\n## Зачем это\n\nТекст.\n\n---\n\n## Слои\n"))
    assert done.returncode == 1
    assert "Решение" in done.stdout


def test_missing_file_fails(tmp_path: Path) -> None:
    done = _run(tmp_path / "нет.md")
    assert done.returncode == 1
    assert "нет" in done.stdout.lower() + done.stderr.lower()


@pytest.mark.parametrize("numbers", [("1", "2"), ("7", "12")])
def test_reference_matching_is_exact(tmp_path: Path, numbers: tuple[str, str]) -> None:
    """`Решение 1` не считается упоминанием `Решение 12` и наоборот."""
    first, second = numbers
    human = (
        f"# Спека\n\n## Решение {first}. А\n\nТекст решения.\n\n"
        f"## Решение {second}. Б\n\nДругой текст решения.\n"
    )
    agent = f"\n| Критерий | Решение {second} |\n"
    done = _run(_write(tmp_path, human + "\n---\n" + agent))
    assert done.returncode == 1
    assert f"Решение {first}" in done.stdout
