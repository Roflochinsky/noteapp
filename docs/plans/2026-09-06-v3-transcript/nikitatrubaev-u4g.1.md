# Журнал среза nikitatrubaev-u4g.1 — заметка распознаётся ElevenLabs Scribe v2

Спека: `docs/specs/2026-09-06-v3-transcript.md` (часть 1, критерии 1–4) · План:
`docs/plans/2026-09-06-v3-transcript.md` · ADR: `docs/adr/2026-09-06-stt-elevenlabs-scribe-v2.md`

## 1. Дерево

- Путь: `/home/nikitatrubaev/orca/workspaces/noteapp/nikitatrubaev-u4g.1`, ветка `nikitatrubaev-u4g.1`.
- `HEAD` на старте: `f8173f0f7c56e88fd8b21e97e447338c368b007d`; `HEAD` сдачи: `05ea1eb5ca63862fe261bc23d4bc59500cf8ed2c`.
- `git rev-list --left-right --count main...HEAD` на старте → `0	0`: дерево ровно на локальном `main`, отставания нет.
- Базовая линия (цифру мерял сам): `bin/gate` rc=0, 55 с; **291** тест в 37 классах, падений 0, пропущено 7.
- После среза: **310** тестов в 39 классах (+19 тестов, +2 класса), падений 0, пропущено 7.

## 2. Гейты

```
гейты: 05ea1eb5ca63862fe261bc23d4bc59500cf8ed2c · bin/gate --full rc=0 50 с
```

Полный вывод: `/tmp/claude-1000/-home-nikitatrubaev-code-noteapp/f6448f97-85c9-429a-8a98-7b14a4fd648a/scratchpad/nikitatrubaev-u4g.1-gates.txt`

**Оговорка про кэш, честно.** Первый зелёный `bin/gate --full` (rc=0, 50 с, APK собран) прошёл на
этом же содержимом файлов до коммита. Повтор `bin/gate --full` уже на `05ea1eb` вернулся за 1 с с
`testDebugUnitTest FROM-CACHE` — это кэш, а не прогон (ловушка Gradle из `docs/harness/epic.md`).
Поэтому в файл вывода положен настоящий перепрогон на сданном HEAD:

```
bin/gate --no-build-cache ktfmtCheck detekt lint cleanTestDebugUnitTest testDebugUnitTest assembleDebug
rc=0 · 21 с · Task :app:testDebugUnitTest выполнен (не FROM-CACHE) · 310 тестов, 0 падений
```

## 3. Изменённые файлы против «Трогает:»

`git diff --name-only main...HEAD` — 12 файлов:

```
README.md
app/src/main/kotlin/com/roflochinsky/noteapp/MainActivity.kt
app/src/main/kotlin/com/roflochinsky/noteapp/pipeline/ElevenLabsClient.kt
app/src/main/kotlin/com/roflochinsky/noteapp/pipeline/Settings.kt
app/src/main/kotlin/com/roflochinsky/noteapp/pipeline/TranscribeWorker.kt
app/src/main/kotlin/com/roflochinsky/noteapp/pipeline/TranscriptMapper.kt
app/src/test/kotlin/com/roflochinsky/noteapp/pipeline/ElevenLabsClientTest.kt
app/src/test/kotlin/com/roflochinsky/noteapp/pipeline/TranscribeWorkerTest.kt
app/src/test/kotlin/com/roflochinsky/noteapp/pipeline/TranscriptMapperTest.kt
app/src/test/kotlin/com/roflochinsky/noteapp/ui/DetailScreenTest.kt
app/src/test/resources/elevenlabs-sample-response.json
docs/SETUP.md
```

**Расхождений со строкой «Трогает:» задачи `bd nikitatrubaev-u4g.1` нет: множества совпадают
файл в файл, 12 против 12.** Ни одного файла вне списка не тронуто; `DeepgramClient.kt` и
`fromDeepgramJson` не тронуты (путь отката ADR), `app/build.gradle.kts` и `OnboardingScreen.kt`
не тронуты.

## 4. Мутации

Спека: `<скратчпад>/nikitatrubaev-u4g.1-mutations.toml`, прогон на сданном HEAD одним заходом,
`scripts/mutate.py run --id nikitatrubaev-u4g.1` → rc=0.

HEAD 05ea1eb5ca63 · 2026-09-07 20:26 · управляющая: убита

| id | claim | find → replace | фильтр | результат | упавший тест |
|---|---|---|---|---|---|
| M1-порог-нестрогий | порог паузы строгий: ровно 1,5 с уже рвёт реплику | `startMs - prevEndMs < GAP_MS` → `startMs - prevEndMs <= GAP_MS` | *TranscriptMapperTest.пауза ровно 1500 мс рвёт реплику | убита | com.roflochinsky.noteapp.pipeline.TranscriptMapperTest::пауза ровно 1500 мс рвёт реплику |
| M2-порог-1300 | порог именно 1,5 с: пауза 1320 мс реплику не рвёт | `private const val GAP_MS = 1500` → `private const val GAP_MS = 1300` | *TranscriptMapperTest.пауза ниже порога не рвёт реплику | убита | com.roflochinsky.noteapp.pipeline.TranscriptMapperTest::пауза ниже порога не рвёт реплику |
| M3-событие-в-скобках | audio_event идёт в текст как пришёл, а не оборачивается ещё раз (как bin/stt-compare-html:65) | `val text = w.getString("text")` → `val text = if (type == "audio_event") "(" + w.getString("text") + ")" else w.getString("text")` | *TranscriptMapperTest.событие звука попадает в текст как пришло | убита | com.roflochinsky.noteapp.pipeline.TranscriptMapperTest::событие звука попадает в текст как пришло |
| M4-спикер-по-порядку | номер спикера — цифра из speaker_id, а не порядок появления | `val result = mutableListOf<Utterance>()⏎        var prevEndMs = 0L⏎        for (i in 0 until words.length()) {⏎            val w = words.getJSONObject(i)⏎            val type = w.getString("type")⏎            if (type == SPACING) continue⏎            val speaker = speakerOf(w.optString("speaker_id"))` → `val result = mutableListOf<Utterance>()⏎        val order = mutableMapOf<String, Int>()⏎        var prevEndMs = 0L⏎        for (i in 0 until words.length()) {⏎            val w = words.getJSONObject(i)⏎            val type = w.getString("type")⏎            if (type == SPACING) continue⏎            val speaker = order.getOrPut(w.optString("speaker_id")) { order.size }` | *TranscriptMapperTest.номер спикера — цифра из speaker_id, а не порядок появления | убита | com.roflochinsky.noteapp.pipeline.TranscriptMapperTest::номер спикера — цифра из speaker_id, а не порядок появления |
| M5-без-проверки-transcript-md | расшифрованная заметка не перезапрашивается — бюджет «ровно 1 запрос на запись» | `File(dir, NotesStore.TRANSCRIPT_MD).exists() -> WorkResult.success()` → `false -> WorkResult.success()` | *TranscribeWorkerTest.готовый transcript md — второго запроса нет | убита | com.roflochinsky.noteapp.pipeline.TranscribeWorkerTest::готовый transcript md — второго запроса нет |
| control-таймкод | формат тайм-кода [мм:сс] держится тестом — если нет, сломан харнесс, а не код | `return "[%02d:%02d]".format(min, sec)` → `return "[%d:%d]".format(min, sec)` | *TranscriptMapperTest.тайм-код форматируется мм-сс с ведущими нулями | убита | com.roflochinsky.noteapp.pipeline.TranscriptMapperTest::тайм-код форматируется мм-сс с ведущими нулями |

Каждая мутация сужена до одного теста; ни один прогон не пришёл `FROM-CACHE` и не был
`UP-TO-DATE` (`mutate.py` гонит `--no-build-cache cleanTestDebugUnitTest` и отказывается судить по
кэшу). Управляющая мутация убита — харнесс исправен.

## 5. Фикстура: доказательство, что личного текста не осталось

`app/src/test/resources/elevenlabs-sample-response.json` собран скриптом (одноразовый, в
скратчпаде, в репо не коммитится): `nikitatrubaev-u4g.1-build-fixture.py`; проверка —
`nikitatrubaev-u4g.1-check-fixture.py`. Окно `[0; 56,2 с]` боевого `~/stt-bench-out/09-elevenlabs-bez.json`.

```
слов окна боевого ответа: 131 (110 уникальных)
из них найдено в фикстуре целым словом: 0 []
элементов в фикстуре: 264 (боевое окно: 264)
start/end/type/speaker_id/logprob совпадают с боевыми: True
audio_event сохранён как есть: ['[смеется]']
transcription_id: 'fixture-u4g1-postanovka' (заглушка), audio_duration_secs: 56.21
верхний text == склейка элементов: True
```

Поиск пословный — `(?<!\w)слово(?!\w)`, без учёта регистра, пунктуация снята — по **всему тексту
файла** фикстуры против всех 110 уникальных слов скопированного окна: ноль совпадений. Скрипт
сборки, кроме того, отказывается писать фикстуру, если постановочное слово совпало с боевым, и
проверяет, что запись каждого числа (`start`, `end`, `logprob`) дословно встречается в боевом
ответе — то есть числа перенесены байт в байт, а не пересчитаны.

**Оговорка, которую называю сам.** Постановочный монолог пересекается со **всем** 66-минутным
боевым ответом (18 525 элементов, 2448 уникальных слов) 43 служебными словами — `без`, `но`, `до`,
`для`, `при`, `потом`, `чтобы` и подобными. Это словарь русского языка, а не содержание разговора;
со словами скопированного окна пересечение ровно нулевое. Считать это утечкой личного текста
нельзя, но умолчать о факте было бы нечестно.

Проверено также, что окно держит каждый обещанный случай (замер повторён мной, а не принят на
слово): `audio_event` на 9,81 внутри речи `speaker_0`; пауза 1320 мс (`end` 7,00 → `start` 8,32)
того же спикера; пауза 5020 мс (47,08 → 52,10) того же спикера; шесть смен спикера на 12,18;
22,78; 31,42; 46,38; 55,04; 56,12. Список записан в KDoc `TranscriptMapperTest` вместе с
требованием перегнать ВЕСЬ набор мутаций при правке фикстуры.

Ожидания критерия 2 взяты **прогоном правила `bin/stt-compare-html`** (`GAP = 1.5`) по этой самой
фикстуре, а не пересчётом кода маппера. Единственное расхождение с эталоном — круглые скобки
вокруг события звука (`bin/stt-compare-html:65` даёт `([смеется])`) — разрешено в пользу спеки
решением Р7 и записано в KDoc теста.

## 6. Попытки гейта

- **попытка 1** — `TranscriptMapperTest > номер спикера — цифра из speaker_id` красный. Причина в
  тесте, не в коде: третий элемент литерала стоял на 1,0 с, пауза от предыдущего 500 мс, спикер
  после правила «без цифры → 0» тот же — элементы законно склеились в одну реплику. Литерал
  отнесён на 2,5 с, причина записана в KDoc теста.
- **попытка 2** — `DetailScreenTest > старая заметка от Deepgram` красный:
  `assertExists` на «Спикер 1» нашёл 4 узла вместо одного (в старой заметке четыре реплики одного
  говорящего). Заменено на `onAllNodesWithText(...).assertCountEquals(md.lines().size)` — заодно
  проверяет, что экран рисует все реплики, а не первую.
- **попытка 3** — `bin/gate --full` красный на `ktfmtCheckMain`: три новых файла не по формату
  (`TranscribeWorker.kt`, `TranscriptMapper.kt`, `ElevenLabsClient.kt`). Лечится `bin/gate ktfmtFormat`.
- **попытка 4** — `bin/gate --full` красный на `detekt`: `ReturnCount` — 4 возврата в
  `transcribeNote` при лимите 2. Послабление `excludeGuardClauses` не сработало: detekt считает
  стражами только цепочку `if (…) return …` **в самом начале** тела, а первой строкой стоял
  `val audio = File(...)`. Переписано одним `when` — один возврат, `@Suppress` не понадобился.
- **попытка 5** — тот же `ReturnCount`: правка не применилась (мой скрипт правки упал на
  собственной проверке и файл не переписал), гейт честно показал то же красное. Правка применена,
  гейт зелёный.

## 7. Риски и «на будущее»

**Нерешённые риски.**

1. **Живого запроса к ElevenLabs не было ни одного.** Тело multipart собрано по формату стенда
   `bin/stt-bench` (`multipart()`, строки 77–93) и проверено офлайн; реальный ответ вендора именно
   на этот запрос не видел никто. Настоящая проверка — прокликка владельца (решение Р8: аудио и
   ключи агенту брать неоткуда). Критерий 1 в части «доехало до репо заметок» тестом не держится и
   держаться не может — так и записано в плане.
2. **Скорость маппера на боевом объёме не мерена.** Фикстура — окно 56 с (264 элемента), боевой
   ответ — 18 525 элементов. Разбор линейный и без аллокаций на элемент сверх строки, но цифры
   нет: **не домерено** намеренно, замер потребовал бы боевого файла в прогоне гейта.
3. **Ключ Deepgram больше негде ввести с экрана.** Значение в `SharedPreferences` не стёрто, как
   велит спека, и `Settings.deepgramKey` читается кодом отката, но строка настроек теперь одна и
   она про ElevenLabs. Откат на Deepgram — это откат кода, а не переключение в приложении; если
   владелец захочет вернуться, ключ придётся либо сеять через `run-as`, либо откатывать срез.

**На будущее (найдено попутно, в срез не входит).**

- `DeepgramClient.kt` остался без единого потребителя в приложении: он путь отката ADR и живёт до
  закрытия эпика v3. Судьбу решает владелец, не агент.
- `transcript.json` и `transcript.md` пишутся простым `writeText`, без `.tmp` + `renameTo`. Убитый
  посреди записи процесс оставит обрезанный `transcript.md`, а он навсегда выключает
  перераспознавание и молча уезжает в GitHub. Это уже записано в LLD среза `u4g.2` — там и место.
- `bin/stt-compare-html:65` оборачивает событие звука в круглые скобки, спека требует как пришло.
  Стенд в этом срезе не правил (Р7); побайтовое совпадение со страницей сверки требует правки той
  строки — вопрос владельцу.
- `docs/specs/2026-08-24-note-format.md` (строки 24, 52–53, 120) описывает маппинг из ответа
  Deepgram и после этого среза стал неправдой. Спека — авторство владельца, агент её не трогает
  (пункт 4 «вынесено владельцу» в плане).
- `TranscribeWorkerTest` поднимает Robolectric только из-за `android.util.Log` в пробах: сама
  проверяемая функция Android не требует. Если проб станет больше, дешевле завести шов логирования,
  чем платить Робиком за каждый тест воркера. Сейчас второго потребителя нет — устройство не заводил.

## 8. Путь отката

- Ветка: `nikitatrubaev-u4g.1`, базовый коммит `f8173f0f7c56e88fd8b21e97e447338c368b007d` (локальный `main`).
- Срез — один коммит `05ea1eb5ca63862fe261bc23d4bc59500cf8ed2c`. Откат до слияния — просто не
  сливать ветку; после слияния — `git revert 05ea1eb` (продуктовый откат к Deepgram описан в ADR:
  `DeepgramClient` и `fromDeepgramJson` в дереве нетронуты, ключ Deepgram в настройках не стёрт).

## Круг фикса

Второй проход по тому же срезу: код среза одобрен обеими осями (`APPROVE`, класса `a` нет),
работа — закрыть названные дыры в стражах. Задание: `nikitatrubaev-u4g.1-fix-prompt.md`.

### 1. HEAD

- `HEAD` до круга: `05ea1eb5ca63862fe261bc23d4bc59500cf8ed2c`.
- **`HEAD` после круга: `e43d0c618fcf9bf3d1846b5d00018c3ff635c355`** (один коммит, та же ветка
  `nikitatrubaev-u4g.1`, `git status --porcelain` пуст).
- Тесты: было **310** в 39 классах → стало **313** в 39 классах (+3), падений 0, пропущено 7.
  Счёт по XML-отчётам отдельного чистого прогона на том же HEAD (Gradle имён и чисел не печатает);
  прогон дописан в конец файла вывода гейта.

### 2. Гейты

```
гейты: e43d0c618fcf9bf3d1846b5d00018c3ff635c355 · bin/gate --full rc=0 32 с
```

Полный вывод: `<скратчпад>/nikitatrubaev-u4g.1-gates-fix.txt` — **sha напечатан первой строкой
файла** (находка Н6 оси СПЕКА: результат гейта существует только вместе с хешем).

В файле три прогона подряд, все rc=0, и про каждый сказано, зачем он там:

1. `bin/gate --full` — 32 с, `BUILD SUCCESSFUL`, но `:app:testDebugUnitTest` пришёл `UP-TO-DATE`
   (тот же прогон уже был на этом содержимом до коммита). Это не прогон, и я его так и подписал.
2. `bin/gate --no-build-cache ktfmtCheck detekt lint cleanTestDebugUnitTest testDebugUnitTest
   assembleDebug` — 27 с, `BUILD SUCCESSFUL`, `> Task :app:testDebugUnitTest` **без пометки**:
   тесты исполнены по-настоящему, покрытие шире `--full`. Это и есть зелёный сданного HEAD.
3. `bin/gate --no-build-cache cleanTestDebugUnitTest testDebugUnitTest` после мутаций — только
   ради счёта тестов: каталог `app/build/test-results` после мутационного прогона хранит
   мутированный результат, судить по нему о срезе нельзя.

### 3. Что закрыто, чем и какая мутация теперь умирает

**Ф1 — шов `doWork` (чей ключ и какой вендор).**
Тест: `TranscribeWorkerTest::ключ берётся из настройки ElevenLabs, а не из настройки прежнего
вендора`. Владелец выставил только `Settings.setElevenLabsKey`, ключ прежнего вендора пуст; запись
уходит в распознавание, и тест сверяет **тот самый ключ**, что пришёл в шов (`listOf("xi-test-kluch")`).
Мутация `X1-воркер-берет-ключ-deepgram` теперь **убита**: под ней ключ пуст → `retry`, ноль вызовов.

**Правка реализации — была, объясняю.** Закрыть дыру одним тестом не вышло: собрать
`CoroutineWorker` в юните нечем — `androidx.work:work-testing` в зависимостях нет, а
`app/build.gradle.kts` этому срезу трогать запрещено; конструировать `WorkerParameters` руками — это
`@RestrictTo`-конструктор с версионно-зависимой сигнатурой, то есть устройство ради теста. Поэтому
три строки, которые `doWork` решал сам (каталог, ключ, вендор), вынесены в
`TranscribeWorker.transcribeById(context, noteId, stt = ElevenLabsClient()::transcribe)` — тем же
швом и тем же словарём, что уже есть у соседнего `transcribeNote`. Поведение не изменилось:
`doWork` после этого достаёт `noteId` из `Data` и зовёт `transcribeById`. Строка, которую мутирует
X1, осталась ровно одна и теперь исполняется тестом.

**Ф2 — фатальные коды 400/401/403.**
Тест `отказ по ключу — failure` переписан в цикл по трём кодам и назван
`отказ по ключу или запросу — failure, запись не перезапрашивается`; каждому коду свой каталог,
сообщение `assert`-а несёт код. Мутация `X2-фатальные-коды-только-401` теперь **убита** (под ней
400 и 403 уходят в `retry`).

**Ф3 — «тело стримится, а не собирается в память».**
Подсказку ревьюера проверил — она верна. Поток `sent` в `ElevenLabsClientTest` заменён на
`Recorder : ByteArrayOutputStream`, который запоминает **размер самой крупной одной записи**
(перекрыт `write(b, off, len)` — через него идут и `write(ByteArray)`, и `copyTo`). Тест
`аудио уходит в поток кусками, а не файлом целиком` шлёт файл 20 000 Б и требует двух вещей: тело
длиннее записи (файл ушёл целиком, не обрезан) и самая крупная запись **меньше** записи. Мерило
общее, а не про размер буфера `copyTo`: любая потоковая отправка проходит, чтение файла целиком —
нет. Мутация `X3-тело-собирается-в-память` теперь **убита** (`readBytes` даёт одну запись в 20 000 Б).

**Ф4 — счётчик запросов.**
Инкремент убран из значения по умолчанию `stt` и остался только в обёртке, через которую `run`
зовёт `transcribeNote`, — теперь один вызов считается один раз. Добавлена положительная сверка
бюджета: `удачная расшифровка — ровно один запрос` (`assertEquals(1, calls)`). До правки этот тест
видел бы 2 и был бы зелёным только при сломанном счётчике. Отдельной мутации задание не требовало;
мерило нулевых сверок при этом не изменилось — `M5-без-проверки-transcript-md` осталась убитой.

**Ф5 — текст.**
- `ElevenLabsClient.FIELDS`, KDoc: «ровно шесть полей» больше не приписано ADR. Новый текст
  перечисляет пять полей Решения 2 **и `keyterms` наравне с ними**, а «шесть вместе с `file`»
  прямо назван границей *этого среза*, а не запретом ADR на седьмое поле, со ссылкой на часть 2
  (`nikitatrubaev-7cy.2`).
- `DetailScreenTest`, KDoc теста критерия 4 переписан по разделам «что тест **видит**» и «чего
  **не видит**»: `transcript.json` лежит рядом только ради достоверности каталога, экран его не
  читает, и «старый JSON разбирается как прежде» держат тесты `fromDeepgramJson`, а не этот тест.
- Там же мерило сделано честным: вместо `onAllNodesWithText("Спикер 1").assertCountEquals(md.lines().size)`
  тест проверяет **текст каждой реплики** на экране (это и есть «экран нарисовал все реплики»), а
  счёт подписей «Спикер 1» сверяется с числом строк, которые этого спикера называют. Второй
  говорящий в фикстуре теперь ничего не покрасит.

### 4. Мутации

Спека: `<скратчпад>/nikitatrubaev-u4g.1-mutations-fix.toml` (управляющая из первого круга —
хирургическая `FILENAME` `audio.m4a` → `audio.bin` — плюс три новые из вердикта оси СПЕКА).
Прогон: `scripts/mutate.py run --id nikitatrubaev-u4g.1 --spec <файл> --scope full` → **rc=0**,
вывод — `<скратчпад>/nikitatrubaev-u4g.1-mutations-fix.txt`.
**Все восемь мутаций и управляющая убиты, выживших нет; полный прогон под управляющей — убита.**
`tests` каждой мутации сужен до одного теста. Прогонов быстрее секунды и `FROM-CACHE` в журнале
нет: `scripts/mutate.py` сам отвергает такой прогон кодом 2.

HEAD e43d0c618fcf · 2026-09-07 20:59 · управляющая: убита
полный прогон: убита (com.roflochinsky.noteapp.pipeline.ElevenLabsClientTest::запрос — шесть полей multipart, ключ заголовком, файл частью file)

| id | claim | find → replace | фильтр | результат | упавший тест |
|---|---|---|---|---|---|
| M1-порог-нестрогий | порог паузы строгий: ровно 1,5 с уже рвёт реплику | `startMs - prevEndMs < GAP_MS` → `startMs - prevEndMs <= GAP_MS` | *TranscriptMapperTest.пауза ровно 1500 мс рвёт реплику | убита | com.roflochinsky.noteapp.pipeline.TranscriptMapperTest::пауза ровно 1500 мс рвёт реплику |
| M2-порог-1300 | порог именно 1,5 с: пауза 1320 мс реплику не рвёт | `private const val GAP_MS = 1500` → `private const val GAP_MS = 1300` | *TranscriptMapperTest.пауза ниже порога не рвёт реплику | убита | com.roflochinsky.noteapp.pipeline.TranscriptMapperTest::пауза ниже порога не рвёт реплику |
| M3-событие-в-скобках | audio_event идёт в текст как пришёл, а не оборачивается ещё раз (как bin/stt-compare-html:65) | `val text = w.getString("text")` → `val text = if (type == "audio_event") "(" + w.getString("text") + ")" else w.getString("text")` | *TranscriptMapperTest.событие звука попадает в текст как пришло | убита | com.roflochinsky.noteapp.pipeline.TranscriptMapperTest::событие звука попадает в текст как пришло |
| M4-спикер-по-порядку | номер спикера — цифра из speaker_id, а не порядок появления | `val result = mutableListOf<Utterance>()⏎        var prevEndMs = 0L⏎        for (i in 0 until words.length()) {⏎            val w = words.getJSONObject(i)⏎            val type = w.getString("type")⏎            if (type == SPACING) continue⏎            val speaker = speakerOf(w.optString("speaker_id"))` → `val result = mutableListOf<Utterance>()⏎        val order = mutableMapOf<String, Int>()⏎        var prevEndMs = 0L⏎        for (i in 0 until words.length()) {⏎            val w = words.getJSONObject(i)⏎            val type = w.getString("type")⏎            if (type == SPACING) continue⏎            val speaker = order.getOrPut(w.optString("speaker_id")) { order.size }` | *TranscriptMapperTest.номер спикера — цифра из speaker_id, а не порядок появления | убита | com.roflochinsky.noteapp.pipeline.TranscriptMapperTest::номер спикера — цифра из speaker_id, а не порядок появления |
| M5-без-проверки-transcript-md | расшифрованная заметка не перезапрашивается — бюджет «ровно 1 запрос на запись» | `File(dir, NotesStore.TRANSCRIPT_MD).exists() -> WorkResult.success()` → `false -> WorkResult.success()` | *TranscribeWorkerTest.готовый transcript md — второго запроса нет | убита | com.roflochinsky.noteapp.pipeline.TranscribeWorkerTest::готовый transcript md — второго запроса нет |
| X1-воркер-берет-ключ-deepgram | воркер читает ключ ElevenLabs, а не ключ прежнего вендора | `key = Settings.elevenLabsKey(context),` → `key = Settings.deepgramKey(context),` | *TranscribeWorkerTest.ключ берётся из настройки ElevenLabs, а не из настройки прежнего вендора | убита | com.roflochinsky.noteapp.pipeline.TranscribeWorkerTest::ключ берётся из настройки ElevenLabs, а не из настройки прежнего вендора |
| X2-фатальные-коды-только-401 | 400 и 403 тоже кончают попытки, а не уходят в вечный retry по 48 МБ | `private val FATAL = setOf(400, 401, 403)` → `private val FATAL = setOf(401)` | *TranscribeWorkerTest.отказ по ключу или запросу — failure, запись не перезапрашивается | убита | com.roflochinsky.noteapp.pipeline.TranscribeWorkerTest::отказ по ключу или запросу — failure, запись не перезапрашивается |
| X3-тело-собирается-в-память | тело multipart стримится из файла, а не собирается в ByteArray (48 МБ на часовой записи) | `audio.inputStream().use { it.copyTo(out) }` → `out.write(audio.readBytes())` | *ElevenLabsClientTest.аудио уходит в поток кусками, а не файлом целиком | убита | com.roflochinsky.noteapp.pipeline.ElevenLabsClientTest::аудио уходит в поток кусками, а не файлом целиком |
| control-имя-файла | имя части file в multipart держится ровно одним тестом — узкий радиус для полного прогона | `const val FILENAME = "audio.m4a"` → `const val FILENAME = "audio.bin"` | *ElevenLabsClientTest.запрос — шесть полей multipart, ключ заголовком, файл частью file | убита | com.roflochinsky.noteapp.pipeline.ElevenLabsClientTest::запрос — шесть полей multipart, ключ заголовком, файл частью file |

### 5. Изменённые файлы против «Трогает:»

`git diff --name-only 05ea1eb..HEAD` — 5 файлов:

```
app/src/main/kotlin/com/roflochinsky/noteapp/pipeline/ElevenLabsClient.kt
app/src/main/kotlin/com/roflochinsky/noteapp/pipeline/TranscribeWorker.kt
app/src/test/kotlin/com/roflochinsky/noteapp/pipeline/ElevenLabsClientTest.kt
app/src/test/kotlin/com/roflochinsky/noteapp/pipeline/TranscribeWorkerTest.kt
app/src/test/kotlin/com/roflochinsky/noteapp/ui/DetailScreenTest.kt
```

Все пять входят в строку «Трогает:» задачи `bd nikitatrubaev-u4g.1`; `git diff --name-only
main...HEAD` по-прежнему 12 файлов, ни одного нового. Запреты круга соблюдены: `app/build.gradle.kts`,
`OnboardingScreen.kt`, `DeepgramClient.kt` и тело `fromDeepgramJson` не тронуты.

### 6. Попытки гейта

**Красных попыток не было — и это стоит объяснить, а не выдать за доблесть.** Круг фикса пишет
стражи над кодом, который уже верен и одобрен: «красное» здесь по построению даёт не гейт, а
мутация. Роль красной попытки сыграл мутационный прогон — каждая из трёх новых мутаций валит ровно
свой новый тест (раздел 4), то есть каждый тест был проверен на способность падать. Прогонов:
`bin/gate ktfmtFormat` (форматирование), `bin/gate` (быстрый, rc=0, 31 с), `bin/gate --full`
(rc=0, 32 с) и два перепрогона без кэша — все зелёные с первого раза.

### 7. Что осталось долгом

- **Онбординг и `setupComplete()` (`MainActivity.kt:489,515`) — долг, перенесён в `u4g.2`**
  решением ведущего: тестов `MainActivity` в репо нет вовсе, первый в этом срезе — не по образцу
  соседей. До тех пор ловится первой прокликкой владельца.
- **Какой именно клиент зовётся по умолчанию, тестом по-прежнему не держится.** `transcribeById`
  исполняется тестом, но значение по умолчанию `ElevenLabsClient()::transcribe` тест подменяет
  фейком — иначе это живой запрос к вендору. Подмена вендора на `DeepgramClient` в этой строке
  мутацией не ловится, и компилятор здесь НЕ страж: у `DeepgramClient::transcribe` ровно тот же
  тип `(File, String) -> String`, подмена компилируется молча (поправлено ведущим по второму
  ревью — обе оси поймали это утверждение как ложное). Дешёвого честного
  способа закрыть это офлайн я не нашёл; называю прямо, потому что вторая половина находки Н1 оси
  СТАНДАРТОВ — именно про вендора, а не только про ключ.
- Боевой транспорт `ElevenLabsClient.httpPost` не покрыт — цена шва, объявлена в первом круге.
- Пустой ответ вендора (`words` пуст → пустой `transcript.md`) — не трогал, решается в `u4g.2`
  вместе с записью через `.tmp` (находка Н8 оси СПЕКА, так и предписано заданием).
- `docs/SETUP.md` про право «Speech to Text» (находка Н5 оси СТАНДАРТОВ) — заданием круга не
  назван, `docs/SETUP.md` не трогал. **На будущее:** источник у требования всё-таки есть — ADR,
  раздел «Контекст», последняя строка про смоук: «Ключ владельца требует права `speech_to_text`».
  Ревьюер этой строки не нашёл; находка снимается чтением ADR, правки не нужно.

### 8. Путь отката круга

Круг — один коммит `e43d0c618fcf9bf3d1846b5d00018c3ff635c355` поверх `05ea1eb`. Откат круга —
`git revert e43d0c6` (вернёт `doWork` к прежнему виду и снимет новые стражи), откат всего среза —
как в разделе 8 выше.

---

Ветка не слита, `bd` не закрыт — это делает ведущий. Сабагентов не запускал.
