# План: зонд кнопки питания

Спека: docs/specs/2026-08-24-probe-power-button.md. Это LLD и журнал исполнения: попытки,
вердикты ревью, ретро — сюда.

## Срезы (LLD)

### С1 — префактор: Gradle-каркас с гейтами

- `app/` в корне noteapp: Kotlin, Compose (minSdk 34 — телефон один, Android 15; targetSdk 35).
- Gradle-плагины: ktfmt (kotlinlang style), detekt (дефолтный конфиг + отчёт в консоль),
  Android Lint (abortOnError=true), юнит-тесты JVM.
- Версии ktfmt в Gradle и pre-commit — синхронно (правило CLAUDE.md).
- Пустой экран «noteapp probe» на Compose. CLAUDE.md: вписать финальные команды блока
  «Приложение».
- Демонстрация: зелёный полный гейт + путь к APK.

### С2 — зонд-функциональность

- Компоненты по спеке п.3 (research §6). Вариант A и B в одном манифесте; переключение:
  `adb shell pm enable/disable-user` компонента VIS — без пересборки (двух build flavors не
  заводим: ponytail, enable/disable компонентов достаточно).
- `RecordingService`: MediaRecorder → `files/probe/<timestamp>.m4a`, foreground-уведомление
  с таймером (без дизайна), состояние тумблера — object в памяти сервиса.
- `ToggleSession.onShow`: лог PROBE (invocation_type, session id), тумблер, синхронный
  startForegroundService, затем hide().
- Экран статуса: RoleManager.isRoleHeld, жив ли FGS, список файлов в files/probe/.
- Юнит: логика тумблера (`ToggleStateTest`).
- Демонстрация: гейты зелёные; в эмуляторе/adb — `am start`-вызов активити B поднимает FGS
  и пишет файл (полная проверка триггера — только на железе, это С3).

### С3 — прогон P1–P9 на устройстве (HITL)

- Wizard-скрипт `scripts/probe-wizard.sh` (скилл mattpocock-skills:wizard): по шагу на каждый
  P из docs/research/assistant-role.md, adb-команды готовыми строками, ожидаемый результат,
  поле «что увидел». Прогоняет владелец — телефон агент не трогает.
- Результаты → `docs/research/probe-results.md`: вердикт по P1–P9 + итоговая строка
  «вариант триггера для v1».
- По результатам: закрыть тикет карты nikitatrubaev-pdj.3 резолюцией, при необходимости
  обновить туман карты (например, «старт только при включённом экране» меняет продуктовое
  ожидание).

## Попытки

(журнал «попытка N по срезу <id>: что красное» — сюда)

## Вердикты ревью

(письменные вердикты code-review по срезам — сюда)

## Retro

(4 вопроса из docs/harness/epic.md — при закрытии эпика)
