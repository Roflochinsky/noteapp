#!/usr/bin/env python3
"""epic-metrics.py — замер эпика одной командой, markdown-таблицей в раздел «Ретро» плана.

Считает по транскриптам Claude Code (`~/.claude/projects/<проект>/<сессия>.jsonl` и
`<сессия>/subagents/agent-*.jsonl`): агент-минуты по категориям, параллельность, простои
без единого агента, агентов на срез, прогоны гейтов, вердикты ревью (из файла плана).
Данные Orca (`orca orchestration task-list --json`) добавляются флагом --orca.

Собран из разведочных sessions.py/lead.py/tools.py ведущего.
Правило проекта «Считай командой, а не в уме». Перенесено из workwatch (форма 2, R4).

  python3 scripts/epic-metrics.py --session <префикс-id> --plan docs/plans/2026-08-26-tasks-v2.md
"""

import argparse
import collections
import datetime as dt
import glob
import json
import os
import re
import shutil
import subprocess
import sys

# категория диспатча определяется по его описанию (поле description вызова Agent)
CATS = [
    ("intent-review", r"review of |ревью замысла|\bhld\b|\blld\b"),
    ("confirm-pass", r"^confirm"),
    ("review", r"^review|axis|ось"),
    ("fix", r"^fix|правк"),
    ("merge", r"^merge|слия"),
]
GATE_RX = re.compile(r"bin/gate|gradlew|actionlint|pre-commit run|pytest|ruff")


def ts(s):
    return dt.datetime.fromisoformat(s.replace("Z", "+00:00")).astimezone()


def span(path):
    """(начало, конец, вызовов инструментов, прогонов гейтов) одного транскрипта."""
    first = last = None
    tools = gates = 0
    with open(path, errors="ignore") as f:
        for line in f:
            try:
                o = json.loads(line)
            except json.JSONDecodeError:
                continue
            t = o.get("timestamp")
            if t:
                x = ts(t)
                first = first or x
                last = x
            m = o.get("message") or {}
            c = m.get("content") if isinstance(m, dict) else None
            if not isinstance(c, list):
                continue
            for b in c:
                if isinstance(b, dict) and b.get("type") == "tool_use":
                    tools += 1
                    if b.get("name") == "Bash" and GATE_RX.search(
                        b.get("input", {}).get("command", "")
                    ):
                        gates += 1
    return first, last, tools, gates


def category(desc):
    d = (desc or "").lower()
    for name, rx in CATS:
        if re.search(rx, d):
            return name
    return "build"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--session", required=True, help="префикс id сессии ведущего")
    ap.add_argument("--plan", help="файл плана эпика — из него берутся вердикты")
    ap.add_argument("--project-glob", default="*noteapp*")
    ap.add_argument("--orca", action="store_true", help="добавить данные orca orchestration")
    a = ap.parse_args()

    root = os.path.expanduser("~/.claude/projects")
    cands = [
        p for p in glob.glob(f"{root}/{a.project_glob}") if glob.glob(f"{p}/{a.session}*.jsonl")
    ]
    if not cands:
        sys.exit(f"сессия {a.session} не найдена в {root}/{a.project_glob}")
    proj = cands[0]
    sess = glob.glob(f"{proj}/{a.session}*.jsonl")[0]
    sid = os.path.basename(sess)[:-6]

    # диспатчи ведущего: время + описание
    dispatches = []
    with open(sess, errors="ignore") as fh:
        lines = fh.readlines()
    for line in lines:
        try:
            o = json.loads(line)
        except json.JSONDecodeError:
            continue
        m = o.get("message") or {}
        if isinstance(m, dict) and isinstance(m.get("content"), list):
            for b in m["content"]:
                if isinstance(b, dict) and b.get("type") == "tool_use" and b.get("name") == "Agent":
                    dispatches.append((ts(o["timestamp"]), b["input"].get("description", "")))
    dispatches.sort()

    spans = []
    for sp in sorted(glob.glob(f"{proj}/{sid}/subagents/agent-*.jsonl")):
        f, last, tools, gates = span(sp)
        if f:
            spans.append((f, last, tools, gates))
    spans.sort()
    if not spans:
        sys.exit("сабагентов у этой сессии не найдено")

    # категория присваивается по порядку запуска: n-й диспатч <-> n-й транскрипт
    per, permin, pergate, perslice = (collections.Counter() for _ in range(4))
    for (_t, d), (f, last, _tools, gates) in zip(dispatches, spans, strict=False):
        c = category(d)
        per[c] += 1
        permin[c] += (last - f).total_seconds() / 60
        pergate[c] += gates
        m = re.search(r"\.(\d+)", d)
        if m:
            perslice[m.group(1)] += 1

    first = min(x[0] for x in spans)
    last = max(x[1] for x in spans)
    wall = (last - first).total_seconds() / 3600

    # параллельность и простои
    ev = []
    for f, last, _, _ in spans:
        ev += [(f, 1), (last, -1)]
    ev.sort()
    n = busy = area = peak = 0
    prev = None
    idle = []
    for t, d in ev:
        if prev is not None:
            sec = (t - prev).total_seconds()
            if n > 0:
                busy += sec
                area += n * sec
            elif sec > 300:
                idle.append((prev, t))
        n += d
        peak = max(peak, n)
        prev = t

    out = [
        f"### Замер эпика (`epic-metrics.py`, сессия `{sid[:8]}`)\n",
        f"Стена: **{wall:.1f} ч** ({first:%m-%d %H:%M} → {last:%m-%d %H:%M}). "
        f"Сабагентов: **{len(spans)}**. Агент-часов: **{sum(permin.values()) / 60:.1f}**. "
        f"Средняя параллельность при работающем агенте: **{area / busy if busy else 0:.1f}**, "
        f"пик **{peak}**.\n",
        "| категория | агентов | агент-минут | средний | прогонов гейтов |",
        "|---|---:|---:|---:|---:|",
    ]
    for c in ["build", "review", "confirm-pass", "fix", "merge", "intent-review"]:
        if per[c]:
            out.append(
                f"| {c} | {per[c]} | {permin[c]:.0f} | {permin[c] / per[c]:.0f} | {pergate[c]} |"
            )
    out.append(
        f"| **итого** | **{sum(per.values())}** | **{sum(permin.values()):.0f}** | | "
        f"**{sum(pergate.values())}** |"
    )
    out += ["", "| срез | агентов |", "|---|---:|"]
    for s, k in sorted(perslice.items(), key=lambda x: -x[1]):
        out.append(f"| `.{s}` | {k} |")
    out += [
        "",
        f"**Простои без единого агента (>5 мин): {len(idle)}, суммарно "
        f"{sum((b - x).total_seconds() for x, b in idle) / 3600:.1f} ч**\n",
    ]
    for x, b in idle:
        out.append(f"- {x:%H:%M} → {b:%H:%M} — {(b - x).total_seconds() / 60:.0f} мин")
    out.append("")

    if a.plan and os.path.exists(a.plan):
        with open(a.plan, encoding="utf-8") as fh:
            text = fh.read()
        cr = len(re.findall(r"CHANGES REQUESTED", text))
        okv = len(re.findall(r"\bAPPROVE\b", text))
        rnd = len(re.findall(r"^#### (Правки|Круг правок|Второй круг)", text, re.M))
        out.append(
            f"Вердикты в плане: **CHANGES REQUESTED {cr}**, **APPROVE {okv}**; "
            f"кругов правок, оформленных разделом: **{rnd}**.\n"
        )

    if a.orca:
        try:
            r = subprocess.run(  # noqa: S603 — аргументы константные
                [shutil.which("orca") or "orca", "orchestration", "task-list", "--json"],
                capture_output=True,
                text=True,
                timeout=30,
            )
            tasks = json.loads(r.stdout)["result"]["tasks"]
            done = [t for t in tasks if t.get("completed_at")]
            out.append(
                f"Orca: задач **{len(tasks)}**, завершено **{len(done)}**, "
                f"статусы {dict(collections.Counter(t['status'] for t in tasks))}.\n"
            )
        except Exception as e:  # Orca необязательна: нет CLI, нет забега или
            # битый JSON с виндовыми путями; замер эпика не должен падать из-за этого.
            out.append(f"Orca: данные не получены ({e}).\n")

    print("\n".join(out))


if __name__ == "__main__":
    main()
