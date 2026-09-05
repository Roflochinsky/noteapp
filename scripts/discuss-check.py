#!/usr/bin/env python3
"""Объективные критерии выхода стадии обсуждения (скилл discuss; из workwatch, форма 2 A4).

    scripts/discuss-check.py <протокол.md> [--repo <корень репозитория>]

Восемь проверок, каждая — отказ, а не пожелание: ни одного вопроса без ответа; ни одного
плейсхолдера; у каждого решения `Увидишь: действие → результат`; каждый термин из шапки
найден в словаре; в шапке `ADR: <путь> | не затронут`; у каждого решения автор, а у решений
ведущего — подтверждение владельца; три раздела карты непусты; раунды есть и меток времени больше
одной (протокол писался по ходу, а не в конце).

Коды выхода: 0 — протокол готов; 1 — есть нарушение, каждое напечатано строкой.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

SECTION = re.compile(r"^##\s+(.*?)\s*$")
DECISION = re.compile(r"^##\s+Решение\s+(\d+)")
ROUND = re.compile(r"^##\s+Раунд\s+(\d+)\s*(?:\((?P<stamp>[^)]*)\))?")
QUESTION = re.compile(r"^###\s+Q")
ANSWER = re.compile(r"^\*\*Ответ:\*\*\s*(?P<body>.*)$")
AUTHOR = re.compile(r"^Автор:\s*(?P<who>.+?)\s*$")
CONFIRMED = re.compile(r"^Подтверждено\s+владельцем:\s*(?P<value>.+?)\s*$", re.IGNORECASE)
SEEN = re.compile(r"^Увидишь:\s*(?P<body>.+?)\s*$")
HEADER_FIELD = re.compile(r"^(?P<name>[A-Za-zА-Яа-яЁё ]+):\s*(?P<value>.*?)\s*$")

PLACEHOLDERS = ("tbd", "todo", "уточним позже", "решим потом", "???")
DICTIONARIES = ("CONTEXT.md",)
MAP_SECTIONS = ("Цель", "Пока не уточнено", "Вне скоупа")
EMPTYISH = {"", "—", "-", "нет", "пусто", "n/a", "тбд"}


def header_fields(lines: list[str]) -> dict[str, str]:
    """Шапка — всё до первого заголовка второго уровня."""
    fields: dict[str, str] = {}
    for line in lines:
        if SECTION.match(line):
            break
        found = HEADER_FIELD.match(line.strip())
        if found:
            fields[found.group("name").strip()] = found.group("value").strip()
    return fields


def blocks(lines: list[str]) -> list[tuple[str, list[str]]]:
    """Разделы второго уровня: заголовок и его строки."""
    found: list[tuple[str, list[str]]] = []
    body: list[str] | None = None
    for line in lines:
        head = SECTION.match(line)
        if head:
            body = []
            found.append((head.group(1), body))
            continue
        if body is not None:
            body.append(line)
    return found


def unanswered(lines: list[str]) -> list[str]:
    """Вопрос закрыт, если до следующего вопроса или раздела встретился непустой ответ."""
    found: list[str] = []
    pending: str | None = None
    for line in lines:
        answer = ANSWER.match(line.strip())
        if answer and answer.group("body").strip():
            pending = None
            continue
        if QUESTION.match(line) or SECTION.match(line):
            if pending:
                found.append(pending)
            pending = line.strip() if QUESTION.match(line) else None
    if pending:
        found.append(pending)
    return found


def placeholders(lines: list[str]) -> list[str]:
    found: list[str] = []
    for line in lines:
        low = line.lower()
        for mark in PLACEHOLDERS:
            if mark in low:
                found.append(f"«{line.strip()}» — плейсхолдер «{mark}»")
                break
    return found


def _term_in_dictionaries(repo: Path, term: str) -> bool:
    for name in DICTIONARIES:
        path = repo / name
        if path.is_file() and term in path.read_text(encoding="utf-8"):
            return True
    return False


def check_terms(repo: Path, fields: dict[str, str]) -> list[str]:
    raw = fields.get("Термины", "").strip()
    if not raw:
        return []
    missing = [
        term.strip()
        for term in raw.split(",")
        if term.strip() and not _term_in_dictionaries(repo, term.strip())
    ]
    return [f"термин «{term}» не найден в словарях {' / '.join(DICTIONARIES)}" for term in missing]


def check_adr(repo: Path, fields: dict[str, str]) -> list[str]:
    value = fields.get("ADR", "").strip()
    if not value:
        return ["в шапке нет строки `ADR:` — гейт ADR не прогнан"]
    if value == "не затронут":
        return []
    if not (repo / value).exists():
        return [f"ADR-гейт: путь `{value}` не существует (или напиши ровно «не затронут»)"]
    return []


def check_decisions(sections: list[tuple[str, list[str]]]) -> list[str]:
    found: list[str] = []
    seen_any = False
    for title, body in sections:
        head = DECISION.match(f"## {title}")
        if not head:
            continue
        seen_any = True
        number = head.group(1)
        text = [line.strip() for line in body]
        criterion = next((SEEN.match(line) for line in text if SEEN.match(line)), None)
        if criterion is None:
            found.append(f"Решение {number}: нет строки «Увидишь: действие → результат»")
        elif "→" not in criterion.group("body") and "->" not in criterion.group("body"):
            found.append(f"Решение {number}: в строке «Увидишь» нет стрелки действие → результат")
        author = next((AUTHOR.match(line) for line in text if AUTHOR.match(line)), None)
        who = author.group("who").lower() if author else ""
        if who not in {"владелец", "ведущий"}:
            found.append(f"Решение {number}: нет строки «Автор: владелец» или «Автор: ведущий»")
        elif who == "ведущий":
            confirmed = next(
                (CONFIRMED.match(line) for line in text if CONFIRMED.match(line)), None
            )
            if confirmed is None or confirmed.group("value").lower() in {"нет", "no"}:
                found.append(
                    f"Решение {number}: автор «ведущий», но нет «Подтверждено владельцем: да»"
                )
    if not seen_any:
        found.append("в протоколе нет ни одного `## Решение N`")
    return found


def check_map(sections: list[tuple[str, list[str]]]) -> list[str]:
    found: list[str] = []
    for name in MAP_SECTIONS:
        body = next((b for title, b in sections if title.strip() == name), None)
        if body is None:
            found.append(f"раздела «{name}» нет — карта не заполнена")
            continue
        text = " ".join(line.strip() for line in body).strip()
        if text.lower() in EMPTYISH:
            found.append(f"раздел «{name}» пуст — либо заполни, либо «пусто, потому что …»")
    return found


def check_rounds(lines: list[str]) -> list[str]:
    found: list[str] = []
    stamps: list[str] = []
    seen = False
    for line in lines:
        head = ROUND.match(line)
        if not head:
            continue
        seen = True
        stamp = (head.group("stamp") or "").strip()
        if not stamp:
            found.append(f"у раунда {head.group(1)} нет временной метки в заголовке")
            continue
        stamps.append(stamp)
    if not seen:
        found.append("в протоколе нет ни одного `## Раунд N` — обсуждения не было")
    elif stamps and len(set(stamps)) < 2:
        found.append("временная метка раунда одна — протокол писался не по ходу, а в конце")
    return found


def problems(path: Path, repo: Path) -> list[str]:
    if not path.is_file():
        return [f"файла протокола нет: {path}"]
    lines = path.read_text(encoding="utf-8").splitlines()
    fields = header_fields(lines)
    sections = blocks(lines)

    found = [f"вопрос без ответа: {q}" for q in unanswered(lines)]
    found += placeholders(lines)
    found += check_decisions(sections)
    found += check_terms(repo, fields)
    found += check_adr(repo, fields)
    found += check_map(sections)
    found += check_rounds(lines)
    return found


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Критерии выхода стадии обсуждения")
    parser.add_argument("protocol", help="путь к протоколу обсуждения")
    parser.add_argument("--repo", default=".", help="корень репозитория (для словарей и ADR)")
    args = parser.parse_args(argv)

    found = problems(Path(args.protocol), Path(args.repo).resolve())
    for line in found:
        print(f"ОТКАЗ: {line}")
    if found:
        return 1
    print("discuss-check: восемь проверок пройдены")
    return 0


if __name__ == "__main__":
    sys.exit(main())
