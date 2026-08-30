# GitHub REST API для двустороннего синка приложения с репо (v2)

**Дата исследования:** 2026-08-25. Источники — официальная документация `docs.github.com` (ссылка стоит у каждого утверждения). Часть фактов дополнительно **проверена живыми запросами** к `api.github.com` — такие места помечены словом «проверено» и показывают реальный вывод.

**Задача:** телефон (Kotlin, без git-клона на устройстве) должен листать заметки и задачи из приватного репо, читать их пачками, править frontmatter и текст, удалять и перемещать файлы, и дёшево узнавать «что изменилось с прошлого раза». Токен — PAT. Git — единственный источник правды.

---

## 1. Вывод: как строить синк

Коротко: **читать через Git-данные (`trees`/`blobs`), писать через Git-данные, а `contents` API оставить только для одиночных мелких правок.** Поллинг делать условными запросами — они бесплатны.

| Операция синка | Чем делать | Сколько HTTP-запросов |
|---|---|---|
| Полная карта репо (все пути + blob-SHA + размеры) | `GET /repos/{o}/{r}/git/trees/{branch}?recursive=1` | **1** |
| Холодный старт: забрать все тексты сразу | `GET /repos/{o}/{r}/zipball/{ref}` (302 → архив) | **1** (+ распаковка) |
| Дочитать конкретный файл | `GET /git/blobs/{sha}` или `GET /contents/{path}` с `Accept: …raw` | 1 на файл |
| «Что изменилось?» (частый поллинг) | `GET /git/ref/heads/main` с `If-None-Match` | **1, и она бесплатна** пока 304 |
| Разбор изменений после сдвига ветки | `GET /compare/{lastSha}...{newSha}` | 1 (даёт файлы, статусы, свежие blob-SHA) |
| Одна правка одного файла | `PUT /contents/{path}` c `sha` | 1 |
| Удалить файл | `DELETE /contents/{path}` c `sha` | 1 |
| **Переместить / переименовать** | только Git-данные: tree → commit → ref | **4–5** |
| **Пачка правок одним коммитом** (сколько угодно файлов) | те же 4–5 запросов | **4–5**, не зависит от числа файлов |

**Три правила, которые из этого следуют.**

1. **Один коммит на один жест пользователя.** `contents` API физически не умеет больше одного файла за коммит, а каждый коммит — это ещё и запуск Action. Как только правка задевает два файла (перенос заметки, «закрыть задачу и переложить в архив»), идти в Git-данные.
2. **Поллинг — только условный.** Ответ `304 Not Modified` при корректной авторизации **не тратит лимит вообще** — проверено, счётчик не двигается. Поллинг раз в 5 минут круглые сутки стоит ≈0 от квоты 5000/час.
3. **Устаревший SHA — это норма, а не сбой.** В репо заметок пишет ещё и Action (саммари), поэтому SHA в кэше телефона протухают сами собой. Ответ `409` надо обрабатывать как штатную ветку, а не как ошибку сети (правило — §7).

Полная схема синка — в §8.

---

## 2. Листинг и массовое чтение: `contents` против `git/trees`

### 2.1 `contents` API — по одному файлу

`GET /repos/{owner}/{repo}/contents/{path}` на папке отдаёт «массив объектов, по объекту на каждый элемент папки», на файле — один объект с содержимым в base64. ([repos/contents](https://docs.github.com/en/rest/repos/contents))

Границы по размеру, дословно из доков:

- **≤ 1 МБ** — «All features of this endpoint are supported»;
- **1–100 МБ** — «Only the raw or object custom media types are supported… the content field will be an empty string and the encoding field will be `none`»;
- **> 100 МБ** — «This endpoint is not supported».

Два ограничения, которые решают выбор:

- **«This API has an upper limit of 1,000 files for a directory. If you need to retrieve more files, use the Git Trees API.»**
- **Нерекурсивно:** «To get a repository's contents recursively, you can recursively get the tree.»

**Проверено** — листинг папки даёт метаданные, но **не содержимое**:

```
GET /repos/octocat/Spoon-Knife/contents/
[ { "name": "README.md", "path": "README.md",
    "sha": "f4790267d0d362a90d6799759ece092616c40779",
    "size": 780, "type": "file", … } ]
```

То есть N заметок = 1 запрос на листинг + N запросов на тексты. Для сотни заметок это сотня запросов на каждый холодный старт — дорого и медленно на мобильной сети.

**Проверено** — сырой текст без base64 достаётся заголовком `Accept`:

```
GET /repos/octocat/Hello-World/contents/README
Accept: application/vnd.github.raw+json
→ 200, content-type: application/vnd.github.raw+json
Hello World!
```

### 2.2 `git/trees?recursive=1` — весь репо одним запросом

`GET /repos/{owner}/{repo}/git/trees/{tree_sha}?recursive=1` возвращает дерево целиком, включая подпапки. Лимит документирован дословно: **«The limit for the tree array is 100,000 entries with a maximum size of 7 MB when using the recursive parameter»**. Если упёрлись — в ответе стоит `truncated: true` («the number of items in the tree array exceeded our maximum limit»), и доки велят: «use the non-recursive method of fetching trees, and fetch one sub-tree at a time». ([git/trees](https://docs.github.com/en/rest/git/trees))

**Проверено** — на каждый файл приходит ровно то, что нужно синку: `path`, `mode`, `type`, `sha`, `size`:

```
GET /repos/octocat/Hello-World/git/trees/master?recursive=1
{ "tree": [ { "path": "README", "mode": "100644", "type": "blob",
              "sha": "980a0d5f19a64b4b30a87d4206aade58726b60e3", … } ] }
```

**Почему это выгоднее `contents`:** один запрос вместо «листинг каждой папки», нет потолка в 1000 файлов на папку, и главное — сразу приходят **blob-SHA всех файлов**. Эти же SHA нужны для записи и для перемещений, то есть карта репо и «ключи для правок» достаются одним вызовом. Для нашего репо (тексты по несколько килобайт) 7 МБ ответа — это десятки тысяч заметок, до потолка далеко.

### 2.3 `zipball` — все тексты одним запросом

`GET /repos/{owner}/{repo}/zipball/{ref}` отдаёт **302** на временную ссылку с архивом; «For private repositories, these links are temporary and expire after five minutes». Если ref не указан — берётся ветка по умолчанию. ([repos/contents](https://docs.github.com/en/rest/repos/contents)) Нужно, чтобы HTTP-клиент шёл по редиректу или читал `Location` вручную.

**Когда это лучший вариант:** первая установка приложения и возврат после долгого офлайна — вместо сотен запросов за blob'ами один архив. Минус — отдаёт весь репо целиком, без выбора, и это не инкремент.

### Итог раздела

- **карта репо** — `git/trees?recursive=1` (1 запрос, все SHA);
- **холодный старт** — `zipball` (1 запрос, все тексты);
- **точечное чтение** — `git/blobs/{sha}` (base64, «supports blobs up to 100 megabytes in size», [git/blobs](https://docs.github.com/en/rest/git/blobs)) или `contents` с `raw`;
- **`contents` на папке** — не использовать вовсе: дороже, с потолком 1000 и без рекурсии.

---

## 3. Условные запросы и лимиты: сколько это реально стоит

### 3.1 Основной лимит

Дословно: **«You can use a personal access token to make API requests… All of these requests count towards your personal rate limit of 5,000 requests per hour.»** Неавторизованные — 60/час. ([rate-limits](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api))

Важный нюанс: лимит **общий на пользователя**, а не на токен. Токен телефона делит 5000/час с `gh` на ноутбуке и с любыми другими токенами владельца. Для наших объёмов это не проблема, но «свой отдельный токен = свой отдельный лимит» — неверное ожидание.

Заголовки в каждом ответе: `x-ratelimit-limit`, `x-ratelimit-remaining`, `x-ratelimit-used`, `x-ratelimit-reset` (UTC epoch seconds), `x-ratelimit-resource`. Приложению стоит их читать и показывать в отладочном экране.

`GET /rate_limit` — «Calling this endpoint does not count against your primary rate limit, but it can count against your secondary rate limit».

### 3.2 ETag / `If-None-Match`: 304 бесплатны

Дословно из best practices: **«If the data has not changed, you will receive a `304 Not Modified` response, which does not count against your primary rate limit»** и **«Making a conditional request does not count against your primary rate limit if a `304` response is returned and the request was made while correctly authorized»**. ([best-practices](https://docs.github.com/en/rest/using-the-rest-api/best-practices-for-using-the-rest-api))

**Проверено, и это ключевой факт для поллинга.** С токеном:

```
GET …/git/trees/master?recursive=1              → 200, x-ratelimit-used: 9
… с If-None-Match: "a9f835de…"                  → 304, x-ratelimit-used: 9
… ещё раз                                        → 304, x-ratelimit-used: 9
… ещё раз                                        → 304, x-ratelimit-used: 9
x-ratelimit-limit: 5000
```

Счётчик **не сдвинулся ни на единицу** за три условных запроса.

**Оговорка, которую тоже проверили:** без авторизации 304 **тратит** лимит (счётчик рос: 2 → 3 → 4). Формулировка доков «while correctly authorized» — не украшение. Наш случай авторизованный, значит всё в порядке.

**Вторая оговорка:** ETag зависит от контекста запроса — на одном и том же URL без токена пришёл слабый `W/"e80410…"`, с токеном — сильный `"a9f835…"`. Значит ETag надо кэшировать вместе с токеном/заголовками и сбрасывать при их смене, иначе поллинг молча перестанет отдавать 304.

Ещё из доков: «Poll only as often as you need to, on a fixed schedule. If a response includes an `x-poll-interval` header, wait at least that many seconds before you poll the same endpoint again.»

### 3.3 Вторичные лимиты — они бьют по записи, не по чтению

Дословный список ([rate-limits](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api)):

- «No more than 100 concurrent requests are allowed.»
- «No more than 900 points per minute are allowed for REST API endpoints» — при этом GET/HEAD/OPTIONS = 1 точка, а **POST/PATCH/PUT/DELETE = 5 точек**.
- «No more than 90 seconds of CPU time per 60 seconds of real time is allowed.»
- **«No more than 80 content-generating requests per minute and no more than 500 content-generating requests per hour are allowed.»**

Плюс из best practices: «you should make requests serially instead of concurrently» и **«If you are making a large number of `POST`, `PATCH`, `PUT`, or `DELETE` requests, wait at least one second between each request.»** При превышении приходит `retry-after`, и «Continuing requests while rate limited can result in the banning of your integration».

**Что это значит для noteapp.** Потолок — **500 коммитов в час**, и это ещё один довод против «каждая правка = отдельный `PUT /contents`». Массовая операция вроде «пометить 40 задач сделанными» через contents API — это 40 коммитов, 40 запусков Action и секунда паузы между каждым; через Git-данные — **один** коммит и 5 запросов. Разница не в скорости, а в том, что второй вариант вообще не приближается к вторичным лимитам.

### 3.4 Прикидка бюджета

| Сценарий | Запросов в час | Доля от 5000 |
|---|---|---|
| Поллинг ref раз в 5 минут (почти всегда 304) | 12, из них ~0 платных | ~0 % |
| Поллинг + 5 реальных изменений (compare + дочитать по 3 файла) | ≈ 12 + 5×4 = 32 | < 1 % |
| Активная правка: 30 жестов пользователя батчами | ≈ 30 × 5 = 150 | 3 % |

Основного лимита хватает с огромным запасом. Реальное ограничение — вторичное (500 коммитов/час), и его снимает батчинг.

---

## 4. Запись: создать, обновить, удалить

`PUT /repos/{owner}/{repo}/contents/{path}` — параметры `message`, `content` («The new file content, using Base64 encoding»), `sha` («Required if you are updating a file. The blob SHA of the file being replaced»), плюс `branch`, `committer`, `author`. Коды: **201 Created** для нового файла, **200 OK** для обновления, **404**, **409 Conflict**, **422 Validation failed**. ([repos/contents](https://docs.github.com/en/rest/repos/contents))

`DELETE /repos/{owner}/{repo}/contents/{path}` — `sha` **обязателен** («The blob SHA of the file being deleted»), плюс `message`. Коды: 200, 404, 409, 422, 503.

Три вещи из доков, которые надо заложить в код:

1. **Прямое предупреждение о параллелизме:** «If you use this endpoint and the 'Delete a file' endpoint in parallel, the concurrent requests will conflict and you must use these endpoints serially instead.» То есть очередь записи в приложении должна быть **однопоточной**.
2. **`author`/`committer` — либо полностью, либо никак:** если передаёте, нужны и имя, и почта, «Otherwise, you'll receive a 422 status code».
3. **Один файл — один коммит.** Ни PUT, ни DELETE не принимают несколько путей. Никакого «атомарно поменять два файла» здесь нет.

### Что именно приходит при устаревшем SHA

В доках перечислен только код — **409 Conflict**. Дословной формулировки тела ответа документация не даёт. По разборам в GitHub Community и трекерах клиентских библиотек тело содержит сообщение вида `<path> does not match <sha>` / «is at X but expected Y», и тот же 409 возвращается, если репозиторий пуст или временно недоступен ([community discussion #62198](https://github.com/orgs/community/discussions/62198), [PyGithub #1787](https://github.com/PyGithub/PyGithub/issues/1787)). Это **вторичный источник** — на текст сообщения полагаться нельзя, различать надо по коду; точную формулировку стоит один раз снять смоук-тестом на тестовом репо (см. §9).

Отдельно: `sha`, не переданный при обновлении существующего файла, даёт **422**, а не 409 — то есть «забыл SHA» и «SHA протух» это разные ветки обработки.

**Ограничение по размеру** для записи в доках `contents` не указано (указаны только пороги 1 МБ / 100 МБ для чтения). Для заметок в килобайты вопрос не стоит; крупные вложения, если когда-нибудь появятся, — через `git/blobs`.

---

## 5. Перемещение и переименование: только Git-данные

**`contents` API этого не умеет** — эндпойнта «move/rename» в документации нет вообще, есть только создать/обновить/удалить один файл.

Наивный обход «PUT в новое место + DELETE из старого» плох по трём причинам сразу: это **два коммита** (два запуска Action и промежуточное состояние, где заметка существует дважды), доки **прямо запрещают** делать PUT и DELETE параллельно, а если второй запрос упадёт — репо остаётся в разъехавшемся состоянии, откатывать которое некому.

**Правильный путь — Git data API, один коммит.** Документированная последовательность ([using-the-rest-api-to-interact-with-your-git-database](https://docs.github.com/en/rest/guides/using-the-rest-api-to-interact-with-your-git-database)): получить текущий коммит → его дерево → создать blob'ы → создать дерево → создать коммит → сдвинуть ветку.

Для перемещения заметки `notes/2026-08-25-idea.md` → `archive/2026-08-25-idea.md`:

```
1) GET  /repos/{o}/{r}/git/ref/heads/main
        → object.sha = HEAD-коммит                                   (1 запрос)

2) GET  /repos/{o}/{r}/git/commits/{headSha}
        → tree.sha = базовое дерево                                  (1 запрос)

3) POST /repos/{o}/{r}/git/trees
   { "base_tree": "<treeSha>",
     "tree": [
       { "path": "archive/2026-08-25-idea.md", "mode": "100644",
         "type": "blob", "sha": "<тот же blob sha>" },
       { "path": "notes/2026-08-25-idea.md",   "mode": "100644",
         "type": "blob", "sha": null }
     ] }                                                             (1 запрос)

4) POST /repos/{o}/{r}/git/commits
   { "message": "…", "tree": "<newTreeSha>", "parents": ["<headSha>"] }  (1 запрос)

5) PATCH /repos/{o}/{r}/git/refs/heads/main
   { "sha": "<newCommitSha>", "force": false }                       (1 запрос)
```

Почему это работает и почему дёшево:

- **`base_tree`:** «a new Git tree object will be created from entries in the Git tree object pointed to by base_tree and entries defined in the tree parameter»; записи из `tree` «will overwrite items from base_tree with the same path». То есть перечислять весь репо не нужно — только изменённые пути. ([git/trees](https://docs.github.com/en/rest/git/trees))
- **`sha: null` удаляет:** «If the value is null then the file will be deleted».
- **Blob для перемещения создавать не нужно** — содержимое не изменилось, берём готовый blob-SHA из `git/trees?recursive=1`. Перемещение стоит те же 5 запросов независимо от размера файла.
- **Если содержимое тоже меняется** — в записи дерева вместо `sha` можно передать `content` напрямую; отдельный `POST /git/blobs` тогда не нужен («Use either tree.sha or content to specify the contents of the entry. Using both… will return an error»). Для нашего случая (правка frontmatter) это экономит по запросу на файл.

### `force: false` — это защита от гонки, а не формальность

`PATCH /repos/{o}/{r}/git/refs/{ref}` принимает `sha` и `force` (по умолчанию `false`): **«Leaving this out or setting it to false will make sure you're not overwriting work.»** Коды — 200, 409, **422 Validation failed**. ([git/refs](https://docs.github.com/en/rest/git/refs))

Практический смысл: если между шагом 1 и шагом 5 Action успел записать саммари, ветка сдвинулась, наше обновление перестало быть fast-forward — и GitHub его **отклонит**. Мы переиграем весь батч на новом HEAD. Это даёт синку то, чего `contents` API дать не может: **вся пачка правок применяется целиком или не применяется вовсе**. `force: true` в noteapp не использовать никогда — это перезапись чужой работы в репо, который сам себе источник правды.

### Тот же механизм = батч

Пачка из 20 правок — это те же 5 запросов: 20 записей в массиве `tree` одного `POST /git/trees`. Ни `contents` API, ни вторичный лимит «500 content-generating в час» тут не мешают.

---

## 6. Как дёшево узнать «что изменилось с прошлого раза»

Вебхуки телефону недоступны (некуда доставлять), значит поллинг. Три кандидата.

### A. ETag-поллинг одного ref — рекомендуется как «сторож»

`GET /repos/{o}/{r}/git/ref/heads/main` возвращает крошечный объект: `ref`, `object.sha`, `type`. ([git/refs](https://docs.github.com/en/rest/git/refs))

**Проверено** — ETag на этом эндпойнте есть:

```
GET /repos/octocat/Spoon-Knife/git/ref/heads/main
etag: "8b3e591776d335a4ce58afd294328ad1164274eb75decbecebb2228c12108cf1"
{ "ref": "refs/heads/main", "object": { "sha": "d0dd1f61b33d64e29d8bc1372a94ef6a2fee76a9", … } }
```

Пока никто ничего не коммитил — `304`, ноль потраченного лимита и ~100 байт трафика. Как только SHA сдвинулся — переходим к шагу C.

### B. `GET /commits?since=` — не подходит как основной механизм

Параметры: `since` — «Only show results that were last updated after the given time. This is a timestamp in ISO 8601 format: YYYY-MM-DDTHH:MM:SSZ», `until`, `sha` (ветка), `path` («Only commits containing this file path will be returned»), `per_page` (max 100). ([commits](https://docs.github.com/en/rest/commits/commits))

Минусы для нас: **опора на часы** (время коммита против часов телефона — гарантированный источник пропущенных или задвоенных изменений), и отдаёт **коммиты, а не файлы** — чтобы узнать затронутые пути, нужен ещё запрос на каждый коммит. Годится как аварийный запасной путь, не как основной.

### C. `GET /compare/{base}...{head}` — разбор изменений одним запросом

Возвращает `status` (diverged / ahead / behind / identical), `ahead_by`, `behind_by`, `total_commits`, массив `commits` и массив `files` с полями `filename`, `status`, `previous_filename`, `sha`. ([commits — compare](https://docs.github.com/en/rest/commits/commits#compare-two-commits))

Лимиты, дословно: «When calling this endpoint without any paging parameter (per_page or page), the returned list is limited to 250 commits» и **«The list of changed files is only shown on the first page of results, and it includes up to 300 changed files for the entire comparison»**.

**Проверено, два важных факта.**

1. **`compare` принимает голые SHA**, хотя доки говорят про branch names:

```
GET /repos/octocat/Spoon-Knife/compare/bb4cc8d3…...d0dd1f61… → 200
status=ahead ahead_by=1 total_commits=1
files: { filename: README.md, status: modified,
         sha: f4790267d0d362a90d6799759ece092616c40779, previous_filename: None }
```

2. **`files[].sha` — это blob-SHA файла**, и он совпал с `sha` из листинга `contents` того же файла (`f4790267…`). Значит один `compare` отдаёт сразу и «что изменилось», и **свежие ключи для последующей записи** — отдельно перечитывать SHA изменённых файлов не нужно.

Плюс `status: renamed` вместе с `previous_filename` — перемещения приходят распознанными, приложению не надо угадывать «удалили тут и создали там».

Оговорка: раз доки обещают только имена веток, на приём SHA закладываться как на контракт нельзя — держать запасной путь (§8, шаг 5).

### Рекомендация: A → C

Сторож на ref (бесплатно), разбор через compare (1 запрос), полный `trees?recursive=1` — только как аварийный откат.

---

## 7. Правило разруливания устаревшего SHA

1. **Записывать только со свежим SHA.** Источник SHA — последний `trees`/`compare`/успешный ответ на запись. SHA из ответа `PUT` (`content.sha` нового блоба) кладём в кэш сразу — это экономит перечитывание.
2. **409 — не ретраить вслепую.** Повтор с тем же SHA даст тот же 409 бесконечно.
3. **Разделять 409 и 422:** 422 — «мы забыли `sha`» или «кривые author/committer», это наш баг; 409 — «файл в git изменился», это штатная ветка.
4. **На 409:** перечитать файл (`GET /contents/{path}` — даёт и свежий `sha`, и содержимое одним запросом) и сравнить с базой, на которой пользователь правил.
   - Изменённое пользователем поле в git **не менялось** → переиграть запись с новым SHA автоматически, молча.
   - Менялось **то же самое** поле → **git выигрывает**, пользователю показать расхождение. Автоматически перезатирать нельзя: с той стороны, скорее всего, Action с саммари.
5. **Для батчей роль «конфликта» играет `PATCH ref` с `force: false`:** отказ = HEAD сдвинулся → перечитать HEAD, пересобрать дерево, повторить. Проверять надо один раз в конце, а не по файлу.
6. **Очередь записи однопоточная**, между мутациями ≥ 1 секунда — прямое требование доков (§3.3, §4).
7. **Ожидать чужие коммиты по умолчанию.** Action пишет саммари в те же файлы — «SHA протух» здесь не редкое исключение, а обычный ход событий.

---

## 8. Схема синка целиком

**Состояние на телефоне:** `lastCommitSha` (HEAD, на котором мы синхронизированы), `etagRef`, карта `путь → blobSha` и локальный кэш текстов.

```
Холодный старт:
  GET /zipball/main            → все тексты        (1 запрос)
  GET /git/trees/main?recursive=1 → карта путь→sha  (1 запрос)
  GET /git/ref/heads/main      → lastCommitSha + ETag

Поллинг (раз в N минут, при открытии приложения, после своей записи):
  GET /git/ref/heads/main  +  If-None-Match: etagRef
    304 → ничего не изменилось, лимит не потрачен → выход
    200 → newSha ≠ lastCommitSha →
      GET /compare/{lastCommitSha}...{newSha}
        для files[]: added/modified → дочитать GET /git/blobs/{files[].sha}
                     removed        → убрать из кэша
                     renamed        → переложить (previous_filename → filename)
        обновить карту sha из files[].sha
        lastCommitSha = newSha
      если files усечены (>300), status=diverged или compare не отработал →
        откат: GET /git/trees/{newSha}?recursive=1 и пересборка карты

Запись (одна очередь, ≥1 сек между вызовами):
  один файл, без переноса → PUT /contents/{path} { message, content(b64), sha }
                            201/200 → взять новый sha из ответа
                            409 → правило §7
  перенос / 2+ файлов     → ref → commit → trees(base_tree) → commits → PATCH ref (force:false)
                            422 на PATCH → HEAD сдвинулся → пересобрать и повторить
  после любой записи      → сразу опросить ref (наш же коммит подвинул ветку)
```

**Готча про Action.** Коммиты приложения делаются под PAT, значит `push` в репо заметок Action запускает как обычно. Обратное неверно: «When you use the repository's `GITHUB_TOKEN` to perform tasks, events triggered by the `GITHUB_TOKEN` will not create a new workflow run» ([trigger-a-workflow](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/trigger-a-workflow)) — коммит саммари сам себя не перезапустит, петли «правка → Action → правка» на уровне GitHub не будет. Но этот же коммит **сдвинет ветку и протухнет SHA у телефона** — то есть в норме после каждой заметки приложение увидит одно чужое изменение. Это и есть основной сценарий §7, а не редкий угол.

---

## 9. Токен: fine-grained против classic

**Всё, что нужно синку, покрывает одна permission — `Contents`.** Под неё попадают все двенадцать используемых эндпойнтов: get content, create/update file, delete file, get/create tree, get/create blob, create commit, get/update reference, list commits, compare commits — чтение требует read, запись read and write. ([permissions-required-for-fine-grained-personal-access-tokens](https://docs.github.com/en/rest/authentication/permissions-required-for-fine-grained-personal-access-tokens))

Разница между типами токенов ([managing-your-personal-access-tokens](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens)):

| | fine-grained PAT | classic PAT |
|---|---|---|
| Область | «Each token is limited to access resources owned by a single user or organization», «Each token can be further limited to only access specific repositories» | scope `repo` — **все** репозитории владельца |
| Права | «granted specific, fine-grained permissions» — можно выдать только `Contents` | грубые scope'ы |
| Срок | «Infinite lifetimes are allowed but may be blocked by a maximum lifetime policy set by your organization or enterprise owner» | то же |
| Рекомендация GitHub | «GitHub recommends that you use fine-grained personal access tokens instead of personal access tokens (classic) whenever possible» | — |
| Лимит | 5000/час, общий на пользователя | тот же |

**Рекомендация для noteapp: fine-grained PAT, привязанный ровно к репо заметок, с `Contents: Read and write`, и отдельный от токена, который используется в CI.** Обоснование прямое: токен живёт на телефоне, который можно потерять; при утечке fine-grained даёт доступ только к репо заметок, а classic с `repo` — ко всем репозиториям владельца сразу. Отдельный токен ещё и отзывается независимо, не ломая Action.

Два практических следствия:

- **Срок жизни.** Бессрочный токен для личного аккаунта разрешён, но приложение всё равно обязано корректно обрабатывать `401` — иначе после ротации ключа синк начнёт молча падать.
- **Workflow-файлы.** Если приложению когда-нибудь понадобится трогать `.github/workflows/`, одной `Contents` не хватит — нужна отдельная permission `Workflows: write` (у classic — scope `workflow`). Сейчас такой задачи нет, и хорошо: держать её вне прав телефона правильнее.

---

## 10. Что осталось проверить смоук-тестом (на тестовом репо, не на боевом)

1. **Тело ответа 409** при устаревшем SHA — точная формулировка в доках не описана, а обработчик конфликта на неё смотреть не должен. Проверить и зафиксировать, что различаем по коду.
2. ~~**Живёт ли ETag на `git/ref` через сдвиг ветки**~~ — **ОТВЕЧЕНО замерами 2026-08-30** (срез `nikitatrubaev-0rk.7`, три независимых прогона против `voice-notes-test`). `git/ref` отдаёт `cache-control: private, max-age=60` — общего CDN-кэша нет. Выдуманный ETag даёт 200; ETag, снятый ДО чужих коммитов, после сдвига ветки даёт 200 сразу и 200 через 65 с. То есть 304 выдаёт только сервер против текущего состояния, несовпавший валидатор всегда возвращает свежее тело: **протухший ETag не может скрыть новые заметки**.
3. **Приём голых SHA в `compare`** — работает на практике (проверено), но доки обещают только имена веток. Нужен запасной путь на случай изменения поведения.
4. **Реальный размер `trees?recursive=1`** на репо с тысячами заметок — до потолка 7 МБ / 100 000 записей далеко, но точку, где ответ становится дорогим по трафику для мобильной сети, стоит знать.
5. **Гонка «приложение правит заметку, Action в этот момент дописывает саммари»** — проверить, что правило §7 действительно доводит синк до сходимости, а не до цикла повторов.
6. **Поведение fine-grained токена на приватном репо** с одной только `Contents: Read and write` — что все шесть шагов Git-данных проходят без доп. прав.
