---
name: update-docs
description: Use when about to run git commit with code changes that may touch documentation — note format or repo-of-notes layout, Action prompt or workflow contract, Deepgram parameters, assistant-role wiring, onboarding steps, or any ADR-gate decision. Symptom — staged edits before a commit.
---

# Обновление документации перед коммитом

## Суть

Документация (`CONTEXT.md`, `docs/adr/`, спеки, `CLAUDE.md`, `PRODUCT.md`) должна отражать
код. Правь **только затронутое**, точечно; документы целиком не переписывай.

## Когда применять

- Перед `git commit`, когда staged-правки меняют поведение.
- **Не нужно**, если правка поведение не меняет (рефактор, тест, опечатка, стиль) и не
  трогает ADR-гейт — просто коммить.

## Шаги

1. **Что менял?** `git diff --staged --stat`
2. **Сопоставь изменение → документ** (таблица ниже).
3. **ADR-гейт?** Если да — сперва ADR (это approval-гейт), потом код.
4. **Правь точечно** и `git add` доки вместе с кодом — одним коммитом.

## Сопоставление: изменение → документ

| Изменение в коде | Что обновить |
|---|---|
| Формат заметки `.md` / раскладка папок репо заметок | ADR формата + спека в `docs/specs/` |
| Промпт Action / контракт workflow | спека + ADR, если меняется необратимо |
| Параметры Deepgram (модель, language, диаризация) | `docs/research/stt-choice.md` — пометка «превзойдено», ADR |
| Ассистент-роль, триггер кнопки, FGS | `docs/research/assistant-role.md` — пометка, спека |
| Новый термин или смена смысла старого | `CONTEXT.md` (глоссарий) |
| Шаги онбординга / разрешения | `PRODUCT.md` → Capabilities, спека |
| Команды сборки/проверок | `CLAUDE.md` → Обязательная верификация |
| Любой ADR-гейт (`CLAUDE.md` → ADR-гейт) | новый/обновлённый ADR в `docs/adr/` |

## Частые ошибки

- Переписал документ целиком вместо точечной правки.
- Тронул ADR-гейт в коде, а ADR «потом» — ADR идёт **первым**, это гейт.
- Код и доки ушли разными коммитами — должны одним.
