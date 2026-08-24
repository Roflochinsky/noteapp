#!/usr/bin/env python3
"""Тесты destructive-fs-guard: crafted JSON stdin → ожидаемое решение.

Запуск: python3 .claude/hooks/tests/test_destructive_fs_guard.py
Без фреймворков (ponytail): гоняем хук суб-процессом, проверяем deny/allow.
"""
import json
import pathlib
import subprocess
import sys

HOOK = pathlib.Path(__file__).resolve().parent.parent / "destructive-fs-guard.py"


def _decision(command: str) -> str:
    """Возвращает 'deny' | 'allow' | 'override' по выводу хука."""
    out = subprocess.run(
        [sys.executable, str(HOOK)],
        input=json.dumps({"tool_name": "Bash", "tool_input": {"command": command}}),
        capture_output=True,
        text=True,
        timeout=10,
    ).stdout
    if '"permissionDecision": "deny"' in out or '"permissionDecision":"deny"' in out:
        return "deny"
    if "WW_DESTRUCTIVE_OK=1" in out or "additionalContext" in out:
        return "override"
    return "allow"


BLOCK = [
    "rm -rf /",
    "rm -fr /",
    "rm -rf /*",
    "rm -Rf /",
    "rm -r /",
    "rm -rf ~",
    "rm -rf ~/",
    "rm -rf $HOME",
    'rm -rf "$HOME"',
    "rm -rf ${HOME}",
    "rm -rf /etc",
    "rm -rf /usr/",
    "rm -rf /var/*",
    "rm -rf /home",
    "rm -rf /tmp",
    "rm -rf / tmp/foo",          # печально известная опечатка (пробел)
    'rm -rf "/"',
    "rm -r --no-preserve-root /",
    "sudo rm -rf /",
    "echo hi && rm -rf /",
    "cd /somewhere && rm -rf ..",
    "dd if=/dev/zero of=/dev/sda",
    "mkfs.ext4 /dev/sdb",
    "find / -delete",
    "find ~ -name x -exec rm {} ;",
    "chmod -R 777 /",
    "chown -R root /etc",
    "cat evil > /dev/sda",
    "(rm -rf /)",                # подоболочка — реальная команд-позиция
    "$(rm -rf /)",               # подстановка команды
    # ── находки adversarial-ревью: обёртки-раннеры с опциями ──
    "sudo -u root rm -rf /",
    "sudo -i rm -rf /",
    "sudo -E rm -rf /",
    "nice -n 10 rm -rf /",
    "env -i rm -rf /",
    "command -p rm -rf /",
    "timeout 5 rm -rf /",
    "sudo dd if=/dev/zero of=/dev/sda",
    # ── чистые слэши / ANSI-C / dot-пути / ~user ──
    "rm -rf //",
    "rm -rf ///",
    "rm -rf $'/'",
    "rm -rf ~root",
    "rm -rf /.",
    "rm -rf /./",
    # ── дисковые нюкеры ──
    "wipefs -a /dev/sda",
    "blkdiscard /dev/sda",
    # ── override не как префикс сегмента → не спасает опасный сегмент ──
    "rm -rf / ; echo done WW_DESTRUCTIVE_OK=1",
    # ── GAP A: квотированный путь редиректа не должен обходить проверку устройства ──
    'cat x > "/dev/sda"',
    'cat x >> "/dev/sda"',
    'cat x > /dev/"sda"',
    'cat x > "/dev/"sda',
    # ── GAP B: ANSI-C escape декодируется в / ──
    r"rm -rf $'\x2f'",
    r"rm -rf $'\057'",
]

ALLOW = [
    "rm -rf ./build",
    "rm -rf node_modules",
    "rm -rf backend/.mypy_cache",
    "rm -f file.txt",
    "rm -rf /tmp/claude-1000/x/scratchpad",   # глубокий /tmp-подпуть — не сам /tmp
    "rm -rf /home/user/project",              # именованный подпуть, не /home
    "rm -rf ./dist ./coverage",
    'git commit -m "remove rm -rf / from docs"',
    'echo "rm -rf /"',
    'grep -rn "rm -rf" .',
    "find . -name '*.pyc' -delete",           # scoped find по cwd
    "chmod -R 755 ./scripts",
    "dd if=/dev/zero of=./testfile bs=1M count=1",  # запись в файл, не в устройство
    'echo "of=/dev/sda"',
    'echo "=== live deny (rm -rf /) ==="',           # regression: скобки+rm внутри кавычек
    'git commit -m "danger: rm -rf / incident"',     # regression: rm -rf / в сообщении коммита
    'echo "cleanup; rm -rf / done"',                 # regression: ; внутри кавычек
    "find / -name rm",                               # поиск файла с именем rm — не удаление
    # ── находки ревью: редирект-regex не должен ловить внутри кавычек ──
    'echo "> /dev/sda"',
    'git commit -m "pipe out > /dev/sda note"',
    # ── git/docker rm — подкоманда, не файловый rm (в т.ч. под sudo) ──
    "sudo git rm -rf /",
    "git rm -rf ./old",
    "docker rm -f container",
    # ── префикс-ловушка sysdir не должна over-match ──
    "rm -rf /vartmp",
    "rm -rf /usrlib",
    "rm -rf /home_backup",
    "rm -rf ./*",                                    # scoped wildcard в cwd
    "cat x > ./file",                                # редирект в обычный файл
    "echo hi > out.log",
]

OVERRIDE = [
    "WW_DESTRUCTIVE_OK=1 rm -rf /",
    "WW_DESTRUCTIVE_OK=1 dd if=/dev/zero of=/dev/sda",
]


def main() -> int:
    fails = []
    for c in BLOCK:
        d = _decision(c)
        if d != "deny":
            fails.append(f"[должно БЛОКИРОВАТЬ, получили {d}] {c}")
    for c in ALLOW:
        d = _decision(c)
        if d != "allow":
            fails.append(f"[должно ПРОПУСКАТЬ, получили {d}] {c}")
    for c in OVERRIDE:
        d = _decision(c)
        if d != "override":
            fails.append(f"[должно ПРОПУСКАТЬ через override, получили {d}] {c}")

    total = len(BLOCK) + len(ALLOW) + len(OVERRIDE)
    if fails:
        print(f"FAIL: {len(fails)}/{total}")
        for f in fails:
            print("  " + f)
        return 1
    print(f"OK: {total}/{total} (block={len(BLOCK)} allow={len(ALLOW)} override={len(OVERRIDE)})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
