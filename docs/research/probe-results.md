# Протокол прогона зонда P1–P9

Устройство: OnePlus 13 (CPH2649), Android 15, OxygenOS build V.R4T3.29fcc26-aee5f1-b10b4b.
Связь: USB через мост Windows-adb → WSL (беспроводной паринг TLS через WSL не прошёл).
Прогон ведёт ведущий по adb; физические нажатия — владелец.

## P1. Пикер ассистента / сторонний ассистент
- ✅ Наш зонд назначен ассистентом через `cmd role add-role-holder` (вариант A,
  VoiceInteractionService прошёл квалификацию). Держатель роли = com.roflochinsky.noteapp,
  secure.assistant = com.roflochinsky.noteapp/.assist.AssistService.
- Было: Google (googlequicksearchbox / GsaVoiceInteractionService).
- ⚠️ `pm grant RECORD_AUDIO` через adb ЗАБЛОКИРОВАН OxygenOS (SecurityException,
  GRANT_RUNTIME_PERMISSIONS). Разрешение микрофона выдаётся только руками (кнопка в
  приложении / Настройки). Для v1: онбординг обязан вести к ручной выдаче.
- Проверку «виден ли в штатном UI-пикере» отдельно не делали — adb-путь сработал.

## P2. Long-press power
- ✅ mLongPressOnPowerBehavior = LONG_PRESS_POWER_ASSISTANT (значение 5, дефолт AOSP —
  OnePlus НЕ переопределил). Таймаут 500 мс. power_button_long_press (global) = null (нет
  пользовательского override). ⇒ удержание питания идёт в держателя роли = наш зонд.

## P3. Перехват лончером
- ✅ Long-press power (screenOn, unlocked) → PROBE:ONSHOW invocation_type=6
  (6=POWER_BUTTON_LONG_PRESS), session растёт (5,6…). Перехвата НЕТ — вызов дошёл до нашего
  ассистента. Вариант A (VoiceInteractionService) на OnePlus 13 рабочий.
- ⚠️ Аномалия при незагранченном микрофоне: на каждое нажатие TOGGLE decision=start дважды,
  ни одного stop — запись падает (нет RECORD_AUDIO), сервис умирает, ToggleState сбрасывается.
  Перепроверить P5 (тумблер) после выдачи микрофона.

## P5. Тумблер (второе нажатие)
- ✅ После выдачи микрофона: session 7→8→9→10, decision start→stop→start→stop, один процесс
  (pid 8700), состояние держится. REC_STOP: bytes=9062 durMs=3908; bytes=20080 durMs=11198.
- Ранняя «двойная start» была артефактом падавшей записи без разрешения — устранена.

## P6. Микрофон из фона (звук реальный)
- ✅ Файл вытащен (run-as), ffmpeg volumedetect: mean_volume −25.7 dB, max −1.0 dB (тишина
  была бы ≈ −91 dB). Микрофон пишет реальную речь при вызове из ассистента (экран включён).
  Формат: AAC 8000 Hz mono (для v1 поднять sample rate).
- ⚠️ Код: MediaRecorder.getMaxAmplitude() вернул 0 (причуда тайминга) — для v1 не опираться
  на него как на признак тишины; файл/громкость проверять иначе. Некритично для зонда.
- Находка (исправлена в коде по ходу): без RECORD_AUDIO startForeground(type=microphone)
  бросал SecurityException и ронял приложение — добавлена проверка разрешения до FGS.
- ⚠️ `pm grant` через adb заблокирован OxygenOS — микрофон выдаётся только через UI.

## P8. Устойчивость роли к переустановке
- ✅ Роль ассистента пережила `adb install -r` (recognitionService-заглушка работает, §1.4a).

## P4. Три сценария экрана
- ✅ Сценарий 1 (экран включён, разблокирован): ONSHOW invocation_type=6, запись идёт (см. P3).
- ✅ Сценарий 2 (экран включён, ЗАБЛОКИРОВАН, keyguard=true): ONSHOW session=11
  screenOn=true keyguard=true → start→FGS→REC_START, stop bytes=7829. Локскрин с включённым
  экраном — работает.
- ✅ Сценарий 3 (экран ПОГАШЕН): ONSHOW session=13, invocation_type=6 — сработал. Нажатие
  будит экран (screenOn=true в onShow), ассистент вызывается, запись идёт (bytes=10415).
  Строки "Not support long press power when device is not interactive" в логе НЕТ ⇒ OnePlus
  не блокирует вызов с погашенного экрана. Красный риск research #1 снят положительно.
  Оговорка: экран на миг загорается (разблокировка не нужна) — «запись не зажигая экран»
  недостижима, но продуктовый сценарий «достал из кармана → зажал → пишет» работает.

## P9. Конфликт жестов
- key_chord_power_volume_up = см. вывод прогона; меню выключения переезжает на Power+VolUp
  при поведении «ассистент» (штатно для AOSP). Второго обработчика удержания не замечено.

## P7. Выживаемость 30–60 мин — НЕ прогнан в этой сессии
- Отложено: это про агрессивный менеджмент OxygenOS (research §5, DontKillMyApp 5/5), не про
  сам механизм триггера. В v1 закрывается чеклистом §5.5 (замок в Recents, отключить
  оптимизацию батареи и Sleep standby) + самопроверка при старте. Прогнать на этапе v1.

## Вердикт
- **Вариант триггера для v1: A (VoiceInteractionService).** Работает на OnePlus 13 /
  OxygenOS 15 полностью: long-press power (invocation_type=6) во всех трёх состояниях экрана,
  тумблер start/stop, запись реального звука из фона, роль переживает переустановку. Перехвата
  лончером НЕТ. Вариант B (ACTION_ASSIST) не понадобился — оставить в коде как спящий фолбэк.
- Открытые хвосты для v1: (1) RECORD_AUDIO выдаётся только через UI (adb pm grant блокирован
  OxygenOS) — онбординг обязан вести к ручной выдаче; (2) роль назначается через системный
  пикер/adb, не программно (requestable=false) — онбординг ведёт в настройки; (3) sample rate
  записи поднять с 8 кГц; (4) getMaxAmplitude ненадёжен; (5) выживаемость фона P7 — чеклист §5.5.
