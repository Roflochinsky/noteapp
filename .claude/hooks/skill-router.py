#!/usr/bin/env python3
"""UserPromptSubmit: подсказывает нужный скилл по теме запроса.

Диспетчер в CLAUDE.md — текст, который легко проскочить. Хук делает тот же выбор
детерминированно и до начала работы. Не блокирует: если правило ошиблось, ведущий
просто не вызывает предложенный скилл. Перенесено из workwatch, правила — noteapp.
"""

import json
import re
import sys

# (регулярка по запросу, имя скилла, зачем). Порядок = приоритет. Процессные скиллы
# идут раньше реализационных — они определяют, КАК подходить к задаче.
RULES: list[tuple[str, str, str]] = [
    (
        r"ревью замысла|проверь замысел|ревью плана|ревью спеки",
        "review-intent",
        "ревью замысла до первой строки кода — оси по типу работы",
    ),
    (
        r"ревью|code[- ]?review|проверь код|посмотри код|отревьюй",
        "mattpocock-skills:code-review",
        "ревью кода",
    ),
    (
        r"\bбаг\b|ошибк|не работает|падает|сломал|тормоз|медленн|виснет|performance",
        "mattpocock-skills:diagnosing-bugs",
        "дефект или производительность — сначала причина, потом правка",
    ),
    (
        r"обсуд|спроектир|продум|давай подума|не понятно как|как лучше сделать",
        "discuss",
        "решение не определено — сначала интервью с протоколом, потом код",
    ),
    (
        r"adr|формат заметк|frontmatter|имя файла заметк|контракт action|промпт action|"
        r"deepgram|nova-3|диариз|ассистент-рол|action_assist|хранени\w* аудио|приватност",
        "adr",
        "тема из ADR-гейта — решение записывается ADR до правки",
    ),
    (
        r"\borca\b|epic-orca",
        "epic-orca",
        "пилот формы 2 — цикл эпика воркерами Orca",
    ),
    (
        r"\bкарт\w*\b|wayfinder|фронтир|туман",
        "mattpocock-skills:wayfinder",
        "работа по wayfinder-карте (эпик nikitatrubaev-pdj) — решения, не код",
    ),
    (
        r"\bспек\w*\b|specification|зафиксируй решени|\bплан\b|распланир|декомпоз|"
        r"разбей на задач|разбей на срез|\bэпик\w*\b|тяжёл|тяжел",
        "epic",
        "тяжёлая задача: спека, срезы, ревью замысла и петля исполнения",
    ),
    (
        r"готово|заверши|закончи|можно закрывать|сдать работу",
        "CLAUDE.md → Обязательная верификация",
        "перед словом «готово» прогнать команды затронутых слоёв",
    ),
]

# UI-правка — не процессный скилл, а жёсткое ограничение; добавляется поверх.
UI_RE = r"дизайн|вёрстк|верстк|интерфейс|экран|кнопк|цвет|шрифт|макет|ui\b|ux\b"

try:
    data = json.load(sys.stdin)
except Exception:
    sys.exit(0)

prompt = (data.get("prompt") or "").lower()
if not prompt:
    sys.exit(0)

hits = [(skill, why) for pattern, skill, why in RULES if re.search(pattern, prompt)]
if re.search(UI_RE, prompt):
    hits.append(
        (
            "impeccable:impeccable",
            "правка UI — утверждённый комп (тикет nikitatrubaev-pdj.4) и DESIGN.md "
            "(когда появится) — жёсткое ограничение",
        )
    )

if not hits:
    print(
        json.dumps(
            {
                "hookSpecificOutput": {
                    "hookEventName": "UserPromptSubmit",
                    "additionalContext": (
                        "Маршрутизация: правила под эту тему нет. Проверь карту скиллов в "
                        "CLAUDE.md глазами — таблица шире правил хука. Если задача "
                        "повторяющаяся и скилла под неё не существует, заведи его через "
                        "mattpocock-skills:writing-for-agents и добавь правило сюда."
                    ),
                }
            },
            ensure_ascii=False,
        )
    )
    sys.exit(0)

lines = "\n".join(f"  • {skill} — {why}" for skill, why in hits[:3])
print(
    json.dumps(
        {
            "hookSpecificOutput": {
                "hookEventName": "UserPromptSubmit",
                "additionalContext": (
                    "Маршрутизация скиллов (не блокирует, правило могло ошибиться — "
                    "тогда игнорируй). По теме запроса подходит:\n"
                    f"{lines}\n"
                    "Скиллы вызываются инструментом Skill; строка без префикса — раздел "
                    "правил, его надо прочитать. Полная карта: CLAUDE.md."
                ),
            }
        },
        ensure_ascii=False,
    )
)

sys.exit(0)
