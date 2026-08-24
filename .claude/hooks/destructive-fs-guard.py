#!/usr/bin/env python3
"""PreToolUse(Bash): страж катастрофических файловых/дисковых операций.

Блокирует почти-необратимое, что в обычной рабочей сессии не делают (near-zero
false-positive), в т.ч. если такую команду подсунет инъекция/галлюцинация суб-агента:
  - `rm` рекурсивно по КАТАСТРОФИЧЕСКОЙ цели: /, //, /*, ~, ~user, $HOME, .., систем-
    каталоги (/etc /usr /var /home /root /tmp …); а также `rm --no-preserve-root`;
  - `dd of=/dev/…`, `mkfs*`, `wipefs`, `blkdiscard` (запись/затирание блок-устройства);
  - `chmod/chown -R` по / или систем-каталогу;
  - `find` по /, ~, $HOME с `-delete` / `-exec rm`;
  - редирект в блок-устройство (`> /dev/sd*|nvme*|…`).
Разбор quote-aware (границы команд и редирект — ВНЕ кавычек, токены через shlex), плюс
re-scan под обёртками-раннерами (sudo/-u/-i, timeout N, nice -n N, env -i …), чтобы их
опции не прятали настоящую команду. Обычные scoped-удаления (`rm -rf ./build`,
`node_modules`, `/tmp/…/scratch`) НЕ трогает — чтобы не плодить override-усталость.

Обход: env-ПРЕФИКС `WW_DESTRUCTIVE_OK=1` перед командой сегмента (единый с
destructive-db-guard) — ТОЛЬКО после явного одобрения пользователем точной цели и
команды (AGENTS.md → Окружения и безопасность). Признаётся лишь как префикс сегмента,
не как слово где-то в строке.

ponytail: прячущие обёртки (`bash -c "…"`, команда из переменной/файла, конвейер в
`xargs`) — принятый ceiling, как и у destructive-db-guard: ремень безопасности для
прямого/частого случая, не песочница. Каждый класс покрыт тестом (.claude/hooks/tests/).
"""
import codecs
import json
import re
import shlex
import sys


def _emit(payload: dict) -> None:
    print(json.dumps({"hookSpecificOutput": {"hookEventName": "PreToolUse", **payload}}, ensure_ascii=False))


def _deny(what: str) -> None:
    _emit(
        {
            "permissionDecision": "deny",
            "permissionDecisionReason": (
                f"Хук destructive-fs-guard: {what} заблокировано (катастрофическая, "
                "почти-необратимая операция). Если это осознанно и одобрено пользователем — "
                "назови точную цель и команду, получи явное «да», затем повтори команду с "
                "ПРЕФИКСОМ WW_DESTRUCTIVE_OK=1. Обычные scoped-удаления (rm -rf ./build и т.п.) "
                "хук не трогает."
            ),
        }
    )
    sys.exit(0)


try:
    data = json.load(sys.stdin)
except Exception:
    sys.exit(0)

cmd = (data.get("tool_input") or {}).get("command") or ""
if not cmd.strip():
    sys.exit(0)

# ── quote-aware разбиение на сегменты команд ─────────────────────────────────
# Границы команд (`; | & ( ) newline backtick`) считаем ТОЛЬКО вне кавычек, поэтому
# скобки/операторы внутри echo-меток и сообщений коммита не рвут строку на фантомы.
_BREAKS = set(";|&()\n`")


def _segments(command: str):
    segs, buf, quote, i, n = [], [], None, 0, len(command)
    while i < n:
        c = command[i]
        if quote:
            buf.append(c)
            if c == quote:
                quote = None
        elif c in "'\"":
            quote = c
            buf.append(c)
        elif c in _BREAKS:
            segs.append("".join(buf))
            buf = []
        else:
            buf.append(c)
        i += 1
    segs.append("".join(buf))
    return segs


_DEV_RE = re.compile(r"^/dev/(?:sd|nvme|hd|vd|mmcblk|xvd)\w*")


def _redirects_to_device(seg: str) -> bool:
    """True, если сегмент делает `>`/`>>` в блок-устройство. Оператор `>` ищем ВНЕ
    кавычек (иначе echo-метка/сообщение коммита ложно сработают), а путь-цель после
    него де-квотим (иначе `> "/dev/sda"` обошёл бы проверку)."""
    i, n, quote = 0, len(seg), None
    while i < n:
        c = seg[i]
        if quote:
            if c == quote:
                quote = None
            i += 1
        elif c in "'\"":
            quote = c
            i += 1
        elif c == ">":
            i += 1
            while i < n and seg[i] == ">":
                i += 1
            while i < n and seg[i] in " \t":
                i += 1
            word, q2 = [], None
            while i < n:
                d = seg[i]
                if q2:
                    if d == q2:
                        q2 = None
                    else:
                        word.append(d)
                elif d in "'\"":
                    q2 = d
                elif d in " \t\n;|&()<>`":
                    break
                else:
                    word.append(d)
                i += 1
            if _DEV_RE.match("".join(word)):
                return True
        else:
            i += 1
    return False


_ASSIGN = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*=")
_ANSI_C = re.compile(r"\$(['\"])")  # $'…' / $"…"  → снимаем ведущий $, чтобы shlex видел цель

# обёртки-раннеры: у них могут быть свои опции/аргументы перед реальной командой
_RUNNERS = {
    "sudo", "doas", "command", "env", "nice", "nohup", "time", "timeout", "stdbuf",
    "setsid", "ionice", "flock", "chrt", "taskset", "xargs", "watch", "exec",
}
# «команды-хосты» с подкомандой rm/prune — их `rm` НЕ трогаем (git rm, docker rm …)
_SUBCMD_HOSTS = {
    "git", "hg", "svn", "bzr", "jj", "docker", "podman", "kubectl", "oc", "nerdctl",
    "apt", "apt-get", "dpkg", "brew", "npm", "pnpm", "yarn", "pip", "pip3", "cargo",
    "go", "gem", "bundle", "conda", "helm",
}
_DANGER = {"rm", "dd", "chmod", "chown", "find", "wipefs", "blkdiscard"}

_SYS_DIRS = (
    "bin", "boot", "dev", "etc", "home", "lib", "lib32", "lib64", "libx32",
    "opt", "proc", "root", "run", "sbin", "srv", "sys", "usr", "var", "tmp",
)
_SYSDIR_RE = re.compile(r"^/(?:" + "|".join(_SYS_DIRS) + r"|lost\+found)(?:/\*?)?$")
_HOME_RE = re.compile(r"^(?:~[a-z0-9_.-]*|\$home|\$\{home\})(?:/\*?)?$")
_ALLSLASH_RE = re.compile(r"^/+\*?$")  # /, //, ///, /*, //* …


def _base(tok: str) -> str:
    return tok.rsplit("/", 1)[-1].lower()


def _tokenize(seg: str):
    try:
        return shlex.split(_ANSI_C.sub(r"\1", seg))  # снимает кавычки; $'…' → '…'
    except ValueError:
        return _ANSI_C.sub(r"\1", seg).split()


def _match_target(t: str) -> bool:
    if t in ("/.", "/./", "..", "../", "../*"):
        return True
    if _ALLSLASH_RE.match(t):
        return True
    tl = t.lower()
    return bool(_HOME_RE.match(tl) or _SYSDIR_RE.match(tl))


def _dangerous_target(tok: str) -> bool:
    t = tok.strip()
    if _match_target(t):
        return True
    # ANSI-C escape ($'\x2f' → /, $'\057' → /): раскодируем и проверяем ещё раз
    if "\\" in t:
        try:
            dec = codecs.decode(t, "unicode_escape")
            if dec != t and _match_target(dec):
                return True
        except Exception:
            pass
    return False


def _has_flag(tokens, letters, longname) -> bool:
    for tk in tokens:
        if not tk.startswith("-"):
            continue
        if tk == longname:
            return True
        if tk.startswith("--"):
            continue
        if any(letter in tk[1:] for letter in letters):  # bundled short flags, e.g. -rf
            return True
    return False


def _positional(tokens):
    return [tk for tk in tokens[1:] if not tk.startswith("-")]


def _check(tokens):
    """tokens[0] = бинарь. Возвращает причину deny либо None."""
    if not tokens:
        return None
    base = _base(tokens[0])
    low = [t.lower() for t in tokens]
    if base == "rm":
        if "--no-preserve-root" in low:
            return "`rm --no-preserve-root` (снятие защиты корня /)"
        if _has_flag(tokens, ("r", "R"), "--recursive") and any(
            _dangerous_target(t) for t in _positional(tokens)
        ):
            return "рекурсивный `rm` по системной/корневой/домашней цели"
    elif base == "dd":
        if any(t.lower().startswith("of=/dev/") for t in tokens):
            return "`dd of=/dev/…` (запись в блок-устройство)"
    elif base.startswith("mkfs") or base in ("wipefs", "blkdiscard"):
        return f"`{base}` (форматирование/затирание блок-устройства)"
    elif base in ("chmod", "chown"):
        if _has_flag(tokens, ("R",), "--recursive") and any(
            _dangerous_target(t) for t in _positional(tokens)
        ):
            return f"рекурсивный `{base} -R` по системной/корневой цели"
    elif base == "find":
        roots = _positional(tokens)
        if (
            roots
            and _dangerous_target(roots[0])
            and ("-delete" in low or ("-exec" in low and "rm" in low))
        ):
            return "`find` по системному/корневому/домашнему корню с -delete/-exec rm"
    return None


override_used = False

for _seg in _segments(cmd):
    toks = _tokenize(_seg)
    # снимаем ведущие env-присваивания; префикс WW_DESTRUCTIVE_OK=1 = обход этого сегмента
    seg_override = False
    while toks and _ASSIGN.match(toks[0]):
        if toks[0].lower() == "ww_destructive_ok=1":
            seg_override = True
        toks.pop(0)
    if seg_override:
        override_used = True
        continue
    if not toks:
        continue

    if _base(toks[0]) in _RUNNERS:
        # re-scan: найти настоящую команду за опциями/аргументами раннера
        for j in range(1, len(toks)):
            bj = _base(toks[j])
            if bj in _DANGER or bj.startswith("mkfs"):
                if _base(toks[j - 1]) in _SUBCMD_HOSTS:  # git rm / docker rm …
                    continue
                reason = _check(toks[j:])
                if reason:
                    _deny(reason)
                break
    else:
        reason = _check(toks)
        if reason:
            _deny(reason)

    # редирект в блок-устройство (оператор `>` вне кавычек, путь-цель де-квотим)
    if _redirects_to_device(_seg):
        _deny("редирект в блок-устройство (> /dev/…)")

if override_used:
    _emit(
        {
            "additionalContext": (
                "WW_DESTRUCTIVE_OK=1: файловая деструктивная операция пропущена хуком. "
                "Убедись, что пользователь явно одобрил точную цель и команду и есть путь отката."
            )
        }
    )
sys.exit(0)
