#!/usr/bin/env bash
# Проводник по прогону зонда P1–P9 на OnePlus 13 (OxygenOS 15).
# Запускать НА МАШИНЕ, где телефон подключён по USB с включённой отладкой.
# Скрипт ничего не решает за тебя — он даёт команды, показывает результат и спрашивает,
# что ты увидел. Ответы копятся в docs/research/probe-results.md.
#
# Перед запуском на телефоне: Настройки → О телефоне → 7 тапов по «Номер сборки» →
# Настройки → Система → Для разработчиков → включить «Отладка по USB».

set -uo pipefail

PKG="com.roflochinsky.noteapp"
ASSIST="$PKG/.assist.AssistService"
APK="app/build/outputs/apk/debug/app-debug.apk"
OUT="docs/research/probe-results.md"
ADB="${ADB:-adb}"

log() { printf '\n\033[1;36m%s\033[0m\n' "$*"; }
ask() {
  # ask "<P-код> <вопрос>" -> дописывает ответ в протокол
  local q="$1" ans
  printf '\033[1;33m%s\033[0m\n> ' "$q"
  read -r ans
  printf -- '- **%s**\n  %s\n' "$q" "${ans:-（пусто）}" >> "$OUT"
}
pause() { printf '\033[0;32m[Enter — продолжить]\033[0m '; read -r _; }

command -v "$ADB" >/dev/null || { echo "adb не найден. Установи platform-tools или задай ADB=путь"; exit 1; }
[ -f "$APK" ] || { echo "Нет APK: $APK — собери: ./gradlew assembleDebug"; exit 1; }

mkdir -p "$(dirname "$OUT")"
{
  echo "# Протокол прогона зонда P1–P9"
  echo
  echo "Устройство: OnePlus 13, OxygenOS ____ (уточни: Настройки → О телефоне)."
  echo "Дата прогона: $(cat /proc/uptime >/dev/null 2>&1 && echo "$(date -u '+%Y-%m-%d %H:%M UTC' 2>/dev/null || echo '____')")"
  echo
} > "$OUT"

log "Жду устройство…"; "$ADB" wait-for-device
"$ADB" devices -l | sed -n '2p'
pause

log "Установка APK"
"$ADB" install -r "$APK" && echo "OK" || echo "УСТАНОВКА НЕ УДАЛАСЬ — см. вывод выше"
pause

log "Выдать RECORD_AUDIO и POST_NOTIFICATIONS (роль микрофон не предгрантит, research §1.6)"
"$ADB" shell pm grant "$PKG" android.permission.RECORD_AUDIO || true
"$ADB" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
echo "выдано (или уже было)"
pause

# ---------- P1 ----------
log "P1. Пикер «Цифровой ассистент» и сторонний ассистент"
echo "Текущий держатель роли:"
"$ADB" shell cmd role get-role-holders android.app.role.ASSISTANT
echo "Текущие secure-настройки:"
"$ADB" shell settings get secure assistant
"$ADB" shell settings get secure voice_interaction_service
echo
echo "На телефоне: Настройки → Приложения → Приложения по умолчанию → Цифровой ассистент"
echo "(на OxygenOS путь мог измениться — ищи «Ассистент»/«Digital assistant»)."
ask "P1: есть ли «noteapp probe» в списке ассистентов и удалось ли выбрать его? (да/нет + путь, где нашёл)"
echo "Если в UI не дало выбрать — обход через adb:"
echo "  $ADB shell cmd role add-role-holder android.app.role.ASSISTANT $PKG"
ask "P1-обход: понадобился ли обход adb, сработал ли? (не нужен/сработал/ошибка)"
pause

# ---------- P2 ----------
log "P2. Значение long-press power и настраиваемость"
echo "Пользовательское значение (ожидаем 5=ассистент или пусто):"
"$ADB" shell settings get global power_button_long_press
echo "Политика окна:"
"$ADB" shell dumpsys window policy | grep -iE "LongPressOnPower|PowerAssistantTimeout" || echo "(строк не найдено)"
echo
echo "На телефоне проверь: Настройки → (Спец.возможности и удобство / Кнопка питания) →"
echo "«Удерживать кнопку питания» → есть ли выбор «Ассистент»/«Digital assistant»."
ask "P2: есть ли пункт и даёт ли выбрать ассистента? Если нет — выполнить обход и записать:"
echo "  Обход: $ADB shell settings put global power_button_long_press 5"
pause

# ---------- P3 ----------
log "P3. ГЛАВНОЕ: перехват вызова лончером (Gemini вместо нас)"
echo "Сейчас пойдёт живой logcat. НЕ закрывай это окно."
echo "Во втором терминале это же окно вести не надо — просто СДЕЛАЙ на телефоне:"
echo "  экран ВКЛючён и разблокирован → зажми кнопку питания (long-press)."
echo "Смотри строки PROBE ниже. Ctrl+C когда увидишь результат."
pause
"$ADB" logcat -c
echo "…слушаю (зажми питание сейчас; Ctrl+C для остановки)…"
"$ADB" logcat -s PROBE:* AssistManager:* VoiceInteractionManagerService:* || true
ask "P3: пришёл ли PROBE:ONSHOW нашего зонда? Или всплыл Gemini/другой ассистент? (вставь ключевые строки)"
pause

# ---------- P4 ----------
log "P4. Три сценария экрана (самый важный неизвестный — погашенный экран)"
echo "Будем ловить PROBE:ONSHOW в каждом сценарии. Перед каждым — чищу лог."
for SC in "экран ВКЛючён, разблокирован" "экран ВКЛючён, ЗАБЛОКИРОВАН (нажми power один раз, не разблокируй, потом long-press)" "экран ПОГАШЕН (подожди, пока погаснет, затем long-press)"; do
  echo; log "P4 · сценарий: $SC"
  "$ADB" logcat -c
  echo "Сделай на телефоне и следи за строками. Ctrl+C после результата."
  pause
  "$ADB" logcat -s PROBE:* PhoneWindowManager:* || true
  ask "P4 [$SC]: пришёл ли PROBE:ONSHOW? какое screenOn=/keyguard= в строке? запись пошла?"
done
echo "Косвенно про погашенный экран:"
"$ADB" logcat -d | grep -i "Not support long press power when device is not interactive" && \
  echo "^ строка есть → config_supportLongPressPowerWhenNonInteractive=false (погашенный не зовёт)" || \
  echo "(строки нет — либо экран вызвал, либо не проявилось)"
pause

# ---------- P5 ----------
log "P5. Тумблер: второе нажатие останавливает"
"$ADB" logcat -c
echo "На телефоне: long-press power (старт) → подожди 5с → long-press power (стоп)."
echo "Жду PROBE:TOGGLE / REC_START / REC_STOP. Ctrl+C после."
pause
"$ADB" logcat -s PROBE:* || true
ask "P5: два разных session= в PROBE:ONSHOW? пришли decision=start и decision=stop? минимальный интервал, при котором оба дошли?"
pause

# ---------- P6 ----------
log "P6. Микрофон из фона: в файле реально есть звук"
echo "Файлы зонда на устройстве:"
"$ADB" shell run-as "$PKG" ls -l files/probe/ 2>/dev/null || \
  "$ADB" shell ls -l /sdcard/Android/data/$PKG/files/probe/ 2>/dev/null || \
  echo "(каталог недоступен через adb — посмотри размер на экране статуса приложения)"
echo "Проверь: последний REC_STOP показывал bytes>0 и maxAmp>0 (тишина = maxAmp≈0)."
"$ADB" logcat -d | grep "PROBE:REC_STOP" | tail -2
echo "appops микрофона:"
"$ADB" shell appops get "$PKG" RECORD_AUDIO || true
"$ADB" logcat -d | grep -i "can not have .*microphone" && echo "^ ЕСТЬ отказ микрофона из фона!" || echo "(отказов микрофона в логе нет)"
ask "P6: bytes>0 и maxAmp>0 в REC_STOP? был ли PROBE:REC_FAIL или SecurityException?"
pause

# ---------- P7 ----------
log "P7. Выживаемость (OxygenOS убивает фон)"
echo "Запусти запись (long-press) и оставь телефон с ПОГАШЕННЫМ экраном на 30–60 мин."
echo "Совет заранее: замок в Recents, Battery → не оптимизировать, выключить Sleep standby"
echo "(чеклист — docs/research/assistant-role.md §5.5)."
echo "Диагностика убийцы (если запись прервалась):"
echo "  $ADB logcat -d | grep -iE 'BgDetect|OppoBgApp|died|SecurityException'"
ask "P7: пережил ли FGS 30–60 мин с погашенным экраном? если убит — что в логе (BgDetect и т.п.)?"
pause

# ---------- P8 ----------
log "P8. Устойчивость роли к переустановке APK (recognitionService, §1.4a)"
echo "Держатель роли ДО переустановки:"
"$ADB" shell cmd role get-role-holders android.app.role.ASSISTANT
"$ADB" install -r "$APK"
echo "Держатель роли ПОСЛЕ переустановки (должен остаться noteapp):"
"$ADB" shell cmd role get-role-holders android.app.role.ASSISTANT
ask "P8: роль осталась за noteapp после переустановки? (да/нет)"
pause

# ---------- P9 ----------
log "P9. Конфликт жестов: меню выключения не сломалось"
echo "Проверь на телефоне: Power+VolumeUp должно давать меню выключения (KEY_CHORD=2)."
"$ADB" shell settings get global key_chord_power_volume_up || true
ask "P9: меню выключения доступно (Power+VolUp)? нет второго обработчика удержания питания?"
pause

# ---------- ИТОГ ----------
log "Вариант A vs B"
echo "Если P3/P4 показали PROBE:ONSHOW — работает вариант A (VoiceInteractionService)."
echo "Если A молчит/перехвачен, переключись на B и повтори P3:"
echo "  выключить A:  $ADB shell pm disable-user --user 0 $ASSIST"
echo "  ПОСЛЕ переключения проверь роль (может слететь, §1.4a):"
echo "     $ADB shell cmd role get-role-holders android.app.role.ASSISTANT"
echo "     при сбросе:  $ADB shell cmd role add-role-holder android.app.role.ASSISTANT $PKG"
echo "  вернуть A:    $ADB shell pm enable $ASSIST"
ask "ИТОГ: вариант триггера для v1 — A | B | ни один (фича переопределяется). Что это меняет для карты?"

log "Готово. Протокол: $OUT — просмотри и, если надо, допиши руками."
