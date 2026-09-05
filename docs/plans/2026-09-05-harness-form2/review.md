# Ревью переноса харнеса формы 2 — коммиты 9c167f3 (ei5) и 128e383 (cc9)

Ревьюер: na-reviewer-роль в канале Agent, модель fable, усилие medium, дерево — главное
`/home/nikitatrubaev/code/noteapp` (только чтение). Диапазон `6579a5b..128e383`, 36 файлов.
Оси Spec и Standards пройдены инлайном одним агентом.

## 1. ГЕЙТЫ

HEAD `128e383`, рабочая копия чистая (в дереве только игнорируемый `.mutations/` живого прогона).
Перегнал сам, блок «Скрипты харнеса» и «Хуки» из CLAUDE.md:

```
~/.local/bin/ruff check scripts            → All checks passed!            rc=0
~/.local/bin/ruff format --check scripts   → 11 files already formatted    rc=0
~/.local/bin/pytest scripts/tests -q       → 91 passed in 7.54s            rc=0
python3 .claude/hooks/tests/test_destructive_fs_guard.py → OK: 84/84 (block=53 allow=29 override=2)
python3 .claude/hooks/tests/test_orca_card_sync.py       → ok
python3 .claude/hooks/tests/test_reminders.py            → ok
```

`bin/gate` и Gradle не запускались по условию задания (параллельно идёт живой прогон мутаций);
Kotlin-код в диапазоне не менялся, так что блок «Приложение» не применим.

Прогоны скриптов на существующих файлах (ожидался разумный отказ, не крэш):

```
scripts/adr-check.py docs/adr/2026-08-26-tasks-as-files.md   rc=1
  ОТКАЗ: в шапке нет строки `Status:`
  ОТКАЗ: Date не в форме ISO ГГГГ-ММ-ДД: «2026-08-26 · Статус: принято · Решения утверждены…»
  ОТКАЗ: в шапке нет непустой строки `Owners:` / `Спека:`; нет `### Решение`; нет минуса в
  «Последствиях»; нет «Чем подтверждается» / «НЕ меняет» / «Откат»
  (то же для двух других старых ADR — rc=1, без трейсбека)
scripts/spec-check.py docs/specs/2026-08-26-tasks-v2.md        rc=1
  ОТКАЗ: части спеки не разделены чертой `---`   (три старые спеки — rc=1, без крэша)
scripts/ready-slices.py nikitatrubaev-0rk -n 3                 rc=0
  МОЖНО ПУСТИТЬ СЕЙЧАС (2 из 3): .37 (13 файлов), .38 (13 файлов); ЖДУТ (1): .40 — зависимость.
  .39 (status=blocked) в выводе отсутствует вовсе — см. находку e-3.
```

## 2. СПЕКА (описание задач ei5 и cc9 как спека)

| Пункт задачи | Где | Статус |
|---|---|---|
| ei5: скиллы epic/epic-orca/discuss/adr/review-intent/to-spec/to-tickets | `.claude/skills/*/SKILL.md` | есть, все 7, на русском, frontmatter name/description; шаги с «Готово:» в 5 из 7 (epic и epic-orca режим 2 — как в источнике) |
| ei5: агенты na-executor/na-reviewer/na-researcher | `.claude/agents/na-*.md` | есть, `isolation: worktree`, модели по спеке |
| ei5: mutate.py под Gradle и XML | `scripts/mutate.py` | есть; проверено чтением против `bin/gate` и образца вывода — см. п. 3 и находки t-1, t-2, e-1 |
| ei5: ready-slices, spec-check, discuss-check, adr-check, epic-metrics + тесты | `scripts/` | есть; 91 pytest; замки и PREFIX_HINTS соответствуют дереву (пути `app/src/main/kotlin/com/roflochinsky/noteapp/{pipeline,ui,assist}`, `app/src/test/resources/`, `docs/examples/process-notes.yml`, `config/detekt/` — все существуют) |
| ei5: хук orca-card-sync, settings.json глубина 1 | `.claude/hooks/orca-card-sync.py`, `.claude/settings.json` | есть, тест есть |
| ei5: docs/harness/epic.md, docs/agents/issue-tracker.md, CLAUDE.md, orca.yaml | — | есть; CLAUDE.md 141 строка ≤ 250 |
| ei5: workwatch-специфика не тащится | — | SQL/stage/e2e/Linear/frontend не перенесены; остатки текста — находки b-5, b-6 |
| cc9: 13 хвостов → крупные срезы, «Трогает:», без общих файлов | bd .37–.40, план «Нарезка 2» | 13 тикетов закрыты с причиной на существующий срез или раздел плана — проверено все 13; содержание .24→.37, .17→.38, .32→.40, .14→«Долг вне срезов», .36 (обе находки), .34, .6, .15, .13, .31, .29, .33, .35 — не потеряно. **Но «срезы не делят файлы» — неверно по коду, см. a-1, a-2** |

Ссылки из новых файлов на файлы/скиллы/агентов noteapp: проверены `test -e` — `PRODUCT.md`,
`CONTEXT.md`, `DESIGN.md`, `docs/examples/process-notes.yml`, `app/src/test/resources`,
`app/build.gradle.kts`, `AndroidManifest.xml`, компы v2/v3 в `docs/design/`, `bin/gate`,
скиллы `wait-what`, `update-docs`, `impeccable:impeccable`, `mattpocock-skills:*`, агенты
`na-*`, `reference/craft-floor.md` в плагине impeccable, `outputs.cacheIf`/`upToDateWhen` в
`app/build.gradle.kts:77-78`, эпик `nikitatrubaev-4wi`, символы `ConflictRule`, `Frontmatter`,
`Edit`, `NoteFile`, `TaskFile`, `PipelineQueue`, `WriteQueue`, `BatchPlan`, `GithubClient`,
`TranscriptMapper` — все существуют. Несуществующих ссылок не нашёл (кроме каталога журналов
`docs/plans/2026-08-26-tasks-v2/`, который создаётся первым срезом — это нормально).

Потери из прежнего CLAUDE.md (`git show 6579a5b:CLAUDE.md`): `~/go/bin/actionlint` — сохранён
(строка 16); `java` не в PATH и JDK в `~/.local/java/jdk17` — сохранено (23); `local.properties`
не в git — сохранено (23–24), но текст ошибки «SDK location not found» и команда
`cp ~/code/noteapp/local.properties .` исчезли (b-7). Версия actionlint v1.7.12 и ссылка на
bd nikitatrubaev-rvw опущены — без замены, мелочь.

## 3. МУТАЦИЯ

Не применимо в форме `scripts/mutate.py`: он гоняет Gradle-тесты приложения, а в диапазоне
Kotlin не менялся; параллельный живой прогон запрещал трогать Gradle. Стражи самих скриптов —
pytest (91 зелёных), в том числе на случаи, которые просили проверить: `test_a_cached_task_is_an_invalid_run`
(FROM-CACHE → код 2), `test_a_filter_that_selects_nothing_is_an_invalid_run` (пустой `--tests` →
код 2), `test_two_failures_is_a_survivor`, `test_surviving_control_exits_two`. Своих мутаций на
Python я не ставил — это честная дыра ревью, не утверждение «стражи держат».

Разбор `mutate.py` против реального вывода Gradle (`gradle-sample.txt`, XML в
`app/build/test-results/testDebugUnitTest/`, 38 отчётов, имён с `()`/`[` сейчас нет):

- `bin/gate --no-build-cache cleanTestDebugUnitTest testDebugUnitTest --tests X` — доходит до
  gradlew: в `bin/gate:26-35` незнакомый `--*` роняет цикл `while` и весь остаток `$@` уходит в
  `tasks`, gradlew получает `--offline --max-workers=4 --no-build-cache cleanTestDebugUnitTest
  testDebugUnitTest --tests X`; `--tests` стоит сразу после задачи — валидно для Gradle.
- Ошибка компиляции под мутацией: `> Task :app:testDebugUnitTest` не печатается → `task is None`,
  отчёты сняты `rmtree` → `cases` пуст → `invalid` → код 2, не «выжила» (`mutate.py:261-272`). ✓
- `--tests` без совпадений: Gradle пишет «No tests found for given includes» → `INVALID_MARKS`
  → код 2. ✓  FROM-CACHE/UP-TO-DATE → код 2 ✓ (`--no-build-cache` + clean делают это
  недостижимым, отказ остаётся страховкой).
- Имя с `()` срезается (`mutate.py:212`), параметры `[…]` срезаются с обеих сторон (`241-249`).
- Ложные вердикты, которые скрипт НЕ ловит — находки t-1, t-2, e-1 ниже.

## 4. ФОРМАТ/ПРИВАТНОСТЬ

Срез формата заметки, аудио и ключей не касается: Kotlin, `docs/examples/process-notes.yml`,
`docs/adr/` не менялись. Приватность харнеса: `.gitignore` получил `.beads` (каталог никогда не
был в git — `git ls-tree 6579a5b -- .beads` пуст, потерь нет), `.mutations/`, черновики планов.
Скиллы и CLAUDE.md сохраняют правила «телефон не стенд», «ключи по явному указанию», тестовый
репо `Roflochinsky/voice-notes-test` назван явно. Хук `orca-card-sync` шлёт наружу только id и
заголовок задачи и только при `ORCA_WORKTREE_ID`.

## 5. СКОУП-КРИП

- ei5 «Трогает: .claude/, scripts/, docs/harness/, docs/agents/, CLAUDE.md, .gitignore,
  orca.yaml, .worktreeinclude» — коммит 9c167f3 укладывается целиком (34 файла). ✓
- cc9 «Трогает: docs/plans/2026-08-26-tasks-v2.md» — коммит 128e383 добавляет ещё
  `docs/research/v3-owner-checklist.md` (74 строки, чеклист по карте v3, bd nikitatrubaev-7cy).
  Вне строки обеих задач — обязательная находка e-4. По содержанию файл безвреден.

## 6. ГРАНИЦЫ

Что ревью ВИДИТ: текст всех 36 файлов диффа против источника в workwatch; исполнение ruff,
pytest, трёх тестов хуков, ready-slices на живом bd, adr-check и spec-check на старых файлах;
код noteapp по строкам «Трогает:» четырёх срезов (MainActivity, DetailScreen, RepoStore,
WriteQueue, Edit, BatchPlan, NotesStore, TaskDetailScreen, TasksScreen).
Объявленный долг: Gradle и `bin/gate` не гонялись; `mutate.py run` вживую не запускался;
Orca-команды из `epic-orca` не проверялись (в скилле честно стоит «не домерено»);
`bd swarm validate` / `bd merge-slot` не вызывались (issue-tracker.md сам говорит «не домерено»);
мутации на Python-скрипты не ставились.

## 7. НАХОДКИ

Классы предложены, утверждает ведущий. Порядок — по классу, не по важности внутри класса.

### Класс a — нарезка cc9 обещает «не делят файлы», код говорит иначе

- **a-1. Срез .37 не сделать, не тронув `MainActivity.kt`, который числится за .38.**
  `DetailScreen` (`app/src/main/kotlin/com/roflochinsky/noteapp/ui/DetailScreen.kt:84-95`) не
  имеет колбэка удаления — параметры `onEdit/onOpen/onTask/onRetry/onBack`. Диалог «три
  галочки → один коммит + аудио с телефона» требует нового `onDelete`, а его точка монтирования
  — `MainActivity.kt:194-209`, где и решается, что позвать (батч в `RepoStore`, удаление
  каталога `NotesStore.noteDir`, `back()`). Описание .37 пишет «MainActivity не трогать — точка
  монтирования диалога DetailScreen» — это противоречие: точка монтирования и есть MainActivity.
  Обход «звать RepoStore прямо из ui/» нарушает LLD-24 («экраны не знают про GitHub»),
  которое .38 сам же требует соблюдать. Пример: два исполнителя стартуют параллельно по
  `ready-slices` → .37 правит `MainActivity.kt` → слияние второго даёт конфликт или .37
  останавливается по правилу «работа вышла за границы среза» на первом же дне.
- **a-2. Срез .38 не сделать, не тронув `RepoStore.kt`, который числится за .37.** Требование
  .38: «MainActivity.store() не должен отправлять то, что ещё в окне отмены». `store()`
  (`MainActivity.kt:411`) зовёт `RepoStore.pendingPaths()` и `RepoWriteWorker.schedule`, а
  воркер зовёт `RepoStore.push()` (`RepoStore.kt:339-347`), который берёт
  `queue.pending().firstOrNull()` без разбора. Спрятать операцию «в окне отмены» в самом
  `WriteQueue.pending()` нельзя — тот же список строит оверлей `view()` (`RepoStore.kt:107-118`),
  и задача перестала бы показываться сделанной. Значит нужен второй метод очереди и правка
  `push()`/`deliver()` — то есть `RepoStore.kt` (или `RepoWriteWorker.kt`, тоже .37).
- **a-3. Срез .37 почти наверняка тронет `WriteQueue.kt` (.38) или `Edit.kt` (.40).** «Заметка +
  N задач + правка источника = один коммит»: `WriteQueue.Op` — одна правка на один путь
  (`WriteQueue.kt:25`), `Edit` умеет только `DeleteFile` целиком (`Edit.kt:39`), «вырезать
  строку-ссылку» из заметки-источника (LLD-18) типа правки нет. Батч потребует либо метки группы
  в `Op` (WriteQueue.kt — параллельный .38), либо нового типа `Edit` (Edit.kt — .40, он хотя бы
  последовательно зависит от .37). `ready-slices.py` этого не видит: он сверяет заявленные
  строки, а не код — набор «.37 ∥ .38» честен только относительно заявленного.

  Как править (предложение, решает ведущий): либо `bd dep add nikitatrubaev-0rk.38
  nikitatrubaev-0rk.37` и переписать «Трогает:» обоих честно; либо вынести из .38 хвост .17
  («Отменить» переживает поворот — единственный, кому нужны WriteQueue/RepoStore/MainActivity.store)
  в .37 или в отдельный третий срез после .37, тогда .38 остаётся чисто экранным и параллельным.

### Класс t — стражи `mutate.py`, которые не ловят

- **t-1. Ложная «убита» на тесте, красном и без мутации.** `verdict()` считает убийством «ровно
  один упавший с нужным именем» (`mutate.py:272`), базового прогона без мутации нет, управляющая
  мутация это не выявляет (она тоже даст 1 failed). Флаки или уже красный `EditTest.x` на HEAD
  + мутация с `expect_fail = EditTest::x` → «убита». Унаследовано от workwatch, но в Gradle
  цена выше: узкий фильтр `*EditTest` гоняет один класс, красный сосед виден только там.
  Лечение — один прогон без подмены до цикла (`0 failed`, иначе код 2).
- **t-2. `_strip_params` режет по первой `[`** (`mutate.py:241-242`). Kotlin-имена в обратных
  кавычках допускают `[`: «список `[a, b]` сортируется» и «список `[c]` пуст» после среза
  совпадут, и падение второго зачтётся за первое. Сейчас таких имён в 38 отчётах нет —
  теоретическая дыра, ставить ограничитель «`[` только в конце имени» дёшево.

### Класс b — текст, ссылки, потерянные правила

- **b-1. `.claude/skills/epic/SKILL.md:157` (режим 3, шаг 3):** «класс `a` — в ближайший срез,
  задач из находок нет» — в режиме 3 открытых детей уже нет, ближайшего среза не существует.
  Нужно сказать, куда идёт `a` при закрытии (новый срез эпика / вопрос владельцу).
- **b-2. Потеряно правило источника без замены:** workwatch `epic` режим 1 шаг 1 — «сжать
  интервью до одного батча развилок — тоже пропуск» (решение владельца 2026-08-21). В noteapp
  `epic:27-31` и `discuss:30-33` остаётся только «раундами до пустого фронтира» — слабее.
- **b-3. Потеряно правило источника без замены:** «наборы мутаций: узкий внутри цикла, полный
  один раз под управляющей и всегда после слияния» (workwatch epic шаг 5, G6). В noteapp
  `--scope full` в `mutate.py` есть, но ни `epic:112-118`, ни `docs/harness/epic.md:143-159` не
  говорят, когда его гонять; шаг 7 после слияния зовёт только `bin/gate`.
- **b-4. Несогласованный потолок:** `epic:60` — `ready-slices.py <эпик> -n 3`, `epic:66` —
  «Потолок пишущих — два», `epic-orca:28` — `-n 2`, `ready-slices.py` default 3,
  `docs/harness/epic.md:103` — `-n 3`. Одно число нужно везде.
- **b-5. `scripts/ruff.toml:2-3`:** комментарий про `backend/pyproject.toml` и «AGENTS.md → блок
  бэкенда» — в noteapp нет ни того, ни другого. Остаток workwatch.
- **b-6. `scripts/spec-check.py:2`:** «R1 §7.2, спека C2» — ссылки на документы workwatch без
  пометки «того репо» (в других скриптах пометка есть).
- **b-7. `CLAUDE.md:22-24`:** из старой версии исчезли текст ошибки «SDK location not found» и
  команда `cp ~/code/noteapp/local.properties .` — по тексту ошибки её теперь не найти grep'ом;
  копирование делают `bin/gate:37` и `orca.yaml`, но тот, кто зовёт gradlew руками вопреки
  правилу, подсказки не получит. Одной строки хватит.
- **b-8. bd .38 «Трогает:»** не содержит нового файла компонента чипов (в .29 предложен
  `ui/Chip.kt`); исполнитель либо создаст файл вне списка (обязательная находка скоуп-крипа),
  либо втиснет компонент в один из трёх существующих. Решить в описании.
- **b-9. bd .37 «Трогает:»** не содержит `pipeline/NotesStore.kt`, а «локальное аудио удалено с
  телефона» требует функции удаления каталога записи — в `NotesStore` её нет (только `root`,
  `noteDir`, `list`). Конфликта с другими срезами не даёт, но список неполный.

### Класс e — прочее

- **e-1. Ложная «выжила» при двух убитых тестах в узком фильтре** — по построению
  (`test_two_failures_is_a_survivor`): фильтр по умолчанию выводится из класса (`*EditTest`,
  `mutate.py:119-126`), и мутация, честно пойманная двумя тестами класса, читается «выжила
  (2 failed)» → `CHANGES REQUESTED` без обсуждения по `docs/harness/epic.md:45`. Это осознанная
  строгость источника, но в правилах человеку (`epic.md:143-159`) об этом ни слова; стоит
  записать: «`tests` сужать до одного теста (`*Класс.имя`), иначе 2 failed = выжила».
- **e-2. `.claude/hooks/skill-router.py:49`:** правило `воркер` → `epic-orca`. В noteapp
  «воркер» — это `RepoWriteWorker`, `PushWorker`, `TranscribeWorker`; фраза «воркер записи
  падает на 304» получит подсказку про пилот Orca. Хук не блокирует, но подсказка ложная.
  Сужать до `воркер\w* orca|orca`.
- **e-3. `scripts/ready-slices.py:127`:** срез со статусом `blocked` (.39) не попадает ни в
  «можно», ни в «ждут» — исчезает молча, хотя докстринг обещает «про каждый отвергнутый
  говорит, чем он занят». Печатать его в «ЖДУТ» с причиной «blocked» — одна строка.
- **e-4. Скоуп-крип коммита 128e383:** `docs/research/v3-owner-checklist.md` вне «Трогает:»
  задач cc9 и ei5 (см. п. 5). Файл сам полезен и безвреден; надо либо дописать в «Трогает:»
  cc9, либо отнести к карте 7cy отдельным коммитом.
- **e-5. `.claude/agents/na-reviewer.md`:** линза над-инженерии перенесена текстом, скилл
  `ponytail:ponytail-review` из `ww-reviewer` снят, хотя плагин ponytail в этом окружении
  установлен. Если снят намеренно — строку «почему» в агент; если нет — вернуть `skills:`.

Что проверено и находкой НЕ стало: adr-check принимает шапку списком и таблицей, русские
статусы, имя по дате, отказывает на старой форме без крэша (8 проверок как заявлено, индекс
опционален); discuss-check словарь `CONTEXT.md`; spec-check идентичен источнику; epic-metrics
`GATE_RX` и `--project-glob` переведены под noteapp; `orca.yaml` setup копирует
`local.properties` и сверяет базу с локальным `main`; `.worktreeinclude` — `local.properties`;
все 13 закрытых тикетов ссылаются на существующие .37–.40 или раздел плана; язык — русский;
CLAUDE.md 141 ≤ 250 строк.

## Итог

**CHANGES REQUESTED** — по коммиту 128e383 (нарезка cc9): ключевое обещание «срезы .37 и .38 не
делят файлы» опровергается кодом в трёх местах (a-1, a-2, a-3) с конкретным сценарием
конфликта при параллельном пуске. Перенос харнеса 9c167f3 (ei5) сам по себе — APPROVE с
находками классов b/e и двумя t по `mutate.py`, которые слияние не блокируют.
