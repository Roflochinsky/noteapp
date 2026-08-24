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
- Контракт тумблера: сессия/активити шлют всегда `ACTION_TOGGLE`; решение start/stop/dup —
  идемпотентно в `RecordingService.onStartCommand` (вердикт LLD-1). В onShow — только прямой
  синхронный `startForegroundService`, затем `hide()` (LLD-2). Отказы записи ловятся и
  логируются `PROBE:REC_FAIL` (LLD-3). Лог-контракт PROBE — из вердикта LLD-5.
- Демонстрация: гейты зелёные + APK + `aapt2 dump xmltree` показывает оба варианта триггера
  в манифесте + юнит тумблера; динамика на железе — С3 (вердикт HLD-2).

### С3 — прогон P1–P9 на устройстве (HITL)

- Wizard-скрипт `scripts/probe-wizard.sh` (скилл mattpocock-skills:wizard): по шагу на каждый
  P из docs/research/assistant-role.md, adb-команды готовыми строками, ожидаемый результат,
  поле «что увидел». После КАЖДОГО переключения варианта A/B — проверка
  `cmd role get-role-holders` и перевыдача роли при сбросе (вердикт HLD-1). Прогоняет владелец — телефон агент не трогает.
- Результаты → `docs/research/probe-results.md`: вердикт по P1–P9 + итоговая строка
  «вариант триггера для v1».
- По результатам: закрыть тикет карты nikitatrubaev-pdj.3 резолюцией, при необходимости
  обновить туман карты (например, «старт только при включённом экране» меняет продуктовое
  ожидание).

## Вердикты ревью замысла (2026-08-24)

**Оси прогнаны ведущим** — оба сабагента-ревьюера не сделали ни одного вызова инструмента за
20 минут (правило docs/harness/epic.md: не стартовали за 3 минуты → ведущий гонит сам и
пишет это в вердикт). Зависание канала — то же, что замер workwatch 2026-08-21.

### Ось HLD — CHANGES REQUESTED (исправлено ниже)

1. **Переключение A/B может ронять роль.** `pm enable/disable-user` компонента — это
   package-change событие; PackageMonitor платформы (research §1.4a) на нём перепроверяет
   ассистента. Правка: в wizard С3 после КАЖДОГО переключения варианта — проверить
   `cmd role get-role-holders android.app.role.ASSISTANT` и перевыдать роль при сбросе.
2. **Демонстрация С2 обещала невозможное.** `am start` требует устройство/эмулятор;
   эмулятор в WSL2 на Win10 (без вложенной виртуализации) не гарантирован. Правка:
   демонстрация С2 = зелёные гейты + APK + структурная проверка манифеста
   (`aapt2 dump xmltree` — оба варианта на месте) + юнит тумблера; вся динамика — С3.
3. Рост в v1 — ок: шов пайплайна = завершение записи в RecordingService; схема §6 не
   перекраивается.

### Ось LLD — CHANGES REQUESTED (исправлено ниже)

1. **Контракт тумблера — ACTION_TOGGLE, истина в сервисе.** Двойной onShow (дребезг) при
   решении «start/stop» в сессии даёт двойной START. Правка: сессия шлёт всегда
   `ACTION_TOGGLE`; `RecordingService.onStartCommand` (сериализован main-тредом) сам решает
   start/stop идемпотентно (START при записи = лог DUP и игнор; STOP при idle = no-op).
   `ToggleStateTest` покрывает: idle→start; recording→stop; два toggle подряд → start,stop
   (не двойной start); stop-при-idle → no-op.
2. **onShow: только прямой синхронный вызов** `startForegroundService` в теле onShow —
   никаких корутин/Handler.post/lifecycleScope (окно привилегии, research §4.3); `hide()`
   строго после; `setUiEnabled(false)` в onCreate. Лог до/после вызова.
3. **Отказы записи логируются, не роняют прогон**: SecurityException (нет RECORD_AUDIO),
   mic busy, prepare/start — ловить, `PROBE:REC_FAIL <причина>`; иначе P6 даст ложное
   «красное» на банальном разрешении.
4. targetSdk 35 оставить (совпадает с устройством); при странностях FGS —
   диагностический откат на 34 (некритичное).
5. **Лог-контракт PROBE** (однозначно различает исходы P2–P6):
   `PROBE:ONSHOW invocation_type= session= screenOn= keyguard=` ·
   `PROBE:ASSIST_ACTIVITY action=` · `PROBE:TOGGLE decision=start|stop|dup` ·
   `PROBE:FGS_STARTED` / `PROBE:FGS_FAIL <ex>` ·
   `PROBE:REC_START file=` / `PROBE:REC_STOP bytes= durMs= maxAmp=` / `PROBE:REC_FAIL <msg>`.

Находки 1–2 (HLD) и 1–3, 5 (LLD) внесены в срезы ниже до первой итерации.

## Попытки

(журнал «попытка N по срезу <id>: что красное» — сюда)

- попытка 1 по срезу qt2.1: исполнитель-сабагент не сделал ни одного вызова инструмента за
  5+ минут (тот же отказ, что у двух ревьюеров замысла; совпадает по времени с ошибкой
  «claude-fable-5 not available» у владельца). Срез взят ведущим по правилу harness.
  На следующий диспатч — пульс-монитор (Monitor) с момента спавна.
- попытка 2 по срезу qt2.1: ktfmtCheck красный — MainActivity.kt не в kotlinlang-формате
  (рукописный стиль). Фикс: ktfmtFormat.
- попытка 3 по срезу qt2.1: checkDebugAarMetadata красный — забыт android.useAndroidX=true
  в gradle.properties проекта. Фикс: добавлен.

## Вердикты ревью

(письменные вердикты code-review по срезам — сюда)

## Retro

(4 вопроса из docs/harness/epic.md — при закрытии эпика)
