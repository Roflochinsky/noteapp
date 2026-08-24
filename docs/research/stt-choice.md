# Выбор облачного STT для noteapp (v1)

**Дата исследования:** 2026-08-24. Все данные взяты из официальной документации и прайс-страниц самих вендоров (+ репозиторий whisperX). Ссылка стоит у каждого утверждения.

## 0. Что требуется

1. Русская речь с вкраплениями английских заимствований.
2. Диаризация (метки спикеров) в том же вызове, что и транскрипция.
3. Бесплатный тариф или стартовые кредиты, которых хватит на 5–10 записей в день по 1–30 мин — «личное использование», желательно на много месяцев.
4. Вызов напрямую из Android по обычному HTTPS (multipart или бинарное тело), один API-ключ, без SDK.

**Расчётный объём.** При средней записи ~10 мин и 5–10 записях в день выходит **25–50 часов аудио в месяц**; тяжёлый сценарий (10 записей по 15 мин) — **~75 часов в месяц**. Ниже все «на сколько хватит» считаются по этим числам.

---

## 1. Сводная таблица

| | **Deepgram** | **AssemblyAI** | **ElevenLabs Scribe** | **Speechmatics** | **Gladia** |
|---|---|---|---|---|---|
| **Русский** | `ru` есть у `nova-3` [[1]](https://developers.deepgram.com/docs/models-languages-overview) | только Universal-2; топ-категория точности «High accuracy (≤10 % WER)» [[2]](https://www.assemblyai.com/docs/pre-recorded-audio/supported-languages) | Scribe v2, категория «Excellent (≤5 % WER)» [[3]](https://elevenlabs.io/docs/capabilities/speech-to-text) | `ru` для Enhanced/Standard [[4]](https://docs.speechmatics.com/speech-to-text/languages) | `ru` есть, с автоопределением и code-switching [[5]](https://docs.gladia.io/chapters/speech-to-text-api/pages/languages) |
| **RU+EN в одной записи** | `nova-3` c `language=multi` — code-switching для 10 языков, включая русский и английский [[1]](https://developers.deepgram.com/docs/models-languages-overview) | Universal-2 поддерживает code switching [[6]](https://www.assemblyai.com/docs/getting-started/models) | «Smart language detection», отдельного RU+EN режима в доках нет [[3]](https://elevenlabs.io/docs/capabilities/speech-to-text) | модель Melia 1 — мультиязычная с code-switching, batch-only [[4]](https://docs.speechmatics.com/speech-to-text/languages) | `language_config.code_switching: true` [[7]](https://docs.gladia.io/api-reference/v2/pre-recorded/init) |
| **Диаризация** | `diarize_model=latest`; работает со всеми batch-моделями Nova и на **всех доступных языках** [[8]](https://developers.deepgram.com/docs/diarization) | `speaker_labels: true`; поддерживается на `universal-2` и `universal-3-5-pro` [[9]](https://www.assemblyai.com/docs/pre-recorded-audio/label-speakers) | `diarize`, до 32 спикеров [[3]](https://elevenlabs.io/docs/capabilities/speech-to-text) | `"diarization": "speaker"` [[10]](https://docs.speechmatics.com/speech-to-text/batch/batch-diarization) | `"diarization": true` [[11]](https://docs.gladia.io/chapters/speech-to-text-api/pages/speaker-diarization) |
| **Формат ответа** | `words[].speaker` + `speaker_confidence`; с `utterances=true` — готовые реплики «[Speaker:0] текст» [[8]](https://developers.deepgram.com/docs/diarization) | массив `utterances[]`: `speaker` (A/B/C), `text`, `start`/`end` (мс), вложенный `words[]` [[9]](https://www.assemblyai.com/docs/pre-recorded-audio/label-speakers) | `words[]` с `speaker_id` (`speaker_0`), `start`/`end` в секундах [[3]](https://elevenlabs.io/docs/capabilities/speech-to-text) | у каждого `word`/`punctuation` поле `speaker`: `S1`, `S2`, `UU` [[10]](https://docs.speechmatics.com/speech-to-text/batch/batch-diarization) | `utterances[]` с индексом `speaker` (0,1,…) по порядку появления, `start`/`end` [[11]](https://docs.gladia.io/chapters/speech-to-text-api/pages/speaker-diarization) |
| **Доплата за диаризацию** | **Включена** в pre-recorded (в таблице add-ons — «Included») [[12]](https://deepgram.com/pricing) | **+$0.02/час** [[13]](https://www.assemblyai.com/pricing) | входит в цену Scribe v2 [[14]](https://elevenlabs.io/pricing/api) | в списке платных bolt-ons её нет ⇒ включена [[15]](https://www.speechmatics.com/pricing) | входит в тариф [[16]](https://www.gladia.io/pricing) |
| **Бесплатно на старте** | **$200 кредитов**, «No expiration», **без карты** [[12]](https://deepgram.com/pricing) | **$50 кредитов**, «Credits do not expire», **без карты** [[17]](https://www.assemblyai.com/docs/billing-and-pricing) [[13]](https://www.assemblyai.com/pricing) | Free-план: **~4 ч 30 мин** Scribe v2 в месяц [[14]](https://elevenlabs.io/pricing/api) | **$100 кредитов**, «no card required» [[15]](https://www.speechmatics.com/pricing) | €50 разово, без месячного обнуления, **но** у free-тарифа жёсткий лимит **10 часов/месяц** [[16]](https://www.gladia.io/pricing) [[18]](https://docs.gladia.io/chapters/limits-and-specifications/concurrency) |
| **Цена после кредитов** | nova-3 моно $0.0043/мин (≈$0.26/ч), мульти $0.0052/мин (≈$0.31/ч) [[12]](https://deepgram.com/pricing) | Universal-2 $0.15/ч + $0.02/ч диаризация = **$0.17/ч** [[13]](https://www.assemblyai.com/pricing) [[6]](https://www.assemblyai.com/docs/getting-started/models) | $0.22/ч [[14]](https://elevenlabs.io/pricing/api) | Melia 1 $0.129/ч, Standard $0.24/ч, Enhanced $0.40/ч [[15]](https://www.speechmatics.com/pricing) | $0.61/ч (Starter) [[16]](https://www.gladia.io/pricing) |
| **На сколько хватит бесплатного** | **~640–775 ч** ⇒ **8–30 месяцев** | ~294 ч ⇒ **4–12 месяцев** | ~4.5 ч/мес ⇒ **не хватает** | ~250 ч (Enhanced) … ~775 ч (Melia 1) ⇒ **3–30 месяцев** | потолок 10 ч/мес ⇒ **не хватает** |
| **Лимиты файла** | ≤ 2 ГБ; ошибка `504`, если обработка >10 мин; до 100 параллельных запросов [[19]](https://developers.deepgram.com/docs/pre-recorded-audio) | в доках upload/submit лимиты не указаны [[20]](https://www.assemblyai.com/docs/pre-recorded-audio/api-reference/files/upload) | ≤ 3 ГБ, ≤ 10 часов [[3]](https://elevenlabs.io/docs/capabilities/speech-to-text) | тело POST ≤ 1 ГБ (иначе — URL); результаты живут 7 дней [[21]](https://docs.speechmatics.com/speech-to-text/batch/limits) | ≤ 135 мин, ≤ 1000 МБ [[22]](https://docs.gladia.io/chapters/limits-and-specifications/supported-formats) |
| **Число HTTP-вызовов** | **1** (синхронно) | 3 (upload → submit → poll) | **1** (multipart) | 2–3 (job → poll → transcript) | 3 (upload → init → poll) |
| **Хранение данных** | «Deepgram does not store transcripts» — ответ API единственный шанс забрать текст [[19]](https://developers.deepgram.com/docs/pre-recorded-audio) | по умолчанию: аудио удаляется за 24–48 ч, транскрипты — за 30 дней; часть файлов может идти в обучение, есть self-serve opt-out [[23]](https://www.assemblyai.com/docs/data-retention-and-model-training) | в доках STT срок хранения не оговорён | аудио, транскрипты и конфиг хранятся 7 дней, можно удалить раньше [[21]](https://docs.speechmatics.com/speech-to-text/batch/limits) | **Free-тариф: всё хранится 1 год** (аудио + транскрипты); ZDR только Enterprise [[24]](https://docs.gladia.io/chapters/limits-and-specifications/data-retention) |

---

## 2. Разбор кандидатов

### 2.1 Deepgram — рекомендуется

**Русский.** `ru` явно указан в списке языков модели `nova-3` / `nova-3-general`; кроме того, в `nova-3` есть режим `multi` (code-switching) для набора «English, Spanish, French, German, Hindi, **Russian**, Portuguese, Japanese, Italian, Dutch» — это ровно случай «русская речь с английскими вставками». ([models-languages-overview](https://developers.deepgram.com/docs/models-languages-overview))

**Диаризация.** «Diarization is compatible with all Nova batch models (Nova-1, Nova-2, Nova-3) as well as enhanced and base», и в шапке страницы стоит «All available languages». Включается одним query-параметром `diarize_model=latest` (старый `diarize=true` объявлен deprecated и всегда идёт в v1-диаризатор). ([diarization](https://developers.deepgram.com/docs/diarization))

**Ответ.** В `words[]` приходят `speaker` (int) и `speaker_confidence`. Если добавить `utterances=true`, ответ уже разбит на реплики — из документации:

```
[Speaker:0] Hello, and thank you for calling premier phone service...
[Speaker:1] Not too bad. How are you today?
```

Это почти буквально формат «Спикер 1 / Спикер 2» из глоссария noteapp. ([diarization](https://developers.deepgram.com/docs/diarization))

**Вызов из Android — один POST с бинарным телом**, никаких загрузок и опросов:

```bash
curl --request POST \
  --header 'Authorization: Token YOUR_DEEPGRAM_API_KEY' \
  --header 'Content-Type: audio/wav' \
  --data-binary @youraudio.wav \
  --url 'https://api.deepgram.com/v1/listen?diarize_model=latest&punctuate=true&utterances=true'
```

([diarization](https://developers.deepgram.com/docs/diarization)). Для noteapp к этому добавляются `model=nova-3` и `language=ru` (или `language=multi`).

**Бесплатно.** Прайс-страница: «Pay As You Go — No minimums. **No expiration.** **No credit card required.**», «Free **$200** Credit, then pay-as-you-go». ([pricing](https://deepgram.com/pricing))

**Диаризация в pre-recorded не стоит ничего.** В таблице «Speech-to-Text Add-ons → Pre-Recorded pricing» у строки «Speaker Diarization» стоит **Included** и для Pay As You Go, и для Growth (в streaming — $0.0020/мин). ([pricing](https://deepgram.com/pricing))

**Экономика.** nova-3 monolingual $0.0043/мин = $0.258/час → $200 ≈ **775 часов**. nova-3 multilingual $0.0052/мин = $0.312/час → ≈ **641 час**. При 25 ч/мес это 25–31 месяц, при 50 ч/мес — 13–15 месяцев, при 75 ч/мес — 8.5–10 месяцев. ([pricing](https://deepgram.com/pricing))

**Лимиты.** Файл до 2 ГБ. Важная деталь: «Requests exceeding 10 minutes (Nova/Base/Enhanced)… return a `504: Gateway Timeout`» — это про *время обработки*, не про длину аудио. Сколько именно Nova-3 обрабатывает 30-минутный файл, документация не указывает, так что это **надо проверить смоук-тестом**, а не считать данностью. Есть режим `callback` для асинхронной отдачи. ([pre-recorded-audio](https://developers.deepgram.com/docs/pre-recorded-audio))

**Приватность.** «Deepgram does not store transcripts, so the API response is the only opportunity to retrieve the transcript.» ([pre-recorded-audio](https://developers.deepgram.com/docs/pre-recorded-audio)). Отдельного self-serve тумблера «не обучаться на моих данных» в публичных доках нет — есть общий [Information Security & Privacy Statement](https://developers.deepgram.com/trust-security/information-security-privacy) и [Privacy Policy](https://deepgram.com/privacy) («Any Customer Data that we have access to shall be retained, stored, and deleted according to our agreement with our business customer»).

**Минус:** Deepgram не публикует пословный WER по русскому языку, так что «насколько хорошо» — вопрос эмпирической проверки, а не документации.

### 2.2 AssemblyAI — второе место

**Русский.** Поддерживается **только** Universal-2 (Universal-3.5 Pro покрывает 18 языков, русского среди них нет). Зато в разбивке по точности русский стоит в верхней группе: «High accuracy (≤ 10 % WER): English, Spanish, French, German, Indonesian, Italian, Japanese, Dutch, Polish, Portuguese, **Russian**, Swedish, Turkish, Ukrainian, Catalan». ([supported-languages](https://www.assemblyai.com/docs/pre-recorded-audio/supported-languages))

**Диаризация.** Страница «Speaker Diarization» помечена бейджами моделей `universal-3-5-pro` и `universal-2` — то есть та самая модель, что делает русский, диаризацию поддерживает. Включается `speaker_labels: true`. Ответ — массив `utterances[]`, где каждый элемент = непрерывная реплика одного спикера (`speaker`, `text`, `start`, `end`, `confidence`, `words[]`). Это самый удобный формат для «одна реплика — одна строка в .md». Ограничить число спикеров можно жёстко: `speakers_expected` / `min_speakers_expected` / `max_speakers_expected` (это «hard boundaries», не подсказки). ([label-speakers](https://www.assemblyai.com/docs/pre-recorded-audio/label-speakers))

**Вызов из Android — три шага:**

1. `POST https://api.assemblyai.com/v2/upload`, заголовок `authorization: <KEY>`, тело — сырые байты (`application/octet-stream`) → `{"upload_url": "..."}`. ([files/upload](https://www.assemblyai.com/docs/pre-recorded-audio/api-reference/files/upload))
2. `POST https://api.assemblyai.com/v2/transcript` с JSON `{"audio_url": ..., "language_code": "ru", "speaker_labels": true}`. ([transcripts/submit](https://www.assemblyai.com/docs/pre-recorded-audio/api-reference/transcripts/submit))
3. `GET https://api.assemblyai.com/v2/transcript/{id}` в цикле до `status: completed` (либо webhook). ([transcripts/submit](https://www.assemblyai.com/docs/pre-recorded-audio/api-reference/transcripts/submit))

Есть EU-эндпоинт `api.eu.assemblyai.com`. ([files/upload](https://www.assemblyai.com/docs/pre-recorded-audio/api-reference/files/upload))

**Бесплатно.** «New accounts receive **$50** in free credits… **Credits do not expire**, and any unused credits are retained on your account when you upgrade». ([billing-and-pricing](https://www.assemblyai.com/docs/billing-and-pricing)) FAQ на прайсе: «you can create an account and start transcribing immediately with **no credit card required**. The free tier includes up to 185 hours of pre-recorded transcription…». ([pricing](https://www.assemblyai.com/pricing))

**Экономика.** Universal-2 — $0.15/ч ([models](https://www.assemblyai.com/docs/getting-started/models)), диаризация — +$0.02/ч ([pricing](https://www.assemblyai.com/pricing)). Итого $0.17/ч → $50 ≈ **294 часа**: 12 месяцев при 25 ч/мес, ~6 месяцев при 50 ч/мес, ~4 месяца при 75 ч/мес.

**Данные.** Аудио удаляется за 24–48 часов, транскрипты — за 30 дней (без BAA/TTL). Часть файлов может использоваться для обучения моделей, но есть self-serve отказ (Data Controls: opt-out + настраиваемый TTL). ([data-retention-and-model-training](https://www.assemblyai.com/docs/data-retention-and-model-training), [data-controls](https://www.assemblyai.com/docs/data-controls))

### 2.3 ElevenLabs Scribe v2 — лучшее качество по докам, но бесплатного почти нет

Русский у Scribe v2 отнесён к высшей категории — «Excellent (≤ 5 % WER)». Диаризация до 32 спикеров, `speaker_id` на уровне слов, файлы до 3 ГБ и 10 часов. Один multipart-POST на `https://api.elevenlabs.io/v1/speech-to-text` с заголовком `xi-api-key` и полями `file`, `model_id=scribe_v2`, `language_code`, `diarize`, `num_speakers`, `timestamps_granularity` — интеграционно это самый простой вариант после Deepgram. ([speech-to-text](https://elevenlabs.io/docs/capabilities/speech-to-text), [api-reference](https://elevenlabs.io/docs/api-reference/speech-to-text/convert))

Ломается на требовании №3: в таблице «Speech to Text API → Scribe v2 → Hours included» у колонки **Free / Pay as you go** стоит **4 часа 30 минут**; следующая ступень — Starter $6/мес с 27 часами. ([pricing/api](https://elevenlabs.io/pricing/api)) Нужные 25–75 ч/мес это не закрывает. Как *платный* вариант — $0.22/ч, то есть ~$5.5–16.5 в месяц. ([pricing/api](https://elevenlabs.io/pricing/api))

### 2.4 Speechmatics — тёмная лошадка с самой дешёвой ставкой

$100 кредитов, «no card required». Русский `ru` поддержан для Enhanced и Standard. Диаризация — `"diarization": "speaker"`, метки `S1`/`S2` на каждом слове (и `UU`, когда спикер не определён), есть `speaker_sensitivity`. В списке платных bolt-ons (Translation, Summaries, Chapters, Sentiment, Topics) диаризации нет ⇒ она входит в базовую ставку. Цены batch: **Melia 1 $0.129/ч**, Standard $0.24/ч, Enhanced $0.40/ч. ([pricing](https://www.speechmatics.com/pricing), [languages](https://docs.speechmatics.com/speech-to-text/languages), [batch-diarization](https://docs.speechmatics.com/speech-to-text/batch/batch-diarization))

Melia 1 — мультиязычная модель, которая «transcribes multilingual speech in a single transcript, including speakers who switch language mid-conversation, with no need to select a language», доступна для Batch ([pricing](https://www.speechmatics.com/pricing)); языки она берёт из общего списка, где русский есть ([languages](https://docs.speechmatics.com/speech-to-text/languages)). **Не подтверждено документацией:** работает ли `diarization: "speaker"` вместе с Melia 1 — все примеры в доках диаризации используют `"model": "enhanced"`. Если Melia + диаризация не сочетаются, ставка становится $0.40/ч (Enhanced) и $100 хватает на ~250 часов.

API: `POST https://eu1.asr.api.speechmatics.com/v2/jobs` с `Authorization: Bearer $API_KEY`, multipart (`data_file` + `config`), затем опрос job и забор транскрипта. Регионы EU1 / US1 / AU1. Тело POST ≤ 1 ГБ. Аудио и транскрипты хранятся 7 дней, можно удалить раньше. ([authentication](https://docs.speechmatics.com/get-started/authentication), [regions](https://docs.speechmatics.com/administration/regions), [batch limits](https://docs.speechmatics.com/speech-to-text/batch/limits), [ASR REST API](https://docs.speechmatics.com/api-ref/batch/speechmatics-asr-rest-api))

Минусы для v1: больше всего неизвестных (совместимость Melia + диаризация), доки по «сырому» HTTP слабее — quickstart показывает только SDK.

### 2.5 Gladia — отпадает

Требование №3 не выполняется: у free-тарифа стоит жёсткий **usage limit 10 часов в месяц** (и 3 параллельные транскрипции), независимо от того, сколько евро кредитов на балансе. ([concurrency](https://docs.gladia.io/chapters/limits-and-specifications/concurrency), [pricing](https://www.gladia.io/pricing)) Нужно 25–75 ч/мес.

Второй минус — приватность: «By default, Free accounts retain **all** data types for **1 year**» (аудио, транскрипты, метаданные, логи); zero-data-retention доступен только Enterprise. ([data-retention](https://docs.gladia.io/chapters/limits-and-specifications/data-retention))

Третий — цена после кредитов: $0.61/ч, вдвое-вчетверо дороже остальных. ([pricing](https://www.gladia.io/pricing))

Технически же всё хорошо: русский с автоопределением и code-switching, `diarization: true`, `utterances[]` со `speaker`-индексами, лимиты 135 мин / 1000 МБ, поток upload → `/v2/pre-recorded` → poll. ([languages](https://docs.gladia.io/chapters/speech-to-text-api/pages/languages), [init](https://docs.gladia.io/api-reference/v2/pre-recorded/init), [supported-formats](https://docs.gladia.io/chapters/limits-and-specifications/supported-formats))

---

## 3. Фолбэк: whisperX внутри GitHub Actions

**Что это.** whisperX = Whisper (через faster-whisper) + wav2vec2 forced alignment для пословных таймкодов + pyannote для диаризации. Диаризация включается флагом `--diarize`, число спикеров ограничивается `--min_speakers` / `--max_speakers`. ([whisperX](https://github.com/m-bain/whisperX))

**Гейтинг моделей.** Нужен HuggingFace access token (read) и принятое пользовательское соглашение модели `speaker-diarization-community-1`; токен передаётся флагом `--hf_token`. ([whisperX](https://github.com/m-bain/whisperX)) На странице модели стоит гейт «You need to agree to share your contact information to access this model»; лицензия CC-BY-4.0, доступ обещан бесплатным навсегда. ([pyannote/speaker-diarization-community-1](https://huggingface.co/pyannote/speaker-diarization-community-1)) Для Actions это означает секрет `HF_TOKEN` в репозитории — принципиальных препятствий нет.

**CPU.** whisperX поддерживает CPU: `whisperx path/to/audio.wav --compute_type int8 --device cpu`. Заявленные «70x realtime» относятся к GPU (large-v2, batched inference, <8 ГБ VRAM) — это **не** цифра для CPU-раннера. ([whisperX](https://github.com/m-bain/whisperX))

**Единственный первичный CPU-бенчмарк**, который публикует бэкенд faster-whisper: модель **small**, int8, Intel Core i7-12700K, 8 потоков — **1 мин 42 с** на 13 минут аудио (и 51 с при `batch_size=8`). Для large-v2/large-v3 CPU-цифр в README нет. ([faster-whisper](https://github.com/SYSTRAN/faster-whisper))

**Лимиты GitHub Actions (приватный репозиторий):**

- **2 000 минут в месяц** на плане GitHub Free для приватных репозиториев; счётчик обнуляется в начале каждого месяца. Для публичных репозиториев стандартные раннеры бесплатны. ([Actions billing](https://docs.github.com/en/billing/concepts/product-billing/github-actions))
- Стандартный раннер `ubuntu-latest` в **приватном** репозитории — **2 CPU / 8 ГБ RAM / 14 ГБ диска** (в публичном — 4 CPU / 16 ГБ). ([github-hosted-runners](https://docs.github.com/en/actions/reference/runners/github-hosted-runners))
- Один job — не более **6 часов**; кеш — 10 ГБ на репозиторий. ([Actions limits](https://docs.github.com/en/actions/reference/limits), [Actions billing](https://docs.github.com/en/billing/concepts/product-billing/github-actions))

**Оценка (расчёт, не документация).** Опорная точка — small/int8 даёт ~7.6× реального времени на 8 потоках. Раннер приватного репо даёт 2 ядра (≈в 4 раза меньше), а large-v3 примерно в 6 раз тяжелее small по числу параметров. Отсюда grubbed-оценка для large-v3 на 2-ядерном раннере — **порядка реального времени или медленнее**, плюс сверху выравнивание wav2vec2 и pyannote-диаризация, плюс скачивание ~3 ГБ весов на каждый прогон (если не закешировано). То есть на 25–75 часов аудио в месяц нужны **тысячи** минут раннера при квоте 2 000 — не сходится даже в оптимистичном сценарии, и это ещё до того, как в тех же 2 000 минутах должен уместиться Action, который пишет саммари через Claude.

**Вывод по фолбэку.** GitHub Actions как бесплатный STT-движок для этого объёма **не годится**. whisperX имеет смысл в двух случаях: (а) кредиты у всех вендоров кончились и платить не хочется — тогда гонять локально на своей машине/GPU, а не в Actions; (б) требование «аудио не должно покидать мой контур» — но тогда это тоже локальный запуск. Как разовый инструмент (переобработать архив, сравнить качество с облаком) whisperX в Actions пригоден, как основной путь пайплайна — нет.

---

## 4. Рекомендация

### Основной выбор: **Deepgram, `model=nova-3`**

Почему он выигрывает именно по этим четырём требованиям:

1. **Самый большой бесплатный запас с большим отрывом** — $200 без срока годности и без карты ⇒ ~640–775 часов аудио ⇒ от 8 месяцев (тяжёлый сценарий) до ~2.5 лет (лёгкий). Ближайший конкурент даёт вчетверо меньше. ([pricing](https://deepgram.com/pricing))
2. **Диаризация в pre-recorded бесплатна** (в прайсе — «Included»), то есть кредиты тратятся только на минуты аудио. ([pricing](https://deepgram.com/pricing))
3. **Один HTTP-запрос** — POST с бинарным телом и API-ключом в заголовке, ответ синхронный. Для Android это буквально один OkHttp-вызов: ни upload-эндпоинта, ни polling-цикла, ни webhook-инфраструктуры (которой у телефона всё равно нет). Ни один другой кандидат, кроме ElevenLabs, этого не даёт.
4. **`utterances=true` возвращает готовые реплики по спикерам** — минимум кода на сборку `.md`. ([diarization](https://developers.deepgram.com/docs/diarization))
5. **RU+EN** покрывается двумя способами: `language=ru` или `language=multi` (code-switching, русский и английский в одном наборе). Можно выбрать эмпирически. ([models-languages-overview](https://developers.deepgram.com/docs/models-languages-overview))
6. **Транскрипты не хранятся на стороне Deepgram.** ([pre-recorded-audio](https://developers.deepgram.com/docs/pre-recorded-audio))

Стартовые параметры для noteapp:

```
POST https://api.deepgram.com/v1/listen
  ?model=nova-3
  &language=ru            # либо multi — проверить на своих записях
  &diarize_model=latest
  &utterances=true
  &punctuate=true
  &smart_format=true
Authorization: Token <KEY>
Content-Type: audio/m4a   # или тот формат, что пишет телефон
<binary>
```

Что проверить смоук-тестом до того, как закладываться: (а) `language=ru` vs `language=multi` на реальной записи с английскими терминами; (б) что 30-минутный файл не упирается в `504` по времени обработки; (в) что диаризация реально возвращает `speaker` при `language=multi`.

### Второе место: **AssemblyAI, `universal-2` + `speaker_labels`**

Берём, если Deepgram на реальных записях хуже по русскому. Плюсы: русский в документации отнесён к «High accuracy (≤10 % WER)» — единственная из «дешёвых» опций с публичной оценкой качества; `utterances[]` — самый чистый формат для заметки; жёсткие границы по числу спикеров; понятная политика хранения с self-serve opt-out. Минусы: $50 вместо $200 (4–12 месяцев вместо 8–30), диаризация +$0.02/ч, и три HTTP-вызова с polling вместо одного.

### Если решающим окажется качество русского, а не бесплатность

**ElevenLabs Scribe v2**: русский в категории «Excellent (≤5 % WER)» — лучшая заявленная точность из всех кандидатов, один multipart-вызов, но бесплатно только ~4.5 ч/мес. Как платный вариант — $0.22/ч, ~$5–17 в месяц при нашем объёме. Разумный сценарий: сидеть на бесплатном Deepgram, а Scribe держать как «включаемый по кнопке» вариант для важных записей.

### Когда включать фолбэк whisperX

Только когда (а) бесплатные кредиты исчерпаны и платить $3–15/мес не хочется, **или** (б) появилось требование, чтобы аудио вообще не уходило в облако. И в обоих случаях запускать **локально**, а не в GitHub Actions: на приватном репо квота 2 000 минут/мес и раннер 2 CPU / 8 ГБ, чего для 25–75 часов аудио в месяц не хватает на порядок, а те же минуты нужны Action'у с саммари.

---

## 5. Источники

1. Deepgram — Models & Languages Overview: https://developers.deepgram.com/docs/models-languages-overview
2. AssemblyAI — Supported Languages (pre-recorded): https://www.assemblyai.com/docs/pre-recorded-audio/supported-languages
3. ElevenLabs — Speech to Text / Transcription: https://elevenlabs.io/docs/capabilities/speech-to-text
4. Speechmatics — Languages: https://docs.speechmatics.com/speech-to-text/languages
5. Gladia — Supported languages: https://docs.gladia.io/chapters/speech-to-text-api/pages/languages
6. AssemblyAI — Models (+ таблица цен): https://www.assemblyai.com/docs/getting-started/models
7. Gladia — POST /v2/pre-recorded: https://docs.gladia.io/api-reference/v2/pre-recorded/init
8. Deepgram — Speaker Diarization: https://developers.deepgram.com/docs/diarization
9. AssemblyAI — Speaker Diarization: https://www.assemblyai.com/docs/pre-recorded-audio/label-speakers
10. Speechmatics — Batch diarization: https://docs.speechmatics.com/speech-to-text/batch/batch-diarization
11. Gladia — Speaker diarization: https://docs.gladia.io/chapters/speech-to-text-api/pages/speaker-diarization
12. Deepgram — Pricing: https://deepgram.com/pricing
13. AssemblyAI — Pricing: https://www.assemblyai.com/pricing
14. ElevenLabs — API Pricing: https://elevenlabs.io/pricing/api
15. Speechmatics — Pricing: https://www.speechmatics.com/pricing
16. Gladia — Pricing: https://www.gladia.io/pricing
17. AssemblyAI — Billing and Pricing: https://www.assemblyai.com/docs/billing-and-pricing
18. Gladia — Concurrency and Rate limits: https://docs.gladia.io/chapters/limits-and-specifications/concurrency
19. Deepgram — Pre-recorded audio: https://developers.deepgram.com/docs/pre-recorded-audio
20. AssemblyAI — Upload a media file: https://www.assemblyai.com/docs/pre-recorded-audio/api-reference/files/upload
21. Speechmatics — Limits (Batch): https://docs.speechmatics.com/speech-to-text/batch/limits
22. Gladia — Supported files & duration: https://docs.gladia.io/chapters/limits-and-specifications/supported-formats
23. AssemblyAI — Data retention and model training: https://www.assemblyai.com/docs/data-retention-and-model-training
24. Gladia — Data retention: https://docs.gladia.io/chapters/limits-and-specifications/data-retention
25. AssemblyAI — Submit a transcript: https://www.assemblyai.com/docs/pre-recorded-audio/api-reference/transcripts/submit
26. ElevenLabs — Speech-to-Text convert endpoint: https://elevenlabs.io/docs/api-reference/speech-to-text/convert
27. whisperX (GitHub): https://github.com/m-bain/whisperX
28. faster-whisper (GitHub, CPU-бенчмарк): https://github.com/SYSTRAN/faster-whisper
29. pyannote/speaker-diarization-community-1 (HuggingFace, гейтинг): https://huggingface.co/pyannote/speaker-diarization-community-1
30. GitHub Actions billing (2 000 минут/мес, GitHub Free): https://docs.github.com/en/billing/concepts/product-billing/github-actions
31. GitHub-hosted runners (2 CPU / 8 ГБ для приватных репо): https://docs.github.com/en/actions/reference/runners/github-hosted-runners
32. GitHub Actions limits (6 часов на job): https://docs.github.com/en/actions/reference/limits
33. Speechmatics — Authentication / supported endpoints: https://docs.speechmatics.com/get-started/authentication
34. Speechmatics — Regions: https://docs.speechmatics.com/administration/regions
35. Deepgram — Information Security & Privacy Statement: https://developers.deepgram.com/trust-security/information-security-privacy
36. AssemblyAI — Data Controls: https://www.assemblyai.com/docs/data-controls

---

## Смоук-тест на реальных записях (2026-08-24, тикет nikitatrubaev-pdj.7)

Прогон nova-3 на записях с OnePlus 13 (8 кГц mono m4a, живой API, ключ владельца).

- **API работает**: HTTP 200, 1–2с на 15с аудио. utterances[] несут speaker/start/end/transcript,
  words[] — speaker/start. Формат готов для сборки .md напрямую.
- **language=multi ЛУЧШЕ language=ru на русском с англицизмами** — выбор для v1:
  - multi: «pipeline упал на билде… смёржить main. Deadline пятница» (поймал техслова).
  - ru: потерял «билде», «смёржить»; «Дедлайн пятницы». Уверенность multi 0.899 > ru 0.887.
- **Диаризация НЕ разделила имитированный второй голос** (оба куска speaker=0). Причина: один
  физический человек + низкое качество 8 кГц. Поле speaker структурно присутствует. Открыто:
  проверить на записи ДВУХ реальных людей и на повышенном sample rate. Риск для сценария
  «разговор двоих» — средний; фолбэк AssemblyAI (у него диаризация заявлена сильнее) остаётся.
- **Качество транскрипта среднее** («тесты»→«течь», «смёржить»→«смёрчить») — во многом из-за
  8 кГц записи зонда. v1: поднять sample rate (44.1/48 кГц) — ожидаемо улучшит и текст, и диаризацию.

**Решение по параметрам v1: model=nova-3, language=multi, diarize=true, utterances=true,
punctuate=true, smart_format=true.** Живой образец ответа: docs/research/deepgram-sample-response.json
