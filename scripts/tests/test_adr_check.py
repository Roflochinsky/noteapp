"""Тесты scripts/adr-check.py — восемь проверок готовности ADR (имена `ГГГГ-ММ-ДД-slug.md`)."""

import subprocess
import sys
from pathlib import Path

import pytest

CHECK = Path(__file__).resolve().parent.parent / "adr-check.py"

BODY = """# ADR: имя файла заметки с секундами

- Status: **Proposed** — ждёт подписи владельца
- Date: 2026-09-05
- Owners: владелец
- Спека: `docs/specs/2026-09-05-note-name.md`

## Почему это ADR, а не просто задача

Тема из списка гейта: формат заметки `.md`, имена файлов.

## Проблема

Две записи в одну минуту делят один путь в inbox/, вторая затирает первую.

## Решения

### Решение 1. Имя файла заметки несёт секунды

В контексте имён файлов заметок, сталкиваясь с тем, что две записи в одну минуту получают один
путь, мы выбрали добавить секунды в имя и отвергли суффикс-счётчик, чтобы достичь
уникальности без второго запроса в репо, принимая, что старые имена остаются без секунд.

## Что этот ADR НЕ меняет

Раскладку папок репо заметок и frontmatter.

## Чем подтверждается

Тест `app/src/test/kotlin/com/roflochinsky/noteapp/pipeline/RawNoteTest.kt` и смоук на
тестовом репо.

## Последствия и принятые риски

- Плюс: коллизии по минуте исчезают.
- Имена в репо разной длины — риск принят, владелец подтвердил.

## Откат

Вернуть прежний формат имени в RawNote.fileName; уже записанные файлы не трогать.
"""

OLD = """# ADR: первый

- Status: Accepted
- Date: 2026-04-25
- Owners: владелец

## Решения

### Решение 1. Что-то

В контексте, сталкиваясь с, мы выбрали, чтобы достичь, принимая, что.
"""


def _vcs(cwd: Path, *args: str) -> str:
    done = subprocess.run(["git", *args], cwd=cwd, capture_output=True, text=True, check=True)
    return done.stdout


@pytest.fixture
def repo(tmp_path: Path) -> Path:
    root = tmp_path / "repo"
    (root / "docs" / "adr").mkdir(parents=True)
    (root / "docs" / "adr" / "2026-04-25-first.md").write_text(OLD, encoding="utf-8")
    _vcs(tmp_path, "init", "-q", "-b", "main", str(root))
    _vcs(root, "config", "user.email", "t@example.com")
    _vcs(root, "config", "user.name", "t")
    _vcs(root, "add", "-A")
    _vcs(root, "commit", "-qm", "init")
    return root


def _put(repo: Path, body: str, name: str = "2026-09-05-note-name-seconds.md") -> Path:
    path = repo / "docs" / "adr" / name
    path.write_text(body, encoding="utf-8")
    return path


def _run(path: Path) -> "subprocess.CompletedProcess[str]":
    return subprocess.run(
        [sys.executable, str(CHECK), str(path)], capture_output=True, text=True, check=False
    )


def test_good_adr_passes(repo: Path) -> None:
    done = _run(_put(repo, BODY))
    assert done.returncode == 0, done.stdout + done.stderr


@pytest.mark.parametrize("name", ["note-name.md", "0002-note-name.md", "2026-09-05-Имя.md"])
def test_bad_filename_fails(repo: Path, name: str) -> None:
    done = _run(_put(repo, BODY, name=name))
    assert done.returncode == 1
    assert "имя" in done.stdout


@pytest.mark.parametrize("field", ["Date", "Owners", "Спека"])
def test_missing_header_field_fails(repo: Path, field: str) -> None:
    body = "\n".join(line for line in BODY.splitlines() if not line.startswith(f"- {field}"))
    done = _run(_put(repo, body + "\n"))
    assert done.returncode == 1
    assert field in done.stdout


def test_status_outside_closed_list_fails(repo: Path) -> None:
    body = BODY.replace("- Status: **Proposed** — ждёт подписи владельца", "- Status: черновик")
    done = _run(_put(repo, body))
    assert done.returncode == 1
    assert "Status" in done.stdout


def test_non_iso_date_fails(repo: Path) -> None:
    body = BODY.replace("- Date: 2026-09-05", "- Date: 5 сентября 2026")
    done = _run(_put(repo, body))
    assert done.returncode == 1
    assert "Date" in done.stdout


@pytest.mark.parametrize(
    "phrase", ["В контексте", "сталкиваясь с", "мы выбрали", "чтобы достичь", "принимая, что"]
)
def test_missing_y_link_fails(repo: Path, phrase: str) -> None:
    body = BODY.replace(phrase, "нечто")
    done = _run(_put(repo, body))
    assert done.returncode == 1
    assert "связк" in done.stdout


def test_consequences_without_negative_fails(repo: Path) -> None:
    body = BODY.replace(
        "- Имена в репо разной длины — риск принят, владелец подтвердил.",
        "- Ещё один плюс: имена стали точнее.",
    )
    done = _run(_put(repo, body))
    assert done.returncode == 1
    assert "отрицательн" in done.stdout


@pytest.mark.parametrize(
    "heading",
    ["## Что этот ADR НЕ меняет", "## Чем подтверждается", "## Откат", "## Последствия"],
)
def test_missing_section_fails(repo: Path, heading: str) -> None:
    body = BODY.replace(heading, "## Прочее")
    done = _run(_put(repo, body))
    assert done.returncode == 1


@pytest.mark.parametrize(
    "text", ["Проверим глазами, когда выкатим.", "Посмотрим `глазами` на экран."]
)
def test_verification_without_a_named_check_fails(repo: Path, text: str) -> None:
    body = BODY.replace(
        "Тест `app/src/test/kotlin/com/roflochinsky/noteapp/pipeline/RawNoteTest.kt` "
        "и смоук на\nтестовом репо.",
        text,
    )
    done = _run(_put(repo, body))
    assert done.returncode == 1
    assert "подтверждается" in done.stdout


def test_index_without_the_adr_fails_and_with_it_passes(repo: Path) -> None:
    index = repo / "docs" / "adr" / "README.md"
    index.write_text("# ADR\n\n- 2026-04-25-first.md\n", encoding="utf-8")
    done = _run(_put(repo, BODY))
    assert done.returncode == 1
    assert "индекс" in done.stdout
    index.write_text("# ADR\n\n- 2026-04-25-first.md\n- 2026-09-05-note-name-seconds.md\n", "utf-8")
    done = _run(_put(repo, BODY))
    assert done.returncode == 0, done.stdout


def test_accepted_adr_edited_in_body_fails(repo: Path) -> None:
    path = _put(repo, BODY.replace("**Proposed** — ждёт подписи владельца", "Accepted"))
    _vcs(repo, "add", "-A")
    _vcs(repo, "commit", "-qm", "adr")
    path.write_text(
        path.read_text(encoding="utf-8").replace(
            "Вернуть прежний формат имени", "Вернуть прежний формат имени и папки"
        ),
        encoding="utf-8",
    )
    done = _run(path)
    assert done.returncode == 1
    assert "неизменяем" in done.stdout


def test_accepted_adr_edited_only_in_header_passes(repo: Path) -> None:
    path = _put(repo, BODY.replace("**Proposed** — ждёт подписи владельца", "Accepted"))
    _vcs(repo, "add", "-A")
    _vcs(repo, "commit", "-qm", "adr")
    path.write_text(
        path.read_text(encoding="utf-8").replace(
            "- Status: Accepted", "- Status: Superseded by ADR 2026-10-01-note-name-v2"
        ),
        encoding="utf-8",
    )
    done = _run(path)
    assert done.returncode == 0, done.stdout + done.stderr


def test_missing_file_fails(tmp_path: Path) -> None:
    done = _run(tmp_path / "нет.md")
    assert done.returncode == 1


@pytest.mark.parametrize(
    "line",
    [
        "- Status: Accepted (подпись владельца 2026-09-05)",
        "- Status: **Accepted** — подписан владельцем",
        "- Status: Superseded by ADR 2026-10-01-x",
        "- **Status:** Proposed",
        "| **Статус** | Accepted |",
        "- Статус: принято — владелец, 2026-09-05",
        "- Статус: предложено",
        "- Status / Статус: Accepted / Принято",
    ],
)
def test_status_is_read_by_prefix_in_both_languages(repo: Path, line: str) -> None:
    body = BODY.replace("- Status: **Proposed** — ждёт подписи владельца", line)
    done = _run(_put(repo, body))
    assert done.returncode == 0, done.stdout


def test_russian_header_names_are_read(repo: Path) -> None:
    body = BODY.replace("- Date: 2026-09-05", "- Дата: 2026-09-05").replace(
        "- Owners: владелец", "- Владелец: Никита"
    )
    done = _run(_put(repo, body))
    assert done.returncode == 0, done.stdout
