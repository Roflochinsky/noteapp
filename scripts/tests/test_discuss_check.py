"""Тесты scripts/discuss-check.py — восемь проверок выхода обсуждения (словарь — CONTEXT.md)."""

import subprocess
import sys
from pathlib import Path

import pytest

CHECK = Path(__file__).resolve().parent.parent / "discuss-check.py"

PROTOCOL = """# Обсуждение: динамика раздела

Дата: 2026-09-01
Цель: показать руководителю изменение между двумя периодами
Термины: зачтённый длительный простой, дисциплина руководителя
ADR: docs/adr/2026-09-01-dynamics.md

## Цель

Понять, есть ли результат пилота, и чем это показать на экране.

## Раунд 1 (10:12)

### Q1. Какая метрика ведёт раздел?

Рекомендация: зачтённый длительный простой — на нём держатся статусы.

**Ответ:** зачтённый длительный простой; активность остаётся справочной.

## Раунд 2 (11:40)

### Q2. Что делать с несопоставимыми строками?

**Ответ:** показывать прочерк, нулём не подставлять.

## Решение 1. Ведущая метрика — зачтённый длительный простой

Автор: владелец
Увидишь: открыл раздел → в шапке минуты на смену

## Решение 2. Пропуск вместо нуля

Автор: ведущий
Подтверждено владельцем: да
Увидишь: у сотрудника нет смен в июле → в таблице прочерк

## Пока не уточнено

- пороги зоны шума в минутах

## Вне скоупа

- карта маршрутов — отдельный эпик, данных пока нет
"""


@pytest.fixture
def repo(tmp_path: Path) -> Path:
    root = tmp_path / "repo"
    (root / "docs" / "adr").mkdir(parents=True)
    (root / "CONTEXT.md").write_text(
        "# noteapp — глоссарий\n\n- **зачтённый длительный простой** — минуты на смену.\n"
        "- **дисциплина руководителя** — семейство метрик.\n",
        encoding="utf-8",
    )
    (root / "docs" / "adr" / "2026-09-01-dynamics.md").write_text("# ADR\n", encoding="utf-8")
    (root / "docs" / "discussions").mkdir()
    return root


def _write(repo: Path, body: str) -> Path:
    path = repo / "docs" / "discussions" / "2026-09-01-dynamics.md"
    path.write_text(body, encoding="utf-8")
    return path


def _run(repo: Path, path: Path) -> "subprocess.CompletedProcess[str]":
    return subprocess.run(
        [sys.executable, str(CHECK), str(path), "--repo", str(repo)],
        capture_output=True,
        text=True,
        check=False,
    )


def test_good_protocol_passes(repo: Path) -> None:
    done = _run(repo, _write(repo, PROTOCOL))
    assert done.returncode == 0, done.stdout + done.stderr


def test_question_without_answer_fails(repo: Path) -> None:
    body = PROTOCOL.replace("**Ответ:** показывать прочерк, нулём не подставлять.\n", "")
    done = _run(repo, _write(repo, body))
    assert done.returncode == 1
    assert "без ответа" in done.stdout


def test_empty_answer_fails(repo: Path) -> None:
    body = PROTOCOL.replace("**Ответ:** показывать прочерк, нулём не подставлять.", "**Ответ:**")
    done = _run(repo, _write(repo, body))
    assert done.returncode == 1
    assert "без ответа" in done.stdout


def test_placeholder_fails(repo: Path) -> None:
    body = PROTOCOL.replace("- пороги зоны шума в минутах", "- пороги зоны шума — уточним позже")
    done = _run(repo, _write(repo, body))
    assert done.returncode == 1
    assert "плейсхолдер" in done.stdout


def test_decision_without_visible_criterion_fails(repo: Path) -> None:
    body = PROTOCOL.replace("Увидишь: открыл раздел → в шапке минуты на смену\n", "")
    done = _run(repo, _write(repo, body))
    assert done.returncode == 1
    assert "Увидишь" in done.stdout


def test_criterion_without_arrow_fails(repo: Path) -> None:
    body = PROTOCOL.replace(
        "Увидишь: открыл раздел → в шапке минуты на смену",
        "Увидишь: в шапке будут минуты на смену",
    )
    done = _run(repo, _write(repo, body))
    assert done.returncode == 1
    assert "Увидишь" in done.stdout


def test_term_missing_from_dictionaries_fails(repo: Path) -> None:
    body = PROTOCOL.replace("Термины: зачтённый длительный простой", "Термины: сдвиг метрики")
    done = _run(repo, _write(repo, body))
    assert done.returncode == 1
    assert "словар" in done.stdout


def test_adr_path_that_does_not_exist_fails(repo: Path) -> None:
    body = PROTOCOL.replace("ADR: docs/adr/2026-09-01-dynamics.md", "ADR: docs/adr/9999-нет.md")
    done = _run(repo, _write(repo, body))
    assert done.returncode == 1
    assert "ADR" in done.stdout


def test_adr_not_touched_is_accepted(repo: Path) -> None:
    body = PROTOCOL.replace("ADR: docs/adr/2026-09-01-dynamics.md", "ADR: не затронут")
    done = _run(repo, _write(repo, body))
    assert done.returncode == 0, done.stdout + done.stderr


def test_missing_adr_line_fails(repo: Path) -> None:
    body = PROTOCOL.replace("ADR: docs/adr/2026-09-01-dynamics.md\n", "")
    done = _run(repo, _write(repo, body))
    assert done.returncode == 1
    assert "ADR" in done.stdout


def test_decision_without_author_fails(repo: Path) -> None:
    body = PROTOCOL.replace("Автор: владелец\n", "")
    done = _run(repo, _write(repo, body))
    assert done.returncode == 1
    assert "Автор" in done.stdout


def test_lead_decision_without_owner_confirmation_fails(repo: Path) -> None:
    body = PROTOCOL.replace("Подтверждено владельцем: да\n", "")
    done = _run(repo, _write(repo, body))
    assert done.returncode == 1
    assert "владельцем" in done.stdout


def test_empty_map_section_fails(repo: Path) -> None:
    body = PROTOCOL.replace("- карта маршрутов — отдельный эпик, данных пока нет\n", "")
    done = _run(repo, _write(repo, body))
    assert done.returncode == 1
    assert "Вне скоупа" in done.stdout


def test_empty_map_section_with_reason_passes(repo: Path) -> None:
    body = PROTOCOL.replace(
        "- карта маршрутов — отдельный эпик, данных пока нет",
        "пусто, потому что вся тема уместилась в цель обсуждения",
    )
    done = _run(repo, _write(repo, body))
    assert done.returncode == 0, done.stdout + done.stderr


def test_single_round_timestamp_fails(repo: Path) -> None:
    """Одна метка на весь протокол — признак, что он написан в конце, а не по ходу."""
    body = PROTOCOL.replace("## Раунд 2 (11:40)", "## Раунд 2 (10:12)")
    done = _run(repo, _write(repo, body))
    assert done.returncode == 1
    assert "по ходу" in done.stdout


def test_round_without_timestamp_fails(repo: Path) -> None:
    body = PROTOCOL.replace("## Раунд 2 (11:40)", "## Раунд 2")
    done = _run(repo, _write(repo, body))
    assert done.returncode == 1
    assert "метк" in done.stdout


def test_missing_file_fails(repo: Path) -> None:
    done = _run(repo, repo / "docs" / "discussions" / "нет.md")
    assert done.returncode == 1


def test_protocol_without_rounds_fails(repo: Path) -> None:
    """Ноль раундов — обсуждения не было: метки времени сравнивать не с чем."""
    body = PROTOCOL.replace("## Раунд 1 (10:12)", "## Вопросы").replace(
        "## Раунд 2 (11:40)", "## Ещё вопросы"
    )
    done = _run(repo, _write(repo, body))
    assert done.returncode == 1
    assert "Раунд" in done.stdout


def test_the_words_by_default_are_not_a_placeholder(repo: Path) -> None:
    """«по умолчанию» — обычная русская фраза, а не незаполненное значение (A4 маркера не знает)."""
    body = PROTOCOL.replace(
        "**Ответ:** показывать прочерк, нулём не подставлять.",
        "**Ответ:** показывать прочерк — это и есть поведение по умолчанию.",
    )
    done = _run(repo, _write(repo, body))
    assert done.returncode == 0, done.stdout
