"""Тесты scripts/mutate.py на временном git-репозитории с поддельным `bin/gate`.

Сеам — командная строка: тесты зовут скрипт подпроцессом в tmp_path, как его зовёт человек.
Настоящий Gradle сюда не влезает (минута на прогон), поэтому в репозитории лежит поддельный
`bin/gate`: он «гоняет» тесты из `tests.json` — тест падает, когда в файле нет обязательной
строки, — пишет XML-отчёты той же формы, что Gradle, и печатает строку задачи. Так проверяется
всё, что делает mutate.py сам: отказы, sha в четырёх точках, откат, разбор отчётов, коды выхода,
страж кэша.
"""

import hashlib
import json
import os
import subprocess
import sys
from pathlib import Path

import pytest

MUTATE = Path(__file__).resolve().parent.parent / "mutate.py"

CALC = """package com.example

object Calc {
    fun total(rows: List<Row>, siteId: Int): Int {
        var result = 0
        for (row in rows) {
            if (row.site != siteId) continue
            result += row.value
        }
        return result
    }
}
"""

GREET = """package com.example

object Greet {
    fun greet(name: String): String {
        if (name.isEmpty()) return "нет имени"
        return "привет, $name"
    }
}
"""

# Поддельный гейт: тест падает, если в файле нет строки must_contain.
TESTS_JSON = [
    {
        "classname": "com.example.CalcTest",
        "name": "site scope is applied()",
        "file": "app/src/main/kotlin/com/example/Calc.kt",
        "must_contain": "if (row.site != siteId) continue",
    },
    {
        "classname": "com.example.CalcTest",
        "name": "empty rows give zero()",
        "file": "app/src/main/kotlin/com/example/Calc.kt",
        "must_contain": "var result = 0",
    },
    {
        "classname": "com.example.GreetTest",
        "name": "empty name has fallback()",
        "file": "app/src/main/kotlin/com/example/Greet.kt",
        "must_contain": 'if (name.isEmpty()) return "нет имени"',
    },
]

FAKE_GATE = r'''#!/usr/bin/env python3
"""Поддельный bin/gate: XML-отчёты как у Gradle, строка задачи, код выхода как у Gradle."""
import fnmatch, json, os, sys, time
from pathlib import Path
from xml.sax.saxutils import escape

root = Path(__file__).resolve().parent.parent
args = sys.argv[1:]
flt = args[args.index("--tests") + 1] if "--tests" in args else "*"
mark = " FROM-CACHE" if os.environ.get("MUTATE_FAKE_CACHE") else ""
tests = json.loads((root / "tests.json").read_text(encoding="utf-8"))
def hit(t):
    short = t["classname"].rsplit(".", 1)[-1]
    return fnmatch.fnmatch(t["classname"], flt) or fnmatch.fnmatch(short, flt)


chosen = [t for t in tests if hit(t)]
if not chosen:
    print("No tests found for given includes: [%s]" % flt)
    sys.exit(1)
out = root / "app" / "build" / "test-results" / "testDebugUnitTest"
out.mkdir(parents=True, exist_ok=True)
if mark:
    # задача из кэша: отчёты не перезаписываются
    print("> Task :app:testDebugUnitTest" + mark)
    sys.exit(0)
failed_total = 0
by_class = {}
for t in chosen:
    by_class.setdefault(t["classname"], []).append(t)
for cls, cases in by_class.items():
    rows = []
    for t in cases:
        body = (root / t["file"]).read_text(encoding="utf-8")
        ok = t["must_contain"] in body
        failed_total += 0 if ok else 1
        inner = "" if ok else "<failure message=\"boom\">AssertionError</failure>"
        name = escape(t["name"], {'"': "&quot;"})
        row = '<testcase name="%s" classname="%s" time="0.001">%s</testcase>'
        rows.append(row % (name, cls, inner))
    stamp = time.strftime("%Y-%m-%dT%H:%M:%S")
    head = '<?xml version="1.0" encoding="UTF-8"?>\n<testsuite name="%s" tests="%d" timestamp="%s">'
    xml = head % (cls, len(cases), stamp) + "".join(rows) + "</testsuite>\n"
    (out / ("TEST-%s.xml" % cls)).write_text(xml, encoding="utf-8")
print("> Task :app:cleanTestDebugUnitTest")
print("> Task :app:testDebugUnitTest")
print("BUILD %s in 1s" % ("FAILED" if failed_total else "SUCCESSFUL"))
sys.exit(1 if failed_total else 0)
'''

CONTROL = """[control]
file = "app/src/main/kotlin/com/example/Greet.kt"
find = "        if (name.isEmpty()) return \\"нет имени\\""
replace = "        if (false) return \\"нет имени\\""
tests = "*GreetTest"
expect_fail = "GreetTest::empty name has fallback"
"""

MUTATION_P1 = """[[mutations]]
id = "P1"
claim = "фильтр по площадке применяется к выборке"
file = "app/src/main/kotlin/com/example/Calc.kt"
find = "            if (row.site != siteId) continue"
replace = "            if (false) continue"
tests = "*CalcTest"
expect_fail = "com.example.CalcTest::site scope is applied"
"""


def _vcs(cwd: Path, *args: str) -> str:
    done = subprocess.run(["git", *args], cwd=cwd, capture_output=True, text=True, check=True)
    return done.stdout


@pytest.fixture
def repo(tmp_path: Path) -> Path:
    """Временный репозиторий с двумя Kotlin-файлами и поддельным гейтом."""
    root = tmp_path / "repo"
    src = root / "app" / "src" / "main" / "kotlin" / "com" / "example"
    src.mkdir(parents=True)
    (src / "Calc.kt").write_text(CALC, encoding="utf-8")
    (src / "Greet.kt").write_text(GREET, encoding="utf-8")
    (root / "bin").mkdir()
    gate = root / "bin" / "gate"
    gate.write_text(FAKE_GATE, encoding="utf-8")
    gate.chmod(0o755)
    (root / "tests.json").write_text(json.dumps(TESTS_JSON, ensure_ascii=False), encoding="utf-8")
    (root / ".gitignore").write_text("app/build/\n.mutations/\n", encoding="utf-8")
    _vcs(tmp_path, "init", "-q", "-b", "main", str(root))
    _vcs(root, "config", "user.email", "t@example.com")
    _vcs(root, "config", "user.name", "t")
    _vcs(root, "add", "-A")
    _vcs(root, "commit", "-qm", "init")
    return root


def _spec(repo: Path, body: str, name: str = "mut.toml") -> Path:
    path = repo / name
    path.write_text(body, encoding="utf-8")
    return path


def _run(
    repo: Path, *args: str, env: dict[str, str] | None = None
) -> "subprocess.CompletedProcess[str]":
    return subprocess.run(
        [sys.executable, str(MUTATE), *args],
        cwd=repo,
        capture_output=True,
        text=True,
        check=False,
        env={**os.environ, **(env or {})},
    )


def _sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _sources(repo: Path) -> dict[Path, str]:
    return {p: _sha(p) for p in sorted((repo / "app" / "src").rglob("*.kt"))}


def test_all_killed_exits_zero_and_restores_files(repo: Path) -> None:
    before = _sources(repo)
    done = _run(repo, "run", "--id", "na-1", "--spec", str(_spec(repo, CONTROL + MUTATION_P1)))
    assert done.returncode == 0, done.stdout + done.stderr
    assert _sources(repo) == before
    assert (repo / ".mutations" / "na-1" / "journal.json").exists()


def test_surviving_mutation_exits_one(repo: Path) -> None:
    survivor = """[[mutations]]
id = "P1"
claim = "ничего не утверждает"
file = "app/src/main/kotlin/com/example/Calc.kt"
find = "        return result"
replace = "        return result // мутация"
tests = "*CalcTest"
expect_fail = "CalcTest::site scope is applied"
"""
    done = _run(repo, "run", "--id", "na-2", "--spec", str(_spec(repo, CONTROL + survivor)))
    assert done.returncode == 1, done.stdout + done.stderr
    assert "P1: выжила" in done.stdout


def test_surviving_control_exits_two(repo: Path) -> None:
    weak = """[control]
file = "app/src/main/kotlin/com/example/Greet.kt"
find = "        return \\"привет, $name\\""
replace = "        return \\"привет,  $name\\""
tests = "*GreetTest"
expect_fail = "GreetTest::empty name has fallback"
"""
    done = _run(repo, "run", "--id", "na-3", "--spec", str(_spec(repo, weak + MUTATION_P1)))
    assert done.returncode == 2, done.stdout + done.stderr
    assert "управляющая" in done.stdout + done.stderr


def test_dirty_worktree_refuses(repo: Path) -> None:
    (repo / "app/src/main/kotlin/com/example/Calc.kt").write_text(CALC + "// грязь\n", "utf-8")
    done = _run(repo, "run", "--id", "na-4", "--spec", str(_spec(repo, CONTROL + MUTATION_P1)))
    assert done.returncode == 2
    assert "грязн" in done.stderr


def test_file_outside_cwd_refuses(repo: Path, tmp_path: Path) -> None:
    outside = """[[mutations]]
id = "P1"
claim = "чужое дерево"
file = "../outside.kt"
find = "x"
replace = "y"
tests = "*CalcTest"
expect_fail = "CalcTest::site scope is applied"
"""
    (tmp_path / "outside.kt").write_text("val x = 1\n", encoding="utf-8")
    done = _run(repo, "run", "--id", "na-5", "--spec", str(_spec(repo, CONTROL + outside)))
    assert done.returncode == 2
    assert "вне рабочего дерева" in done.stderr


@pytest.mark.parametrize("find", ["row", "такой строки нет"])
def test_non_unique_or_missing_key_refuses(repo: Path, find: str) -> None:
    body = f"""[[mutations]]
id = "P1"
claim = "ключ не уникален или отсутствует"
file = "app/src/main/kotlin/com/example/Calc.kt"
find = "{find}"
replace = "line"
tests = "*CalcTest"
expect_fail = "CalcTest::site scope is applied"
"""
    done = _run(repo, "run", "--id", "na-6", "--spec", str(_spec(repo, CONTROL + body)))
    assert done.returncode == 2
    assert "встречается" in done.stderr


@pytest.mark.parametrize("expect", ["", "site scope is applied"])
def test_expect_fail_must_name_class_and_test(repo: Path, expect: str) -> None:
    body = MUTATION_P1.replace(
        'expect_fail = "com.example.CalcTest::site scope is applied"', f'expect_fail = "{expect}"'
    )
    done = _run(repo, "run", "--id", "na-7", "--spec", str(_spec(repo, CONTROL + body)))
    assert done.returncode == 2
    assert "expect_fail" in done.stderr


def test_missing_control_refuses(repo: Path) -> None:
    done = _run(repo, "run", "--id", "na-8", "--spec", str(_spec(repo, MUTATION_P1)))
    assert done.returncode == 2
    assert "управляющая" in done.stderr


def test_head_mismatch_refuses_and_match_is_accepted(repo: Path) -> None:
    done = _run(
        repo,
        "run",
        "--id",
        "na-9",
        "--spec",
        str(_spec(repo, 'head = "deadbee"\n' + CONTROL + MUTATION_P1)),
    )
    assert done.returncode == 2
    assert "HEAD" in done.stderr
    head = _vcs(repo, "rev-parse", "HEAD").strip()[:7]
    done = _run(
        repo,
        "run",
        "--id",
        "na-10",
        "--spec",
        str(_spec(repo, f'head = "{head}"\n' + CONTROL + MUTATION_P1)),
    )
    assert done.returncode == 0, done.stdout + done.stderr


def test_replacement_that_changes_nothing_refuses(repo: Path) -> None:
    noop = MUTATION_P1.replace(
        'replace = "            if (false) continue"',
        'replace = "            if (row.site != siteId) continue"',
    )
    done = _run(repo, "run", "--id", "na-11", "--spec", str(_spec(repo, CONTROL + noop)))
    assert done.returncode == 2
    assert "не изменил" in done.stderr


def test_only_selects_one_mutation(repo: Path) -> None:
    two = (
        MUTATION_P1
        + """[[mutations]]
id = "P2"
claim = "второй страж"
file = "app/src/main/kotlin/com/example/Calc.kt"
find = "        var result = 0"
replace = "        var result = 1"
tests = "*CalcTest"
expect_fail = "CalcTest::empty rows give zero"
"""
    )
    done = _run(
        repo, "run", "--id", "na-12", "--spec", str(_spec(repo, CONTROL + two)), "--only", "P1"
    )
    assert done.returncode == 0, done.stdout + done.stderr
    assert "P2" not in done.stdout


def test_scope_full_runs_whole_suite(repo: Path) -> None:
    done = _run(
        repo,
        "run",
        "--id",
        "na-13",
        "--spec",
        str(_spec(repo, CONTROL + MUTATION_P1)),
        "--scope",
        "full",
    )
    assert done.returncode == 0, done.stdout + done.stderr
    assert "полный прогон" in done.stdout


def test_table_prints_markdown(repo: Path) -> None:
    _run(repo, "run", "--id", "na-14", "--spec", str(_spec(repo, CONTROL + MUTATION_P1)))
    done = _run(repo, "table", "--id", "na-14")
    assert done.returncode == 0, done.stdout + done.stderr
    assert "HEAD " in done.stdout
    assert "управляющая: убита" in done.stdout
    assert "| P1 |" in done.stdout
    assert "CalcTest::site scope is applied" in done.stdout
    assert "→" in done.stdout


def test_table_without_journal_refuses(repo: Path) -> None:
    done = _run(repo, "table", "--id", "нет-такого")
    assert done.returncode == 2
    assert "журнал" in done.stderr


def test_backup_keeps_full_path(repo: Path) -> None:
    _run(repo, "run", "--id", "na-15", "--spec", str(_spec(repo, CONTROL + MUTATION_P1)))
    backup = repo / ".mutations" / "na-15" / "backup" / "app/src/main/kotlin/com/example/Calc.kt"
    assert backup.exists()
    assert backup.read_text(encoding="utf-8") == CALC


def test_two_failures_is_a_survivor(repo: Path) -> None:
    """Ровно одно падение — не «хотя бы одно»: два упавших теста мутацию не убивают."""
    wide = """[[mutations]]
id = "P1"
claim = "радиус шире заявленного"
file = "app/src/main/kotlin/com/example/Calc.kt"
find = "        var result = 0\\n        for (row in rows) {\\n            if (row.site != siteId)"
replace = "        var result = 1\\n        for (row in rows) {\\n            if (false)"
tests = "*CalcTest"
expect_fail = "CalcTest::site scope is applied"
"""
    done = _run(repo, "run", "--id", "na-16", "--spec", str(_spec(repo, CONTROL + wide)))
    assert done.returncode == 1, done.stdout + done.stderr
    assert "P1: выжила" in done.stdout


def test_a_wrong_test_name_is_a_survivor(repo: Path) -> None:
    """Одно падение с чужим именем — не убийство: страж закрывает не ту ось."""
    wrong = MUTATION_P1.replace("site scope is applied", "empty rows give zero")
    done = _run(repo, "run", "--id", "na-17", "--spec", str(_spec(repo, CONTROL + wrong)))
    assert done.returncode == 1, done.stdout + done.stderr
    assert "P1: выжила" in done.stdout


def test_a_filter_that_selects_nothing_is_an_invalid_run(repo: Path) -> None:
    """Тест не отобрался — прогон невалиден (код 2), а не «мутация выжила» (код 1)."""
    absent = MUTATION_P1.replace('tests = "*CalcTest"', 'tests = "*NoSuchTest"')
    before = _sha(repo / "app/src/main/kotlin/com/example/Calc.kt")
    done = _run(repo, "run", "--id", "na-18", "--spec", str(_spec(repo, CONTROL + absent)))
    assert done.returncode == 2, done.stdout + done.stderr
    assert "НЕВАЛИДЕН" in done.stdout
    assert _sha(repo / "app/src/main/kotlin/com/example/Calc.kt") == before


def test_a_cached_task_is_an_invalid_run(repo: Path) -> None:
    """Ловушка кэша Gradle (docs/harness/epic.md): задача FROM-CACHE — не прогон."""
    done = _run(
        repo,
        "run",
        "--id",
        "na-19",
        "--spec",
        str(_spec(repo, CONTROL + MUTATION_P1)),
        env={"MUTATE_FAKE_CACHE": "1"},
    )
    assert done.returncode == 2, done.stdout + done.stderr
    assert "НЕВАЛИДЕН" in done.stdout


def test_missing_gate_refuses(repo: Path) -> None:
    (repo / "bin" / "gate").unlink()
    _vcs(repo, "add", "-A")
    _vcs(repo, "commit", "-qm", "без гейта")
    done = _run(repo, "run", "--id", "na-20", "--spec", str(_spec(repo, CONTROL + MUTATION_P1)))
    assert done.returncode == 2
    assert "bin/gate" in done.stderr


def test_missing_spec_file_refuses(repo: Path) -> None:
    done = _run(repo, "run", "--id", "na-21", "--spec", str(repo / "нет.toml"))
    assert done.returncode == 2
    assert "спек" in done.stderr


def test_outside_a_repository_refuses(tmp_path: Path) -> None:
    plain = tmp_path / "plain"
    plain.mkdir()
    done = subprocess.run(
        [sys.executable, str(MUTATE), "run", "--id", "x", "--spec", "нет.toml"],
        cwd=plain,
        capture_output=True,
        text=True,
        check=False,
    )
    assert done.returncode == 2
