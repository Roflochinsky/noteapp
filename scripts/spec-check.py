#!/usr/bin/env python3
"""Проверка правила синхронизации двух частей спеки (скилл to-spec; из workwatch, форма 2 C2).

    scripts/spec-check.py <спека.md>

Решение живёт ТОЛЬКО в человеческой части и имеет номер; агентная часть ссылается номером
и не пересказывает. Проверяется три вещи: части разделены чертой `---`; каждый номер решения
встречается в агентной части хотя бы раз; ни один абзац решения не скопирован в агентную
часть дословно (сверка по нормализованным 8-граммам).

Коды выхода: 0 — правило соблюдено; 1 — есть нарушение, каждое напечатано строкой.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

MIN_WORDS = 12
GRAM = 8

HEADING = re.compile(r"^##\s+Решение\s+(\d+)\b")
SECTION = re.compile(r"^##\s")
WORD = re.compile(r"[^\w]+", re.UNICODE)


def words(text: str) -> list[str]:
    return WORD.sub(" ", text.lower()).split()


def grams(tokens: list[str], size: int = GRAM) -> set[tuple[str, ...]]:
    return {tuple(tokens[i : i + size]) for i in range(len(tokens) - size + 1)}


def split_parts(lines: list[str]) -> tuple[list[str], list[str], str]:
    """Черта — первая строка `---` после последнего заголовка решения."""
    rules = [i for i, line in enumerate(lines) if line.strip() == "---"]
    if not rules:
        return [], [], "части спеки не разделены чертой `---`"
    heads = [i for i, line in enumerate(lines) if HEADING.match(line)]
    if not heads:
        return [], [], "в человеческой части нет ни одного `## Решение N`"
    after = [i for i in rules if i > heads[-1]]
    if not after:
        return [], [], "черта `---` стоит до последнего решения, а не после человеческой части"
    cut = after[0]
    return lines[:cut], lines[cut + 1 :], ""


def decisions(part: list[str]) -> list[tuple[str, list[str]]]:
    """Блоки решений: от заголовка `## Решение N` до следующего заголовка второго уровня."""
    found: list[tuple[str, list[str]]] = []
    current: list[str] | None = None
    for line in part:
        head = HEADING.match(line)
        if head:
            current = []
            found.append((head.group(1), current))
            continue
        if SECTION.match(line):
            current = None
            continue
        if current is not None:
            current.append(line)
    return found


def paragraphs(block: list[str]) -> list[str]:
    chunks: list[str] = []
    buffer: list[str] = []
    for line in [*block, ""]:
        if line.strip():
            buffer.append(line.strip())
        elif buffer:
            chunks.append(" ".join(buffer))
            buffer = []
    return chunks


def problems(path: Path) -> list[str]:
    if not path.is_file():
        return [f"файла спеки нет: {path}"]
    lines = path.read_text(encoding="utf-8").splitlines()
    human, agent, failure = split_parts(lines)
    if failure:
        return [failure]

    agent_text = "\n".join(agent)
    agent_grams = grams(words(agent_text))
    found: list[str] = []

    for number, block in decisions(human):
        if not re.search(rf"\bРешение\s+{number}\b", agent_text):
            found.append(f"Решение {number} не упомянуто в агентной части — его никто не реализует")
        for chunk in paragraphs(block):
            tokens = words(chunk)
            if len(tokens) < MIN_WORDS:
                continue
            overlap = grams(tokens) & agent_grams
            if overlap:
                echo = " ".join(sorted(overlap)[0])
                found.append(
                    f"Решение {number}: абзац пересказан в агентной части дословно — «{echo}…»"
                )
                break
    return found


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Проверка спеки в двух формах")
    parser.add_argument("spec", help="путь к файлу спеки")
    args = parser.parse_args(argv)

    found = problems(Path(args.spec))
    for line in found:
        print(f"ОТКАЗ: {line}")
    if found:
        return 1
    print("spec-check: части разведены, каждое решение упомянуто номером, дублей нет")
    return 0


if __name__ == "__main__":
    sys.exit(main())
