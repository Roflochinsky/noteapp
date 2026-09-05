#!/usr/bin/env python3
"""Проверяемые критерии готовности ADR (скилл adr; перенесено из workwatch, форма 2 C6).

    scripts/adr-check.py <docs/adr/ГГГГ-ММ-ДД-slug.md>

Восемь проверок: имя файла `ГГГГ-ММ-ДД-slug.md`; шапка (`Status` из закрытого списка,
`Date`, `Owners`, `Спека:`); пять связок Y-строки у каждого решения; отрицательный пункт в
последствиях; раздел «Чем подтверждается» называет проверку; разделы «Что этот ADR НЕ меняет»
и «Откат» существуют; неизменяемость принятого ADR; индекс (если он есть) упоминает файл.

Статусы принимаются и по-русски: предложено / принято / отложено / заменён.
Коды выхода: 0 — ADR готов; 1 — есть нарушение, каждое напечатано строкой.
"""

from __future__ import annotations

import argparse
import re
import shutil
import subprocess
import sys
from pathlib import Path

GIT = shutil.which("git") or "git"

NAME = re.compile(r"^\d{4}-\d{2}-\d{2}-[a-z0-9]+(?:-[a-z0-9]+)*\.md$")
SECTION = re.compile(r"^##\s+(.*?)\s*$")
DECISION = re.compile(r"^###\s+Решение\b")
ANY_HEADING = re.compile(r"^#{1,6}\s")
FIELD = re.compile(
    r"^\s*[-|]?\s*\**(?P<name>Status|Статус|Date|Дата|Owners|Владелец|Спека)\**"
    r"(?:\s*/\s*[^:|]+?)?\s*[:|]\s*(?P<value>.*?)\s*\|?\s*$"
)
HUNK = re.compile(r"^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@")
MARKDOWN = re.compile(r"[*`_]")

ALIASES = {"Статус": "Status", "Дата": "Date", "Владелец": "Owners"}
STATUSES = {
    "Accepted",
    "Proposed",
    "Deferred",
    "Blocked: business approval required",
    "Superseded",
}
RUSSIAN_STATUSES = {
    "принято": "Accepted",
    "предложено": "Proposed",
    "отложено": "Deferred",
    "заменён": "Superseded",
    "заменен": "Superseded",
}
Y_LINKS = ("в контексте", "сталкиваясь с", "мы выбрали", "чтобы достичь", "принимая, что")
NEGATIVE = ("риск принят", "минус", "ценой")
VERIFICATION = re.compile(
    r"\.kt\b|\.py\b|\.ya?ml\b|bin/gate|gradlew|actionlint|pytest|pre-commit|"
    r"смоук|стенд|тестов\w* репо|телефон|adb\b",
    re.IGNORECASE,
)
HEADER_FIELDS = re.compile(
    r"^\s*[-|]?\s*\**(Status|Статус|Date|Дата|Owners|Владелец|Спека|Superseded by|Заменён)\**"
    r"(?:\s*/\s*[^:|]+?)?\s*[:|]"
)


def _git(cwd: Path, *args: str) -> tuple[int, str]:
    done = subprocess.run(  # noqa: S603
        [GIT, *args], cwd=cwd, capture_output=True, text=True, check=False
    )
    return done.returncode, done.stdout


def repo_root(path: Path) -> Path:
    code, out = _git(path.parent, "rev-parse", "--show-toplevel")
    return Path(out.strip()) if code == 0 and out.strip() else path.parent.parent.parent


def header_end(lines: list[str]) -> int:
    """Номер последней строки шапки (1-based); шапка — всё до первого `## `."""
    for index, line in enumerate(lines):
        if SECTION.match(line):
            return index
    return len(lines)


def sections(lines: list[str]) -> dict[str, list[str]]:
    found: dict[str, list[str]] = {}
    body: list[str] | None = None
    for line in lines:
        head = SECTION.match(line)
        if head:
            body = []
            found[head.group(1)] = body
            continue
        if body is not None:
            body.append(line)
    return found


def decision_blocks(lines: list[str]) -> list[list[str]]:
    found: list[list[str]] = []
    body: list[str] | None = None
    for line in lines:
        if DECISION.match(line):
            body = []
            found.append(body)
            continue
        if ANY_HEADING.match(line):
            body = None
            continue
        if body is not None:
            body.append(line)
    return found


def flatten(lines: list[str]) -> str:
    return " ".join(MARKDOWN.sub("", " ".join(lines)).lower().split())


def section_named(found: dict[str, list[str]], needle: str) -> list[str] | None:
    for title, body in found.items():
        if needle.lower() in title.lower():
            return body
    return None


def check_name(path: Path) -> list[str]:
    if not NAME.match(path.name):
        return [f"имя файла не в форме `ГГГГ-ММ-ДД-slug.md`: {path.name}"]
    return []


def header_fields(lines: list[str]) -> dict[str, str]:
    """Шапка бывает списком (`- Status: …`) и таблицей (`| **Статус** | … |`) — обе читаются."""
    fields: dict[str, str] = {}
    for line in lines[: header_end(lines)]:
        found = FIELD.match(line)
        if found:
            name = ALIASES.get(found.group("name"), found.group("name"))
            fields.setdefault(name, found.group("value"))
    return fields


def header_status(lines: list[str]) -> str:
    """Статус — ПРЕФИКС строки: `Accepted (…)`, `принято — …`, `Superseded by ADR …`."""
    raw = MARKDOWN.sub("", header_fields(lines).get("Status", ""))
    head = re.split(r"[—(;]|\s/\s", raw)[0].strip()
    for known in sorted(STATUSES, key=len, reverse=True):
        if head == known or head.startswith(f"{known} "):
            return known
    first = head.split(" ")[0].lower() if head else ""
    return RUSSIAN_STATUSES.get(first, head)


def check_header(lines: list[str]) -> list[str]:
    fields = header_fields(lines)
    problems: list[str] = []
    status = header_status(lines)
    if not status:
        problems.append("в шапке нет строки `Status:`")
    elif status not in STATUSES:
        problems.append(f"Status «{status}» вне закрытого списка: {', '.join(sorted(STATUSES))}")
    date = fields.get("Date", "").strip()
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", date):
        problems.append(f"Date не в форме ISO ГГГГ-ММ-ДД: «{date or 'нет строки'}»")
    for name in ("Owners", "Спека"):
        if not fields.get(name, "").strip():
            problems.append(f"в шапке нет непустой строки `{name}:`")
    return problems


def check_decisions(lines: list[str]) -> list[str]:
    blocks = decision_blocks(lines)
    if not blocks:
        return ["нет ни одного `### Решение` — решать нечего"]
    problems = []
    for index, block in enumerate(blocks, start=1):
        text = flatten(block)
        missing = [link for link in Y_LINKS if link not in text]
        if missing:
            problems.append(
                f"Решение {index}: в Y-строке нет связк(и) {', '.join(f'«{m}»' for m in missing)}"
            )
    return problems


def check_sections(lines: list[str]) -> list[str]:
    found = sections(lines)
    problems = []

    consequences = section_named(found, "Последствия")
    if consequences is None:
        problems.append("нет раздела «Последствия и принятые риски»")
    elif not any(mark in flatten(consequences) for mark in NEGATIVE):
        problems.append(
            "«Последствия и принятые риски» без отрицательного пункта "
            f"(нужен маркер {', '.join(f'«{m}»' for m in NEGATIVE)})"
        )

    verification = section_named(found, "Чем подтверждается")
    if verification is None:
        problems.append("нет раздела «Чем подтверждается»")
    elif not VERIFICATION.search("\n".join(verification)):
        problems.append(
            "«Чем подтверждается» не называет проверку — нужен файл теста, команда гейта, "
            "смоук на тестовом репо или сценарий на телефоне"
        )

    if section_named(found, "НЕ меняет") is None:
        problems.append("нет раздела «Что этот ADR НЕ меняет»")
    if section_named(found, "Откат") is None:
        problems.append("нет раздела «Откат»")
    return problems


def check_immutability(path: Path, repo: Path) -> list[str]:
    rel = path.resolve().relative_to(repo.resolve()).as_posix()
    code, committed = _git(repo, "show", f"HEAD:{rel}")
    if code != 0:
        return []
    old_lines = committed.splitlines()
    if header_status(old_lines) != "Accepted":
        return []

    limit_old = header_end(old_lines)
    new_lines = path.read_text(encoding="utf-8").splitlines()
    limit_new = header_end(new_lines)
    code, diff = _git(repo, "diff", "HEAD", "--", rel)
    if code != 0 or not diff.strip():
        return []

    old_no = new_no = 0
    touched: list[str] = []
    for line in diff.splitlines():
        hunk = HUNK.match(line)
        if hunk:
            old_no, new_no = int(hunk.group(1)), int(hunk.group(2))
            continue
        if old_no == 0 or line.startswith(("+++", "---")):
            continue
        if line.startswith("-"):
            if old_no > limit_old and not HEADER_FIELDS.match(line[1:]):
                touched.append(f"−{old_no}: {line[1:].strip()}")
            old_no += 1
        elif line.startswith("+"):
            if new_no > limit_new and not HEADER_FIELDS.match(line[1:]):
                touched.append(f"+{new_no}: {line[1:].strip()}")
            new_no += 1
        else:
            old_no += 1
            new_no += 1
    if touched:
        shown = "; ".join(touched[:3])
        return [
            "принятый ADR неизменяем: правки вне шапки требуют нового ADR со `Superseded by` "
            f"({shown})"
        ]
    return []


def check_index(path: Path, repo: Path) -> list[str]:
    """Индекса у noteapp нет; появится — обязан упоминать файл ADR."""
    for candidate in (
        path.parent / "README.md",
        path.parent / "ADR_INDEX.md",
        repo / "docs" / "ADR_INDEX.md",
    ):
        if candidate.is_file():
            if path.name in candidate.read_text(encoding="utf-8"):
                return []
            return [f"индекс ADR ({candidate.name}) не упоминает {path.name}"]
    return []


def problems(path: Path) -> list[str]:
    if not path.is_file():
        return [f"файла ADR нет: {path}"]
    repo = repo_root(path)
    lines = path.read_text(encoding="utf-8").splitlines()
    return [
        *check_name(path),
        *check_header(lines),
        *check_decisions(lines),
        *check_sections(lines),
        *check_immutability(path, repo),
        *check_index(path, repo),
    ]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Критерии готовности ADR")
    parser.add_argument("adr", help="путь к файлу ADR")
    args = parser.parse_args(argv)

    found = problems(Path(args.adr))
    for line in found:
        print(f"ОТКАЗ: {line}")
    if found:
        return 1
    print("adr-check: восемь проверок пройдены")
    return 0


if __name__ == "__main__":
    sys.exit(main())
