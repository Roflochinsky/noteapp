#!/usr/bin/env python3
"""ready-slices.py — какие срезы эпика можно пустить ОДНОВРЕМЕННО.

Читает детей bd-эпика, вынимает из каждого строку «Трогает: …» (описание или заметки),
раскрывает фигурные скобки, приводит пути к корню репозитория и выдаёт максимум N срезов,
не пересекающихся по файлам. Про каждый отвергнутый говорит, ЧЕМ он занят.

bd вызывается только на чтение. Перенесено из workwatch (спека формы 2, E4); замки — noteapp.

  python3 scripts/ready-slices.py nikitatrubaev-0rk -n 2
"""

import argparse
import json
import os
import re
import shutil
import subprocess
import sys

# Общие ресурсы: путь -> имя замка. Два среза, взявшие ОДИН замок, нельзя пустить вместе
# даже в разных worktree — конфликт не в дереве, а в общем артефакте (docs/harness/epic.md).
SHARED_LOCKS = {
    r"^(app/)?build\.gradle\.kts$": "gradle-build",  # зависимости и версии — один файл
    r"^gradle/libs\.versions\.toml$": "gradle-build",
    r"^settings\.gradle\.kts$": "gradle-build",
    r"^config/detekt/": "detekt-config",
    r"^app/src/main/AndroidManifest\.xml$": "manifest",
    r"^app/src/test/resources/": "fixtures",  # фикстуры — общий ресурс (docs/harness/epic.md)
    r"^docs/examples/process-notes\.yml$": "action-contract",  # промпт и workflow Action
    r"^DESIGN\.md$": "design",
    r"^CONTEXT\.md$": "product-truth",
    r"^PRODUCT\.md$": "product-truth",
    r"^\.beads/": "beads",
    r"^docs/plans/[^/]+\.md$": "plan-file",  # сам файл плана; журналы срезов — свои файлы
    r"^docs/specs/": "spec-file",
    r"^docs/adr/": "adr",
}

# Догадка о корне для сокращённых путей («pipeline/Edit.kt», «ui/TasksScreen.kt»).
_MAIN = "app/src/main/kotlin/com/roflochinsky/noteapp/"
_TEST = "app/src/test/kotlin/com/roflochinsky/noteapp/"
PREFIX_HINTS = [
    ("pipeline/", _MAIN + "pipeline/"),
    ("ui/", _MAIN + "ui/"),
    ("assist/", _MAIN + "assist/"),
    ("test/pipeline/", _TEST + "pipeline/"),
    ("test/ui/", _TEST + "ui/"),
    ("test/", _TEST),
    ("kotlin/", "app/src/main/kotlin/"),
    ("resources/", "app/src/test/resources/"),
]


def expand_braces(tok):
    m = re.search(r"\{([^{}]*)\}", tok)
    if not m:
        return [tok]
    out = []
    for part in m.group(1).split(","):
        out += expand_braces(tok[: m.start()] + part.strip() + tok[m.end() :])
    return out


def normalize(tok, repo):
    tok = tok.strip().strip(",;`()").strip()
    # мусор из скобочных пояснений: путь обязан нести «/» или точку
    if not tok or tok.startswith("<") or not re.search(r"[/.]", tok):
        return []
    res = []
    for p in expand_braces(tok):
        p = p.lstrip("./")
        if os.path.exists(os.path.join(repo, p)):
            res.append(p)
            continue
        hit = None
        for pref, full in PREFIX_HINTS:
            if p.startswith(pref) and os.path.exists(os.path.join(repo, full + p[len(pref) :])):
                hit = full + p[len(pref) :]
                break
        res.append(hit or p)  # не нашли — держим как есть: ложный конфликт лучше пропуска
    return res


def touches(issue, repo):
    text = (issue.get("description") or "") + "\n" + (issue.get("notes") or "")
    files = []
    for line in text.splitlines():
        m = re.match(r"\s*\**Трогает:?\**\s*(.+)$", line)
        if m:
            files += [f for t in m.group(1).split() for f in normalize(t, repo)]
    return sorted(set(files))


def locks(files):
    out = set()
    for f in files:
        for rx, name in SHARED_LOCKS.items():
            if re.search(rx, f):
                out.add(name)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("epic")
    ap.add_argument("-C", "--repo", default=".")
    ap.add_argument("-n", "--max", type=int, default=2)
    a = ap.parse_args()
    repo = os.path.abspath(a.repo)

    # ВАЖНО: у bd флаг --directory даёт пустой список, базу он ищет от cwd. Запускаем с cwd=repo.
    bd = shutil.which("bd") or "bd"
    raw = subprocess.run(  # noqa: S603 — аргументы константные плюс id эпика
        [bd, "list", "--parent", a.epic, "--json"], capture_output=True, text=True, cwd=repo
    )
    if raw.returncode != 0:
        sys.exit(raw.stderr.strip() or "bd list упал")
    issues = json.loads(raw.stdout)
    rdy = subprocess.run(  # noqa: S603
        [bd, "ready", "--parent", a.epic, "--json"], capture_output=True, text=True, cwd=repo
    )
    ready_ids = {i["id"] for i in json.loads(rdy.stdout)} if rdy.returncode == 0 else set()

    cand, busy = [], []
    taken_files, taken_locks = set(), set()
    for i in issues:
        status = i.get("status")
        f = touches(i, repo)
        if status == "in_progress":  # взятый срез держит свои файлы и замки, в потолок не входит
            busy.append((i["id"], (i.get("title") or "")[:60], f))
            taken_files |= set(f)
            taken_locks |= locks(f)
            continue
        if status not in ("open", "blocked"):
            continue
        cand.append(
            (
                i["id"],
                i.get("priority", 9),
                f,
                locks(f),
                i["id"] in ready_ids,
                (i.get("title") or "")[:60],
                status,
            )
        )
    cand.sort(key=lambda c: (not c[4], c[1], c[0]))

    chosen, waiting = [], []
    for cid, _pri, f, lk, isready, title, status in cand:
        if not isready:
            why = (
                "status=blocked (снять — bd update --status=open)"
                if status == "blocked"
                else "ждёт зависимости (bd ready не отдаёт)"
            )
            waiting.append((cid, title, why))
            continue
        if not f:
            waiting.append(
                (cid, title, "НЕТ строки «Трогает:» — параллелить нельзя, пока не дописана")
            )
            continue
        clash = sorted(set(f) & taken_files)
        lclash = sorted(lk & taken_locks)
        if clash:
            waiting.append((cid, title, "общий файл с уже взятым: " + ", ".join(clash[:3])))
        elif lclash:
            waiting.append((cid, title, "общий ресурс-замок: " + ", ".join(lclash)))
        elif len(chosen) >= a.max:
            waiting.append((cid, title, f"конфликта нет, но потолок {a.max} исполнителей выбран"))
        else:
            chosen.append((cid, title, f, lk))
            taken_files |= set(f)
            taken_locks |= lk

    if busy:
        print(f"=== В РАБОТЕ ({len(busy)}) — их файлы заняты ===")
        for cid, title, f in busy:
            print(f"  {cid:22s} {title}\n      файлы: {', '.join(f) or '—'}")
    print(f"=== МОЖНО ПУСТИТЬ СЕЙЧАС ({len(chosen)} из потолка {a.max}) ===")
    for cid, title, f, lk in chosen:
        print(f"  {cid:22s} {title}")
        print(f"      файлы: {', '.join(f)}")
        if lk:
            print(f"      замки: {', '.join(sorted(lk))}")
    print(f"\n=== ЖДУТ ({len(waiting)}) ===")
    for cid, title, why in waiting:
        print(f"  {cid:22s} {why}\n      {title}")


if __name__ == "__main__":
    main()
