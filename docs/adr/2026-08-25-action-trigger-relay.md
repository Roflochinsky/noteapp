# ADR: триггер Action — релей push → workflow_dispatch

Дата: 2026-08-25 · Статус: принято · Утверждено владельцем 2026-08-25 (выбор в сессии:
«Чинить + прогнать застрявшие») · Меняет реализацию решения Q4 спеки
`docs/specs/2026-08-24-note-format.md`.

## Проблема

Спека (Q4) требует запускать обработку «мгновенно после пуша» — `on: push` по путям
`inbox/**`. Но `anthropics/claude-code-action` событие `push` не поддерживает вовсе
(`src/github/context.ts`: только `workflow_dispatch`, `repository_dispatch`, `schedule`,
`workflow_run` и события issues/PR; остальное — `throw "Unsupported event type"`).
Из-за этого ни один push-запуск не отработал: заметки копились в `inbox/` без саммари.

## Решение

Продуктовый контракт не меняется: пуш в `inbox/` по-прежнему запускает обработку сразу.
Меняется механика — в `process-notes.yml` два джоба:

- **relay** (`if: push`) — одной командой `gh workflow run` перезапускает этот же workflow
  как `workflow_dispatch` (нужен `permissions: actions: write`). Рекурсии нет:
  `workflow_dispatch` — документированное исключение из защиты GitHub от самозапуска
  через `GITHUB_TOKEN`.
- **process** (`if: workflow_dispatch`) — прежний шаг `claude-code-action` с прежним
  промптом. Промпт и формат заметки не тронуты.

Guard `github.triggering_actor != 'claude[bot]'` в relay: пуш самого Action (перенос
файлов из `inbox/`) не запускает холостой Claude-прогон.

## Последствия

- Задержка обработки +10–20 секунд (второй запуск через релей). Приемлемо.
- Зависимость от имени `claude[bot]`: сменится app-имя у action — guard перестанет
  экономить холостые прогоны (но ничего не сломает: промпт корректно завершает работу
  при пустом `inbox/`).
- Эталон workflow — `docs/examples/process-notes.yml`; рабочая копия —
  `voice-notes/.github/workflows/process-notes.yml`. Менять синхронно.

## Отклонено

- Ждать поддержки `push` в action — сроков нет, заметки копятся.
- `schedule` (cron) — теряет «мгновенно после пуша».
- Прямой запуск Claude Code CLI в workflow — больше подвижных частей, свой парсинг
  OAuth; релей — одна строка.
