# noteapp — правила для агентов

Личный Android-диктофон (OnePlus 13): long-press питания → запись → Deepgram → `.md` →
приватный GitHub-репо → Claude-Action пишет саммари. Продуктовая правда — `PRODUCT.md`,
глоссарий — `CONTEXT.md`, решения — wayfinder-карта `bd show nikitatrubaev-pdj` и `docs/adr/`.

## Обязательная верификация

Перед словом «готово» прогнать блоки затронутых слоёв и показать реальный вывод.

| Слой | Команды |
|---|---|
| Приложение (Kotlin) | `JAVA_HOME=~/.local/java/jdk17 ./gradlew ktfmtCheck detekt lint testDebugUnitTest assembleDebug` |
| GitHub Actions | `actionlint docs/examples/process-notes.yml` (эталон для репо заметок; своих workflow у репо нет) |
| Хуки харнесса | `python3 .claude/hooks/tests/test_destructive_fs_guard.py` |
| Всё разом (гигиена) | `pre-commit run --all-files` |

**Чем блок «Приложение» падает не по делу** (за ночь 2026-08-26 на это налетели двое):
`java` в PATH нет — JDK лежит в `~/.local/java/jdk17` (Temurin 17), отсюда `JAVA_HOME=…` в
команде. И `local.properties` (`sdk.dir`) не в git — в свежем git-worktree его просто нет,
Gradle падает на «SDK location not found»; скопировать из основного рабочего дерева:
`cp ~/code/noteapp/local.properties .`.

Инструменты — лучшие в классе, зафиксировано переносом harness 2026-08-24
(bd nikitatrubaev-rvw): **ktfmt** (формат, безкомпромиссный), **detekt** (статанализ),
**Android Lint**, **actionlint** (workflows). Версии пинить в Gradle и pre-commit синхронно —
разъехавшиеся версии заставляют хук и CI форматировать по-разному (прецедент workwatch).

## ADR-гейт

Решения, которые нельзя менять без ADR в `docs/adr/` и подписи владельца:

- формат заметки `.md` (frontmatter, имена файлов, раскладка папок репо заметок);
- контракт GitHub Action (промпт, триггер, что и куда пишет);
- выбор STT и его ключевые параметры (сейчас: Deepgram nova-3, диаризация);
- механизм триггера записи (ассистент-роль / ACTION_ASSIST);
- всё, что касается хранения аудио и приватности.

## Окружения и безопасность

- **Телефон владельца — не стенд.** Установка APK, смена роли ассистента, системные
  настройки — только по явному указанию владельца.
- Реальный GitHub-репо заметок и ключи (Deepgram, GitHub token, CLAUDE_CODE_OAUTH_TOKEN) —
  только по явному указанию; для тестов — отдельный тестовый репо.
- Деструктивные операции блокирует хук `destructive-fs-guard`; обход `WW_DESTRUCTIVE_OK=1` —
  только после явного одобрения владельцем точной команды.

## Карта скиллов

Хук `skill-router` подсказывает автоматически; полная карта:

| Ситуация | Скилл |
|---|---|
| Тяжёлая задача, спека, срезы, петля исполнения | `epic` (наш, см. `docs/harness/epic.md`) |
| Спека из принятых решений | `to-spec` |
| Срезы из спеки в beads | `to-tickets` |
| Решения ещё не приняты | `grill-me` / `mattpocock-skills:grilling` |
| Работа по карте решений | `mattpocock-skills:wayfinder` (v1 — nikitatrubaev-pdj, закрыта; v2 — nikitatrubaev-5dd) |
| Ревью кода | `mattpocock-skills:code-review` |
| Баг, не работает, медленно | `mattpocock-skills:diagnosing-bugs` |
| Любая правка UI | `impeccable:impeccable` — `DESIGN.md` + компы «Документ» v1 (nikitatrubaev-pdj.4) и v2 (nikitatrubaev-5dd.3) — жёсткое ограничение |
| Доки при коммите | `update-docs` |
| Непонятно объяснил | `/wait-what` (зовёт владелец) |

## Beads

Трекер — `bd` (workspace домашний). Задачи проекта — с описанием «Продукт: …» первой
строкой. Wayfinder-карта — эпик `nikitatrubaev-pdj`; строительные эпики — свои id.
TodoWrite и markdown-TODO не использовать.
