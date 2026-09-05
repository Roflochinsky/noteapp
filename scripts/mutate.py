#!/usr/bin/env python3
"""Мутационные прогоны по спецификации: подмена — прогон — откат — журнал.

    scripts/mutate.py run   --id <bd-id> --spec <файл.toml> [--only P2] [--scope narrow|full]
    scripts/mutate.py table --id <bd-id>

Спека мутаций — TOML (`tomllib`, стандартная библиотека): `head`, таблица `[control]`,
массив `[[mutations]]` с полями id, claim, file, find, replace, tests, expect_fail.
`tests` — фильтр Gradle `--tests` (например `*EditTest`); `expect_fail` — `Класс::имя теста`
(класс — простое имя или полное, имя — как в `@Test fun \\`…\\`` без скобок).

Прогон — `bin/gate --no-build-cache cleanTestDebugUnitTest testDebugUnitTest [--tests …]`;
вердикт читается из XML-отчётов `app/build/test-results/testDebugUnitTest/`, а не из консоли:
Gradle не печатает имена прошедших тестов. Кэш сборки — ловушка (docs/harness/epic.md):
задача, пришедшая `FROM-CACHE`/`UP-TO-DATE`, или отчёты старше старта прогона — прогон невалиден.

До первой подмены — базовый прогон каждого фильтра без мутации: красный или невалидный базовый
прогон — код 2 (иначе уже красный тест засчитался бы за убийство).

Коды выхода: 0 — все мутации убиты и управляющая убита; 1 — есть выжившая мутация (дыра
в стражах); 2 — отказ харнесса (прогон невалиден, судить о стражах по нему нельзя).

Чистота рабочей копии проверяется по ОТСЛЕЖИВАЕМЫМ файлам: неотслеживаемые (`??`)
игнорируются — иначе скрипт нельзя запустить рядом с черновиками и журналом `.mutations/`.
Гарантию отката это не ослабляет: файл сверяется по sha256 до и после.
Перенесено из workwatch (scripts/mutate.py, спека формы 2 G7), прогон заменён с pytest на Gradle.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import re
import shutil
import subprocess
import sys
import time
import tomllib
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path

OK = 0
SURVIVOR = 1
REFUSAL = 2

JOURNAL_DIR = ".mutations"
GATE = Path("bin") / "gate"
REPORTS = Path("app") / "build" / "test-results" / "testDebugUnitTest"
GIT = shutil.which("git") or "git"
TASK_LINE = re.compile(r"^> Task :app:testDebugUnitTest(?:\s+(?P<mark>[A-Z-]+))?\s*$", re.MULTILINE)
INVALID_MARKS = ("no tests found", "compilation error", "could not resolve")
TAIL_LINES = 30


class Refusal(Exception):
    """Прогон невалиден: код 2, судить о стражах по такому прогону нельзя."""


@dataclass(frozen=True)
class Mutation:
    """Одна подмена: что меняем, чем проверяем, какой тест обязан упасть."""

    id: str
    claim: str
    file: str
    find: str
    replace: str
    tests: str
    expect_fail: str


@dataclass
class Outcome:
    """Результат одной мутации — строка журнала и строка таблицы."""

    mutation: Mutation
    killed: bool
    failed: int
    errors: int
    invalid: bool = False
    tail: str = ""
    fell: list[str] = field(default_factory=list)


# --- git и файловая механика -------------------------------------------------


def _git(root: Path, *args: str) -> str:
    try:
        done = subprocess.run(  # noqa: S603
            [GIT, *args], cwd=root, capture_output=True, text=True, check=False
        )
    except OSError as exc:  # git не установлен
        raise Refusal(f"git недоступен: {exc}") from exc
    if done.returncode != 0:
        raise Refusal(f"git {' '.join(args)} отказал: {done.stderr.strip()}")
    return done.stdout


def tracked_dirt(root: Path) -> list[str]:
    """Изменения отслеживаемых файлов; неотслеживаемые и журнал не считаются грязью."""
    lines = []
    for raw in _git(root, "status", "--porcelain").splitlines():
        if not raw.strip() or raw.startswith("??"):
            continue
        if raw[3:].startswith(f"{JOURNAL_DIR}/"):
            continue
        lines.append(raw)
    return lines


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


# --- спецификация ------------------------------------------------------------


def _tests_of(raw: dict[str, object], expect_fail: str, where: str) -> str:
    tests = str(raw.get("tests") or "").strip()
    if tests:
        return tests
    klass = expect_fail.split("::")[0].strip()
    if klass:
        return f"*{klass.rsplit('.', 1)[-1]}"
    raise Refusal(f"{where}: не задан фильтр тестов (`tests`) и его не вывести из expect_fail")


def _mutation(raw: object, where: str) -> Mutation:
    if not isinstance(raw, dict):
        raise Refusal(f"{where}: ожидался блок мутации, получено {type(raw).__name__}")
    expect_fail = str(raw.get("expect_fail") or "").strip()
    if not expect_fail:
        raise Refusal(f"{where}: expect_fail пуст — имя падающего теста обязательно")
    if "::" not in expect_fail:
        raise Refusal(
            f"{where}: expect_fail пишется как `Класс::имя теста`, получено {expect_fail!r}"
        )
    path = str(raw.get("file") or "").strip()
    if not path:
        raise Refusal(f"{where}: не задан file")
    find = str(raw.get("find") or "")
    if not find:
        raise Refusal(f"{where}: не задан find")
    return Mutation(
        id=str(raw.get("id") or where),
        claim=str(raw.get("claim") or ""),
        file=path,
        find=find,
        replace=str(raw.get("replace") if raw.get("replace") is not None else ""),
        tests=_tests_of(raw, expect_fail, where),
        expect_fail=expect_fail,
    )


def load_spec(path: Path) -> tuple[str, Mutation, list[Mutation]]:
    if not path.is_file():
        raise Refusal(f"файла спеки мутаций нет: {path}")
    try:
        data = tomllib.loads(path.read_text(encoding="utf-8"))
    except tomllib.TOMLDecodeError as exc:
        raise Refusal(f"спека мутаций: TOML не разобран — {exc}") from exc
    raw_control = data.get("control")
    if isinstance(raw_control, list):
        if len(raw_control) != 1:
            raise Refusal(f"управляющая мутация должна быть ровно одна, задано {len(raw_control)}")
        raw_control = raw_control[0]
    if raw_control is None:
        raise Refusal("управляющая мутация не задана (`control`) — прогон нечем проверить")
    control = _mutation(raw_control, "control")
    raw_mutations = data.get("mutations") or []
    if not isinstance(raw_mutations, list):
        raise Refusal("спека мутаций: `mutations` должен быть списком")
    mutations = [_mutation(item, f"mutations[{i}]") for i, item in enumerate(raw_mutations)]
    return str(data.get("head") or "").strip(), control, mutations


# --- прогон Gradle -----------------------------------------------------------


@dataclass(frozen=True)
class Case:
    """Один тест из XML-отчёта: класс, имя, упал ли."""

    classname: str
    name: str
    failed: bool

    @property
    def nodeid(self) -> str:
        return f"{self.classname}::{self.name}"


def read_reports(root: Path, started: float) -> list[Case]:
    """Тесты из отчётов, записанных ПОСЛЕ старта прогона; старые отчёты — не свидетели."""
    cases: list[Case] = []
    folder = root / REPORTS
    if not folder.is_dir():
        return cases
    for report in sorted(folder.glob("TEST-*.xml")):
        if report.stat().st_mtime < started - 1:
            continue
        try:
            tree = ET.parse(report)  # noqa: S314 — отчёт пишет Gradle, не внешний ввод
        except ET.ParseError:
            continue
        for case in tree.getroot().iter("testcase"):
            failed = any(child.tag in ("failure", "error") for child in case)
            cases.append(
                Case(
                    classname=case.get("classname", ""),
                    name=re.sub(r"\(\)$", "", case.get("name", "")),
                    failed=failed,
                )
            )
    return cases


def run_gate(root: Path, tests: str) -> tuple[str, list[Case], int]:
    """`bin/gate --no-build-cache cleanTestDebugUnitTest testDebugUnitTest [--tests …]`."""
    gate = root / GATE
    if not gate.is_file():
        raise Refusal(f"нет {GATE} — прогон нечем сделать")
    cmd = [str(gate), "--no-build-cache", "cleanTestDebugUnitTest", "testDebugUnitTest"]
    if tests:
        cmd += ["--tests", tests]
    # Старые отчёты снимаются до прогона: иначе отчёт прошлой мутации (секунду назад) читался
    # бы как свидетель этой. Gradle делает то же в cleanTestDebugUnitTest, но полагаться на него
    # нельзя — задача из кэша отчёты не трогает.
    shutil.rmtree(root / REPORTS, ignore_errors=True)
    started = time.time()
    try:
        done = subprocess.run(  # noqa: S603
            cmd, cwd=root, capture_output=True, text=True, check=False
        )
    except OSError as exc:  # гейт не запускается — прогон невалиден, не «выжила»
        raise Refusal(f"гейт {GATE} не запустился: {exc}") from exc
    return done.stdout + done.stderr, read_reports(root, started), done.returncode


def _strip_params(name: str) -> str:
    """Срезается только ХВОСТОВОЙ блок параметров `[…]`: `[` внутри Kotlin-имени — часть имени."""
    return re.sub(r"\[[^\]]*\]$", "", name).strip()


def matches(nodeid: str, expect_fail: str) -> bool:
    """`expect_fail` — `Класс::имя`; класс сравнивается по суффиксу (простое или полное имя)."""
    node_class, _, node_name = nodeid.partition("::")
    want_class, _, want_name = expect_fail.partition("::")
    if _strip_params(node_name) != _strip_params(want_name):
        return False
    want_class = want_class.strip()
    return not want_class or node_class == want_class or node_class.endswith(f".{want_class}")


def verdict(output: str, cases: list[Case], mut: Mutation) -> Outcome:
    """Убита только при ровно одном падении с нужным именем и валидном прогоне.

    Невалидный прогон — задача из кэша или up-to-date, ни одного свежего отчёта, ошибка
    компиляции, пустой фильтр — не «выжила»: судить по нему о стражах нельзя, это код 2.
    """
    task = TASK_LINE.search(output)
    mark = (task.group("mark") or "") if task else ""
    low = output.lower()
    invalid = (
        task is None
        or mark in ("FROM-CACHE", "UP-TO-DATE")
        or not cases
        or any(m in low for m in INVALID_MARKS)
    )
    fell = [c.nodeid for c in cases if c.failed]
    failed = len(fell)
    killed = not invalid and failed == 1 and matches(fell[0], mut.expect_fail)
    return Outcome(
        mutation=mut,
        killed=killed,
        failed=failed,
        errors=0,
        invalid=invalid,
        tail="\n".join(output.strip().splitlines()[-TAIL_LINES:]),
        fell=fell,
    )


def baseline(root: Path, tests: str) -> None:
    """Прогон фильтра БЕЗ подмены: обязан быть валидным и зелёным, иначе судить нечем."""
    output, cases, _rc = run_gate(root, tests)
    probe = verdict(output, cases, Mutation("baseline", "", "", "-", "", tests, "-::-"))
    if probe.invalid:
        raise Refusal(
            f"базовый прогон фильтра {tests!r} невалиден — "
            "задача из кэша, отчётов нет или сборка упала"
        )
    if probe.failed:
        raise Refusal(
            f"базовый прогон фильтра {tests!r} красный без мутации: {', '.join(probe.fell)} — "
            "сначала починить тест, потом мерить стражей"
        )


# --- одна мутация ------------------------------------------------------------


def play(root: Path, journal: Path, mut: Mutation, scope_full: bool) -> Outcome:
    target = (root / mut.file).resolve()
    if not target.is_relative_to(root):
        raise Refusal(f"{mut.id}: файл {mut.file} вне рабочего дерева {root}")
    if not target.is_file():
        raise Refusal(f"{mut.id}: файла нет — {mut.file}")

    original = target.read_bytes()
    sha_before = hashlib.sha256(original).hexdigest()
    source = original.decode("utf-8")
    hits = source.count(mut.find)
    if hits != 1:
        raise Refusal(f"{mut.id}: ключ встречается {hits} раз(а) в {mut.file}, нужен ровно один")

    backup = journal / "backup" / mut.file
    backup.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(target, backup)

    target.write_bytes(source.replace(mut.find, mut.replace, 1).encode("utf-8"))
    sha_mutated = sha256(target)
    if sha_mutated == sha_before:
        shutil.copy2(backup, target)
        raise Refusal(f"{mut.id}: подмена не изменила файл — мутация не попала")
    try:
        output, cases, _rc = run_gate(root, "" if scope_full else mut.tests)
    except BaseException:
        shutil.copy2(backup, target)
        raise
    if sha256(target) != sha_mutated:
        raise Refusal(
            f"{mut.id}: файл {mut.file} изменён во время прогона — "
            "откат поверх чужой правки не делается"
        )
    shutil.copy2(backup, target)

    if sha256(target) != sha_before:
        raise Refusal(f"{mut.id}: sha после отката не совпал с исходным — файл не восстановлен")
    dirt = tracked_dirt(root)
    if dirt:
        raise Refusal(f"{mut.id}: после отката копия не чистая: {'; '.join(dirt)}")

    return verdict(output, cases, mut)


# --- команды -----------------------------------------------------------------


def _mark(outcome: Outcome) -> str:
    if outcome.invalid:
        return "ПРОГОН НЕВАЛИДЕН"
    return "убита" if outcome.killed else "выжила"


def _detail(outcome: Outcome) -> str:
    if outcome.killed:
        return next(n for n in outcome.fell if matches(n, outcome.mutation.expect_fail))
    if outcome.invalid:
        return "задача из кэша, отчётов нет или сборка упала"
    return f"{outcome.failed} failed"


def cmd_run(args: argparse.Namespace) -> int:
    root = Path.cwd().resolve()
    dirt = tracked_dirt(root)
    if dirt:
        raise Refusal(f"рабочая копия грязная, мутации не запускаются: {'; '.join(dirt)}")

    head, control, mutations = load_spec(Path(args.spec))
    actual = _git(root, "rev-parse", "HEAD").strip()
    if head and not actual.startswith(head):
        raise Refusal(f"HEAD {actual[:12]} не совпал с заявленным в спеке {head}")

    if args.only:
        mutations = [m for m in mutations if m.id == args.only]
        if not mutations:
            raise Refusal(f"мутации с id {args.only} в спеке нет")

    for tests in dict.fromkeys(m.tests for m in [*mutations, control]):
        baseline(root, tests)

    journal = root / JOURNAL_DIR / args.id
    journal.mkdir(parents=True, exist_ok=True)
    # Старый журнал снимается до первой мутации: отказ посреди прогона не должен оставлять
    # прошлую таблицу «убита» под тем же id.
    (journal / "journal.json").unlink(missing_ok=True)

    outcomes: list[Outcome] = []
    for mut in mutations:
        outcome = play(root, journal, mut, scope_full=False)
        outcomes.append(outcome)
        print(f"{mut.id}: {_mark(outcome)} ({_detail(outcome)})")

    control_outcome = play(root, journal, control, scope_full=False)
    print(f"управляющая: {_mark(control_outcome)} ({_detail(control_outcome)})")

    full: Outcome | None = None
    full_note = ""
    if args.scope == "full":
        full = play(root, journal, control, scope_full=True)
        full_note = f"{_mark(full)} ({_detail(full)})"
        if not full.invalid and not full.killed:
            full_note += f" · широкий радиус: упало {full.failed}, заявлен один тест"
        print(f"полный прогон под управляющей: {full_note}")

    _write_journal(journal, args, actual, outcomes, control_outcome, full_note, full)

    broken = [o for o in [*outcomes, control_outcome, *([full] if full else [])] if o.invalid]
    if broken:
        names = ", ".join(o.mutation.id for o in broken)
        print(f"ОТКАЗ: прогон невалиден ({names}) — судить о стражах нельзя", file=sys.stderr)
        return REFUSAL
    if not control_outcome.killed:
        print("ОТКАЗ: управляющая мутация не убита — сломан харнесс, а не код", file=sys.stderr)
        return REFUSAL
    return SURVIVOR if any(not o.killed for o in outcomes) else OK


def _row(outcome: Outcome) -> dict[str, object]:
    mut = outcome.mutation
    return {
        "id": mut.id,
        "claim": mut.claim,
        "file": mut.file,
        "find": mut.find,
        "replace": mut.replace,
        "tests": mut.tests,
        "expect_fail": mut.expect_fail,
        "killed": outcome.killed,
        "invalid": outcome.invalid,
        "failed": outcome.failed,
        "errors": outcome.errors,
        "fell": outcome.fell,
        "tail": outcome.tail,
    }


def _write_journal(
    journal: Path,
    args: argparse.Namespace,
    head: str,
    outcomes: list[Outcome],
    control: Outcome,
    full_note: str,
    full: Outcome | None,
) -> None:
    payload = {
        "id": args.id,
        "head": head,
        "date": dt.datetime.now().strftime("%Y-%m-%d %H:%M"),
        "scope": args.scope,
        "full_run": full_note,
        "full_run_tail": full.tail if full else "",
        "control": _row(control),
        "mutations": [_row(o) for o in outcomes],
    }
    (journal / "journal.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8"
    )


def _cell(text: str) -> str:
    flat = text.replace("\n", "⏎").replace("|", "\\|").strip()
    return f"`{flat}`" if flat else "`` (пусто)"


def _table_row(row: dict[str, object]) -> str:
    """У выжившей имя теста не печатается: строка «выжила + имя» читается как убийство."""
    if row.get("invalid"):
        mark, shown = "ПРОГОН НЕВАЛИДЕН", _cell(_last_line(str(row.get("tail") or "")))
    elif row["killed"]:
        fell = row["fell"] or []
        mark = "убита"
        shown = next((n for n in fell if matches(n, str(row["expect_fail"]))), "—")
    else:
        mark, shown = "выжила", f"{row['failed']} failed"
    return (
        f"| {row['id']} | {row['claim']} | {_cell(str(row['find']))} → "
        f"{_cell(str(row['replace']))} | {row['tests']} | {mark} | {shown} |"
    )


def _last_line(text: str) -> str:
    lines = text.strip().splitlines()
    return lines[-1] if lines else "прогон невалиден"


def cmd_table(args: argparse.Namespace) -> int:
    path = Path.cwd().resolve() / JOURNAL_DIR / args.id / "journal.json"
    if not path.is_file():
        raise Refusal(f"журнала прогона нет: {path}")
    data = json.loads(path.read_text(encoding="utf-8"))
    control = data["control"]
    print(
        f"HEAD {data['head'][:12]} · {data['date']} · "
        f"управляющая: {'убита' if control['killed'] else 'НЕ УБИТА'}"
    )
    if data.get("full_run"):
        print(f"полный прогон: {data['full_run']}")
    print()
    print("| id | claim | find → replace | фильтр | результат | упавший тест |")
    print("|---|---|---|---|---|---|")
    for row in data["mutations"]:
        print(_table_row(row))
    print(_table_row(control))
    return OK


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Мутационные прогоны харнесса noteapp")
    sub = parser.add_subparsers(dest="command", required=True)
    run = sub.add_parser("run", help="прогнать мутации по спеке")
    run.add_argument("--id", required=True, help="bd-id, он же каталог журнала")
    run.add_argument("--spec", required=True, help="файл спецификации мутаций (TOML)")
    run.add_argument("--only", help="прогнать одну мутацию по её id")
    run.add_argument("--scope", choices=["narrow", "full"], default="narrow")
    run.set_defaults(handler=cmd_run)
    table = sub.add_parser("table", help="напечатать таблицу прогона для файла плана")
    table.add_argument("--id", required=True)
    table.set_defaults(handler=cmd_table)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        return int(args.handler(args))
    except Refusal as exc:
        print(f"ОТКАЗ: {exc}", file=sys.stderr)
        return REFUSAL


if __name__ == "__main__":
    raise SystemExit(main())
