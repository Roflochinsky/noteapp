"""Разбор строки «Трогает:» — машинное правило формы 2 (ready-slices.py)."""

import importlib.util
import pathlib

import pytest

_SRC = pathlib.Path(__file__).resolve().parents[1] / "ready-slices.py"
_spec = importlib.util.spec_from_file_location("ready_slices", _SRC)
rs = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(rs)

MAIN = "app/src/main/kotlin/com/roflochinsky/noteapp"


@pytest.fixture
def repo(tmp_path):
    (tmp_path / MAIN / "pipeline").mkdir(parents=True)
    (tmp_path / MAIN / "pipeline" / "Edit.kt").write_text("val x = 1\n")
    (tmp_path / MAIN / "pipeline" / "RepoStore.kt").write_text("val y = 1\n")
    (tmp_path / MAIN / "ui").mkdir(parents=True)
    (tmp_path / MAIN / "ui" / "TasksScreen.kt").write_text("val z = 1\n")
    return str(tmp_path)


def test_touches_reads_description_and_notes(repo):
    issue = {
        "description": f"Продукт: x\n\nТрогает: {MAIN}/pipeline/Edit.kt",
        "notes": f"**Трогает:** {MAIN}/ui/TasksScreen.kt, docs/plans/",
    }
    assert rs.touches(issue, repo) == [
        f"{MAIN}/pipeline/Edit.kt",
        f"{MAIN}/ui/TasksScreen.kt",
        "docs/plans/",
    ]


def test_touches_returns_nothing_without_the_line(repo):
    assert rs.touches({"description": "Продукт: y\n\nбез строки"}, repo) == []


def test_normalize_expands_braces_and_short_paths_and_drops_prose(repo):
    got = rs.normalize("pipeline/{Edit,RepoStore}.kt", repo)
    assert got == [f"{MAIN}/pipeline/Edit.kt", f"{MAIN}/pipeline/RepoStore.kt"]
    assert rs.normalize("ui/TasksScreen.kt", repo) == [f"{MAIN}/ui/TasksScreen.kt"]
    assert rs.normalize("(только", repo) == []
    assert rs.normalize("<путь>", repo) == []


def test_locks_name_shared_resources():
    assert "gradle-build" in rs.locks(["app/build.gradle.kts"])
    assert "gradle-build" in rs.locks(["gradle/libs.versions.toml"])
    assert "fixtures" in rs.locks(["app/src/test/resources/github/ref.json"])
    assert "action-contract" in rs.locks(["docs/examples/process-notes.yml"])
    assert "adr" in rs.locks(["docs/adr/2026-09-05-x.md"])
    assert "plan-file" in rs.locks(["docs/plans/2026-08-26-tasks-v2.md"])
    assert rs.locks(["docs/plans/2026-08-26-tasks-v2/nikitatrubaev-0rk.37.md"]) == set()
    assert rs.locks([f"{MAIN}/pipeline/Edit.kt"]) == set()
