# Ассистент-роль и long-press кнопки питания (Android 15 / OnePlus 13 / OxygenOS 15)

> Исследование по тикету `nikitatrubaev-pdj.2`. Дата: 2026-08-24.
> Источники: developer.android.com, source.android.com, android.googlesource.com (AOSP `main`).
> Всё, что не подтверждено официальным документом, помечено как **анекдотическое** или **вывод из кода**.

---

## Краткая сводка (TL;DR)

1. **Стать ассистентом можно.** Требования зафиксированы в AOSP: либо `VoiceInteractionService` с корректным XML-метаданным, либо активити с `ACTION_ASSIST`. В AOSP «невидимый» тумблер даёт первый вариант — но на OnePlus, вероятно, придётся использовать второй (см. п. 9).
2. **Роль нельзя запросить программно.** `ROLE_ASSISTANT` помечен `requestable="false"` — `createRequestRoleIntent()` молча провалится. Пользователь ставит приложение ассистентом руками в Настройках.
3. **Long-press power → ассистент — это штатный механизм AOSP**, дефолт `config_longPressOnPowerBehavior = 5`. Он маршрутизирует в того, кто держит роль, а не в Google. НО есть легальный механизм перехвата (`OverviewProxyService` override) и есть OEM-прошивка.
4. **Повторный вызов работает.** В коде нет ни дебаунса, ни проверки «сессия уже показана» — `onShow()` вызывается каждый раз на том же объекте сессии.
5. **Главный риск — экран выключен.** По дефолту AOSP `config_supportLongPressPowerWhenNonInteractive = false`: если экран был погашен в момент нажатия, long-press power **не** зовёт ассистента. Заблокированный, но включённый экран — зовёт.
6. **Микрофон с локскрина — легален.** Есть явно документированное исключение из FGS while-in-use ограничений для приложения, предоставляющего `VoiceInteractionService`.
7. **Альтернатив роли ассистента нет.** Кнопка питания снимается с очереди событий до доставки в приложения (`result &= ~ACTION_PASS_TO_USER`), поэтому ни accessibility-сервис, ни Tasker/Button Mapper её перехватить не могут.
8. **Мина замедленного действия:** забытый `recognitionService` в XML → система молча сбрасывает роль ассистента при каждом обновлении APK (§1.4a).
9. 🔴 **Главное про OnePlus:** есть свидетельство (анекдотическое, но снятое **на OnePlus 13** при решении ровно нашей задачи), что OPPO/ColorOS **блокирует `VoiceInteractionService`** на кнопке питания (`IsQuickLaunchSupport=false`), и работает только путь через `ACTION_ASSIST`-активити. Поэтому §6 описывает **две** реализации, переключаемые одной строкой манифеста, а probe обязан проверить обе **первым делом** (§2.7.1, P1).
10. **Официального мануала OxygenOS 15 не существует** — новейший опубликованный OnePlus мануал это OxygenOS 14. Все пути настроек для 15 — вторичные источники (§2.7.0).
11. **Лучший запасной триггер — не long-press home** (он недоступен на локскрине), а **жесты при выключенном экране** и **Quick Launch** по отпечатку: оба работают с заблокированного, а первый — и с погашенного экрана.

---

## 1. Что приложение должно реализовать, чтобы стать ассистентом (Android 15)

### 1.1. Формальное требование роли

Имя роли и условия квалификации:

> ```java
> /**
>  * The name of the assistant role.
>  * <p>
>  * To qualify for this role, an application needs to either implement
>  * {@link android.service.voice.VoiceInteractionService} or handle
>  * {@link android.content.Intent#ACTION_ASSIST}. The application will be able to access call log
>  * and SMS for its functionality.
>  */
> public static final String ROLE_ASSISTANT = "android.app.role.ASSISTANT";
> ```
> — `androidx/core/role/RoleManagerCompat.java`
> https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/core/core-role/src/main/java/androidx/core/role/RoleManagerCompat.java

То же в документации AOSP по ролям (таблица ролей, строка ASSISTANT):

> "At least one of: The app has an activity that performs assist actions… The app has an always-on voice interaction service gated by the `android.permission.BIND_VOICE_INTERACTION` permission, which can perform voice recognition and host active voice interaction sessions. Additionally, the app has an explicit flag indicating that the service is capable of handling the assist action."
> https://source.android.com/docs/core/permissions/android-roles

### 1.2. Точная проверка квалификации (что реально проверяет система)

`AssistantRoleBehavior.isAssistantVoiceInteractionService()` — приложение проходит как VIA-ассистент, только если ВСЕ условия выполнены:

* сервис объявлен с `android:permission="android.permission.BIND_VOICE_INTERACTION"`;
* у сервиса есть meta-data `android.voice_interaction`, ссылающаяся на XML;
* в этом XML заданы **все три**: `sessionService`, `recognitionService`, `supportsAssist="true"`.
  Если `sessionService == null || recognitionService == null || !supportsAssist` → `return false`.

Альтернативный путь — экспортированная активити с `<action android:name="android.intent.action.ASSIST"/>`; запрос идёт с `MATCH_DEFAULT_ONLY`, то есть нужна и `CATEGORY_DEFAULT`; неэкспортированные активити отбрасываются (`if (!activityInfo.exported) continue;`).

> https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/main/PermissionController/role-controller/java/com/android/role/controller/behavior/AssistantRoleBehavior.java

Определение роли в `roles.xml` (комментарий с «грубым описанием» повторяет то же):

> ```xml
> <role name="android.app.role.ASSISTANT" behavior="AssistantRoleBehavior"
>       defaultHolders="config_defaultAssistant" exclusive="true" exclusivity="user"
>       fallBackToDefaultHolder="true" showNone="true"
>       overrideUserWhenGranting="true" requestable="false" …>
> ```
> https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/main/PermissionController/res/xml/roles.xml

### 1.3. ⚠️ Роль нельзя запросить из приложения

`requestable="false"` означает буквально: «Whether this role is requestable by applications with `RoleManager#createRequestRoleIntent(String)`»
(https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/main/PermissionController/role-controller/java/com/android/role/controller/model/Role.java).

`RequestRoleActivity` явно отклоняет такой запрос:

> ```java
> if (!role.isRequestable()) {
>     Log.e(LOG_TAG, "Role is not requestable: " + mRoleName);
>     reportRequestResult(…ROLE_REQUEST_RESULT_REPORTED__RESULT__IGNORED);
>     finish();
>     return;
> }
> ```
> https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/main/PermissionController/src/com/android/permissioncontroller/role/ui/RequestRoleActivity.java

**Практический вывод:** приложение может только *отвести пользователя* в нужный экран настроек. Публичные intent-экшены для этого:

* `Settings.ACTION_VOICE_INPUT_SETTINGS` = `"android.settings.VOICE_INPUT_SETTINGS"`
* `Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS` = `"android.settings.MANAGE_DEFAULT_APPS_SETTINGS"`
  (оба объявлены с `@SdkConstant` без `@hide` в `android/provider/Settings.java`)
  https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/provider/Settings.java

Проверять факт владения ролью можно через `RoleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)`
https://developer.android.com/reference/android/app/role/RoleManager

### 1.4. Что происходит после назначения роли

`VoiceInteractionManagerService.RoleObserver.onRoleHoldersChanged()`:

1. Ищет у пакета `VoiceInteractionService` с `supportsAssist`. Нашёл → пишет **и** `Settings.Secure.ASSISTANT`, **и** `Settings.Secure.VOICE_INTERACTION_SERVICE` = компонент сервиса.
2. Если `recognitionService == null` — логирует ошибку и записывает **пустую строку** (то есть ассистент фактически сбрасывается):
   > "The RecognitionService must be set to avoid boot loop on earlier platform version…"
3. Если VIA-сервиса нет — ищет активити `ACTION_ASSIST`, пишет её в `Secure.ASSISTANT`, а `VOICE_INTERACTION_SERVICE` = `""`.

> https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/voiceinteraction/java/com/android/server/voiceinteraction/VoiceInteractionManagerService.java

### 1.4a. 🔴 Ловушка: система сама сбросит ассистента при обновлении APK

В том же файле есть `PackageMonitor`, который при **появлении/обновлении** пакета текущего ассистента (`isPackageAppearing`) вызывает:

```java
private void resetServicesIfNoRecognitionService(ComponentName serviceComponent, int userHandle) {
    for (ResolveInfo resolveInfo : queryInteractorServices(userHandle, serviceComponent.getPackageName())) {
        VoiceInteractionServiceInfo serviceInfo = new VoiceInteractionServiceInfo(…);
        if (!serviceInfo.getSupportsAssist()) { continue; }
        if (serviceInfo.getRecognitionService() == null) {
            Slog.e(TAG, "The RecognitionService must be set to avoid boot loop on earlier platform version. …");
            setCurInteractor(null, userHandle);
            resetCurAssistant(userHandle);          // ← роль ассистента слетает
        }
    }
}
```
(вызывается из `onSomePackagesChanged`: `change = isPackageAppearing(curInteractor.getPackageName()); if (change != PACKAGE_UNCHANGED) { resetServicesIfNoRecognitionService(curInteractor, userHandle); … }`)
https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/voiceinteraction/java/com/android/server/voiceinteraction/VoiceInteractionManagerService.java

⇒ **Если забыть `recognitionService` в XML, приложение будет молча терять роль ассистента при каждой переустановке/обновлении.** Это же — правдоподобное объяснение публичных жалоб «сторонний ассистент сам сбрасывается на Gemini» (см. §2.5, вторичные источники).

**Практический вывод для CI/разработки:** каждый `./gradlew installDebug` — это `isPackageAppearing`. Атрибут `recognitionService` обязан присутствовать всегда, иначе отладка превратится в «после каждой сборки заново назначай ассистента».

Атрибуты XML (`res/values/attrs.xml`, styleable `VoiceInteractionService`):

> `sessionService` — "The service that hosts active voice interaction sessions. **This is required.**"
> `recognitionService` — "**This is required.** … From Android 12 onward, this attribute does nothing. However, we still require it to be set to something… the system will reset the current assistant if this isn't specified."
> `supportsAssist` — "Flag indicating whether this voice interaction service is capable of handling the assist action."
> `supportsLaunchVoiceAssistFromKeyguard` — "Flag indicating whether this voice interaction service is capable of being launched from the keyguard."
> https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/res/res/values/attrs.xml

### 1.5. Минимальная реализация, которая получает вызов по кнопке питания

Вызов по кнопке питания доходит **до обоих** типов ассистента, но по-разному. `AssistManager` (SystemUI):

```java
final boolean isService = assistComponent.equals(getVoiceInteractorComponentName());
…
private void startAssistInternal(Bundle args, ComponentName assistComponent, boolean isService) {
    if (isService) { startVoiceInteractor(args); }   // → onShow() в сессии, без активити
    else           { startAssistActivity(args, assistComponent); }  // → запуск Activity
}
```
https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SystemUI/src/com/android/systemui/assist/AssistManager.java

* **Вариант «только `ACTION_ASSIST` активити»** — минимальный по коду, но система запускает *активити*. Для нас это плохо: на локскрине это означает окно поверх замка, мигание UI и зависимость от `FLAG_SHOW_WHEN_LOCKED`.
* **Вариант «`VoiceInteractionService`»** — минимальный по UX: `onShow()` прилетает в сессию, а UI можно вообще выключить (`setUiEnabled(false)`, «If set to false, you will not be able to provide a UI through `onCreateContentView()`»
  https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/service/voice/VoiceInteractionSession.java).

**Рекомендуемый минимум для noteapp** — три класса + один XML:

```xml
<!-- AndroidManifest.xml -->
<service android:name=".AssistService"
         android:permission="android.permission.BIND_VOICE_INTERACTION"
         android:exported="true">
    <intent-filter>
        <action android:name="android.service.voice.VoiceInteractionService" />
    </intent-filter>
    <meta-data android:name="android.voice_interaction"
               android:resource="@xml/voice_interaction_service" />
</service>

<!-- exported не нужен: система биндит по явному ComponentName, system_server обходит проверку экспорта -->
<service android:name=".AssistSessionService"
         android:permission="android.permission.BIND_VOICE_INTERACTION" />

<!-- нужен, чтобы recognitionService указывал на что-то валидное -->
<service android:name=".DummyRecognitionService" android:exported="true">
    <intent-filter>
        <action android:name="android.speech.RecognitionService" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>
    <meta-data android:name="android.speech" android:resource="@xml/recognition_service" />
</service>
```

```xml
<!-- res/xml/voice_interaction_service.xml -->
<voice-interaction-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:sessionService=".AssistSessionService"
    android:recognitionService=".DummyRecognitionService"
    android:supportsAssist="true"
    android:supportsLaunchVoiceAssistFromKeyguard="true" />
```

Структура подтверждена двумя первоисточниками:

* официальный гайд AOSP (раздел Automotive, но описывает те же платформенные компоненты)
  https://source.android.com/docs/automotive/voice/voice_interaction_guide/app_development
* эталонный манифест тестового приложения в AOSP — `frameworks/base/tests/VoiceInteraction/AndroidManifest.xml`:
  ```xml
  <service android:name="MainInteractionService"
       android:permission="android.permission.BIND_VOICE_INTERACTION"
       android:process=":interactor" android:exported="true">
      <meta-data android:name="android.voice_interaction" android:resource="@xml/interaction_service"/>
      <intent-filter><action android:name="android.service.voice.VoiceInteractionService"/></intent-filter>
  </service>
  <service android:name="MainInteractionSessionService"
       android:permission="android.permission.BIND_VOICE_INTERACTION"
       android:process=":session" />
  <service android:name="MainRecognitionService" android:exported="true">
      <intent-filter><action android:name="android.speech.RecognitionService"/>
          <category android:name="android.intent.category.DEFAULT"/></intent-filter>
      <meta-data android:name="android.speech" android:resource="@xml/recognition_service"/>
  </service>
  ```
  https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/tests/VoiceInteraction/AndroidManifest.xml

  Обратите внимание: session-сервис **без** `exported` и **без** intent-filter — система биндит его по явному `ComponentName`
  (`mBindIntent.setComponent(mSessionComponentName)` в `VoiceInteractionSessionConnection`), а system_server обходит проверку экспорта.

  `android:process=":session"` — рекомендация javadoc («that service should run in a separate process from this one»,
  https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/service/voice/VoiceInteractionService.java).
  Для noteapp это **не обязательно** и скорее вредно: проще держать сессию и `RecordingService` в одном процессе, чтобы состояние тумблера было общим в памяти. Проверка while-in-use в `ActiveServices` идёт **по UID**, а не по процессу, поэтому привилегия сохраняется и при одном процессе.

Константы сервиса: `SERVICE_INTERFACE = "android.service.voice.VoiceInteractionService"`, `SERVICE_META_DATA = "android.voice_interaction"`, и требование `BIND_VOICE_INTERACTION` — «so that other applications can not abuse it»
https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/service/voice/VoiceInteractionService.java

### 1.6. Какие права даёт роль (и каких НЕ даёт)

Из `roles.xml`, блок `<permissions>` роли ASSISTANT: набор `sms`, `READ_CALL_LOG`, `ACCESS_BLOBS_ACROSS_USERS` (31+), `READ_ASSISTANT_APP_SEARCH_DATA` (33+), `SUBSCRIBE_TO_KEYGUARD_LOCKED_STATE` (33+), `EXECUTE_APP_ACTION` (34+), `MANAGE_CONTENT_SUGGESTIONS` (35+), `EMBED_ANY_APP_IN_UNTRUSTED_MODE` (35+); app-op `SYSTEM_ALERT_WINDOW`.

**`RECORD_AUDIO` в этом списке НЕТ.** Микрофон предгрантится только системным/предустановленным пакетам: `DefaultPermissionGrantPolicy` вызывает `grantPermissionsToSystemPackage(pm, voiceInteractPackageName, userId, CONTACTS…, MICROPHONE_PERMISSIONS, …)` — то есть только для system package.
https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/pm/permission/DefaultPermissionGrantPolicy.java

AOSP это подтверждает прямо: "Some of these permissions are pregranted to the default `VoiceInteractionService`… only the default VIA would have these permissions pre-granted."
https://source.android.com/docs/automotive/voice/voice_interaction_guide/integration_flows

⇒ **Пользователь всё равно должен вручную дать `RECORD_AUDIO`** нашему sideload-приложению.

---

## 2. Кнопка питания → ассистент

### 2.1. Механика в AOSP

`PhoneWindowManager` (`services/core/java/com/android/server/policy/PhoneWindowManager.java`,
https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/policy/PhoneWindowManager.java):

```java
static final int LONG_PRESS_POWER_NOTHING = 0;
static final int LONG_PRESS_POWER_GLOBAL_ACTIONS = 1;
static final int LONG_PRESS_POWER_SHUT_OFF = 2;
static final int LONG_PRESS_POWER_SHUT_OFF_NO_CONFIRM = 3;
static final int LONG_PRESS_POWER_GO_TO_VOICE_ASSIST = 4;
static final int LONG_PRESS_POWER_ASSISTANT = 5;  // Settings.Secure.ASSISTANT
```

```java
case LONG_PRESS_POWER_ASSISTANT:
    mPowerKeyHandled = true;
    performHapticFeedback(HapticFeedbackConstants.ASSISTANT_BUTTON, "Power - Long Press - Go To Assistant");
    final int powerKeyDeviceId = INVALID_INPUT_DEVICE_ID;
    launchAssistAction(null, powerKeyDeviceId, eventTime,
            AssistUtils.INVOCATION_TYPE_POWER_BUTTON_LONG_PRESS);
    break;
```

Полная цепочка вызова:

```
power long-press
 → PhoneWindowManager.powerLongPress()
 → launchAssistAction(…, INVOCATION_TYPE_POWER_BUTTON_LONG_PRESS /* = 6 */)
 → SearchManager.launchAssist(args)                      (core/java/android/app/SearchManager.java)
 → SearchManagerService.launchAssist()  → StatusBarManagerInternal.startAssist(args)
 → SystemUI AssistManager.startAssist(args)
 → getAssistInfo()  == Settings.Secure.ASSISTANT
 → isService ? AssistUtils.showSessionForActiveService(args, SHOW_SOURCE_ASSIST_GESTURE, …)
             : startAssistActivity(args, assistComponent)
 → VoiceInteractionManagerService.showSessionForActiveService
 → VoiceInteractionManagerServiceImpl.showSessionLocked
 → VoiceInteractionSessionConnection.showLocked  → mSession.show(...)
 → VoiceInteractionSession.doShow()  → onPrepareShow() → onShow(args, flags)
```

Источники цепочки:
* https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/SearchManager.java
* https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/search/SearchManagerService.java
* https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/com/android/internal/app/AssistUtils.java (`INVOCATION_TYPE_POWER_BUTTON_LONG_PRESS = 6`)
* https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/voiceinteraction/java/com/android/server/voiceinteraction/VoiceInteractionSessionConnection.java

**Вывод: маршрутизация идёт по `Settings.Secure.ASSISTANT`, то есть в держателя роли. Никакого хардкода Google в AOSP нет.**

### 2.2. Дефолты AOSP

`core/res/res/values/config.xml`
(https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/res/res/values/config.xml):

```xml
<!-- Control the behavior when the user long presses the power button.
        0 - Nothing / 1 - Global actions menu / 2 - Power off (confirm)
        3 - Power off (no confirm) / 4 - Go to voice assist
        5 - Go to assistant (Settings.Secure.ASSISTANT) -->
<integer name="config_longPressOnPowerBehavior">5</integer>
<integer name="config_longPressOnPowerDurationMs">500</integer>
<integer-array name="config_longPressOnPowerDurationSettings">
    <item>250</item><item>350</item><item>500</item><item>650</item><item>750</item>
</integer-array>
<bool name="config_longPressOnPowerForAssistantSettingAvailable">true</bool>
<bool name="config_showDefaultAssistant">true</bool>
```

### 2.3. Настройка в UI и её эквивалент в adb

История экрана в стоковых Настройках (по веткам `packages/apps/Settings`):

| Версия | Экран | Контрол |
|---|---|---|
| Android 11 | экран `power_menu_settings` есть, но пункта long-press power **нет** | — |
| Android 12 | «Press and hold power button» | switch `gesture_power_menu_long_press_for_assist` («Hold for Assistant») |
| Android 13 | то же + ползунок чувствительности | `LongPressPowerSensitivityPreferenceController` |
| Android 14 | «Press & hold power button» | **два радио**: «Power menu» / «Digital assistant» |
| Android 15 | то же, что 14 | `LongPressPowerForPowerMenuPreferenceController` / `LongPressPowerForAssistantPreferenceController` |

https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/android15-release/res/xml/power_menu_settings.xml
(и те же файлы в ветках `android11-release` … `android14-release`)

Значение `config_longPressOnPowerBehavior` в AOSP: `1` в Android 11 → `5` начиная с Android 12 и далее (сверено по веткам `android11-release`, `android12-release`, `android15-release`, `android16-release` файла `core/res/res/values/config.xml`). Оверлей OEM может это переопределить.

Ничто в UI Настроек не ссылается на Google/Gemini/конкретный пакет — только на роль
(https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/android15-release/src/com/android/settings/gestures/LongPressPowerForAssistantPreferenceController.java).

Пункт «Digital assistant app» в Настройках — это просто ярлык в PermissionController: `Intent.ACTION_MANAGE_DEFAULT_APP` с `EXTRA_ROLE_NAME = RoleManager.ROLE_ASSISTANT`
(https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/android15-release/src/com/android/settings/applications/assist/DefaultAssistPreferenceController.java).
Этот экшен `@SystemApi` и требует `MANAGE_ROLE_HOLDERS` (§1.3), поэтому стороннему приложению остаётся `ACTION_MANAGE_DEFAULT_APPS_SETTINGS`.


`PowerMenuSettingsUtils` (Settings app) — вся логика экрана «Press & hold power button»:

```java
private static final int LONG_PRESS_POWER_GLOBAL_ACTIONS = 1;   // Power menu
private static final int LONG_PRESS_POWER_ASSISTANT_VALUE = 5;  // Digital assistant

public static boolean isLongPressPowerSettingAvailable(Context context) {
    if (!res.getBoolean(config_longPressOnPowerForAssistantSettingAvailable)) return false;
    switch (res.getInteger(config_longPressOnPowerBehavior)) {
        case LONG_PRESS_POWER_GLOBAL_ACTIONS:
        case LONG_PRESS_POWER_ASSISTANT_VALUE:
            return true;   // "We support switching between Power Menu and Digital Assistant."
        default:
            return false;  // "All other combinations are not supported."
    }
}

public static boolean setLongPressPowerForAssistant(Context context) {
    Settings.Global.putInt(cr, Settings.Global.POWER_BUTTON_LONG_PRESS, 5);
    Settings.Global.putInt(cr, Settings.Global.KEY_CHORD_POWER_VOLUME_UP, 2); // power menu на Power+VolUp
}
```
https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/main/src/com/android/settings/gestures/PowerMenuSettingsUtils.java

Соответствующие ключи (`Settings.Global`, `@hide`, но `@Readable` и доступны shell'у):

* `POWER_BUTTON_LONG_PRESS` = `"power_button_long_press"` — "Overrides internal `R.integer.config_longPressOnPowerBehavior`."
* `POWER_BUTTON_LONG_PRESS_DURATION_MS` — длительность удержания
* `KEY_CHORD_POWER_VOLUME_UP`
  https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/provider/Settings.java

⇒ **adb-обходной путь, если UI на OxygenOS не даёт выбрать:**

```bash
adb shell settings put global power_button_long_press 5
adb shell settings put secure assistant com.example.noteapp/.AssistService
adb shell settings put secure voice_interaction_service com.example.noteapp/.AssistService
adb shell settings put global key_chord_power_volume_up 2
```

`Settings.Secure.ASSISTANT` — "The current assistant component. It could be a voice interaction service, or an activity that handles ACTION_ASSIST… This should be set indirectly by setting the assistant role."
`Settings.Secure.VOICE_INTERACTION_SERVICE` — "The currently selected voice interaction service flattened ComponentName."
(там же, `Settings.java`)

⚠️ Записывать напрямую в `secure.assistant` — это обход `RoleManager`. `RoleObserver` перезапишет эти значения при следующем изменении держателя роли. Как долгоживущая конфигурация это ненадёжно; как способ **разблокировать проверку на устройстве** — годится.

### 2.4. Длительность удержания

Когда поведение = ассистент, используется отдельный таймаут:

```java
@Override long getLongPressTimeoutMs() {
    if (getResolvedLongPressOnPowerBehavior() == LONG_PRESS_POWER_ASSISTANT) {
        return mLongPressOnPowerAssistantTimeoutMs;   // Settings.Global.POWER_BUTTON_LONG_PRESS_DURATION_MS
    } else { return super.getLongPressTimeoutMs(); }
}
```
(`PhoneWindowManager`, ссылка выше). Дефолт 500 мс, пользователь может менять «чувствительность» из списка 250/350/500/650/750.

### 2.5. ⚠️ Легальный механизм перехвата: `OverviewProxyService`

В `AssistManager.startAssist()` **до** всякой маршрутизации по роли:

```java
if (shouldOverrideAssist(args)) {
    mOverviewProxyService.getProxy().onAssistantOverrideInvoked(args.getInt(INVOCATION_TYPE_KEY));
    return;
}
…
public boolean shouldOverrideAssist(int invocationType) {
    return mAssistOverrideInvocationTypes != null
        && Arrays.stream(mAssistOverrideInvocationTypes).anyMatch(o -> o == invocationType);
}

/** @param invocationTypes The invocation types that will henceforth be handled via
 *  OverviewProxy (Launcher); other invocation types should be handled by this class. */
public void setAssistantOverridesRequested(int[] invocationTypes) { … }
```
https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SystemUI/src/com/android/systemui/assist/AssistManager.java

Кто может это вызвать: `setAssistantOverridesRequested` — метод биндер-интерфейса `ISystemUiProxy`, который SystemUI отдаёт **только компоненту recents/лончеру**:

```java
@Override
public void setAssistantOverridesRequested(int[] invocationTypes) {
    verifyCallerAndClearCallingIdentityPostMain("setAssistantOverridesRequested", () ->
            notifyAssistantOverrideRequested(invocationTypes));
}
```
https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SystemUI/src/com/android/systemui/recents/OverviewProxyService.java

То есть **лончер** (на Pixel — Google-овский QuickStep, на OnePlus — OEM-овский) может заявить, что берёт на себя конкретные типы вызова, включая `INVOCATION_TYPE_POWER_BUTTON_LONG_PRESS = 6`, и тогда до держателя роли вызов не дойдёт вовсе. Это ровно тот механизм, которым «power = Gemini» может быть реализовано **в обход роли ассистента** — формально роль будет наша, а кнопка будет открывать Gemini. Документации на это нет; **проверяется только на устройстве (см. P3)**.

Каждый показ получает **новый** `KEY_SHOW_SESSION_ID` (`getNextShowSessionId()` в `showSessionLocked`) — по нему можно отличать вызовы в логах.

### 2.5a. Полевые свидетельства: сторонние ассистенты на кнопке питания работают

**[ВТОРИЧНЫЕ ИСТОЧНИКИ, не Google-документация]** — но они прямо про наш сценарий:

* ChatGPT (бета v1.2025.070, март 2025) добавил себя в список ассистентов по умолчанию: «You can also long-press the power button to launch it if you have that setting enabled»; при этом «ChatGPT cannot be invoked with a hotword, as that functionality requires access to privileged APIs only available to trusted, preinstalled apps».
  https://www.androidauthority.com/chatgpt-default-assistant-on-android-3535089/
* «Long-pressing the power button will trigger ChatGPT's voice mode with a bubble overlay on the screen.»
  https://9to5google.com/2025/03/14/chatgpt-default-assistant-android/
* Perplexity Assistant: сообщения о том, что ассистент сам возвращается на Gemini после обновлений и перезагрузок; причина официально не установлена.
  https://www.androidauthority.com/perplexity-auto-switches-to-gemini-3534111/
  ⚠️ У этого симптома есть **точное объяснение в AOSP** — `resetServicesIfNoRecognitionService()` (§1.4a). Скорее всего это не «Google переключает», а отсутствующий `recognitionService`.

⇒ Сторонний ассистент на long-press power — не теоретическая возможность, а работающая на практике схема как минимум на части устройств.

### 2.5b. Почему нельзя обойтись без роли ассистента

Кнопка питания принципиально недоступна приложениям и accessibility-сервисам. `PhoneWindowManager.interceptKeyBeforeQueueing()`:

```java
case KeyEvent.KEYCODE_POWER: {
    …
    // Any activity on the power button stops the accessibility shortcut
    result &= ~ACTION_PASS_TO_USER;
```
https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/policy/PhoneWindowManager.java

Событие питания снимается с очереди до доставки в приложения, поэтому Tasker / AutoInput / Button Mapper и любые `AccessibilityService.onKeyEvent()` кнопку питания перехватить не могут (Button Mapper прямо пишет в описании: "does not work with … the power button" — https://play.google.com/store/apps/details?id=flar2.homebutton&hl=en, **вторичный источник**).

⇒ **Роль ассистента — единственный легальный способ получить long-press питания в стороннем приложении.** Альтернатив нет.

### 2.6. Что говорит CDD (важно для оценки риска OEM)

Android 15 CDD (Compatibility Definition Document) — документ, который OEM обязан выполнить, чтобы ставить Google-сервисы:

CDD 3.8.4 **[C-2-2]**:

> "The designated interaction to launch the assist app as described in section 7.2.3 MUST launch the user-selected assist app, in other words the app that implements `VoiceInteractionService`, or an activity handling the `ACTION_ASSIST` intent."

и 3.8.4 **[H-SR-2]** — «STRONGLY RECOMMENDED to use long press on HOME key as the designated interaction to launch the assist app».
https://source.android.com/docs/compatibility/15/android-15-cdd

Два вывода:

1. **CDD прямо признаёт «user-selected assist app», включая стороннее приложение** — то есть блокировать сторонний ассистент на уровне роли OEM не должен.
2. **CDD НЕ требует, чтобы long-press power вёл к ассистенту.** Обязательный (точнее, STRONGLY RECOMMENDED) жест — long-press HOME. Поведение кнопки питания — целиком на усмотрение OEM. Это главный источник риска для нашего сценария и одновременно основание для запасного плана: long-press home / nav-handle отдаёт тот же `onShow` (§3.3).

### 2.7. OxygenOS 15 / OnePlus 13 — конкретика

#### 2.7.0. Состояние источников

**Официального мануала OxygenOS 15 не существует.** Прямые пробы CDN OnePlus (`OxygenOS_15.0_User_Manual.pdf`, `OnePlus_13_User_Manual*.pdf` в `/common/` и `/en/`) возвращают **HTTP 404**. Новейший опубликованный системный мануал — **OxygenOS 14.0**:
https://service.oneplus.com/content/dam/support/user-manuals/common/OxygenOS_14.0_User_Manual.pdf
Всё, что специфично именно для OxygenOS 15, ниже опирается на вторичные источники и помечено.

#### 2.7.1. 🔴 ГЛАВНАЯ НАХОДКА: OxygenOS/ColorOS блокирует `VoiceInteractionService` на кнопке питания

**[АНЕКДОТИЧЕСКИЙ источник, но протестирован ровно на нашем устройстве]**
https://github.com/moltbot/moltbot/pull/3790 · дифф: https://patch-diff.githubusercontent.com/raw/moltbot/moltbot/pull/3790.diff

> "OPPO's `PhoneWindowManager` sets `IsQuickLaunchSupport=false` for apps with `VoiceInteractionService`, blocking long-press power button from triggering the assistant."
> "Disabling the service makes OPPO fall back to the standard `ACTION_ASSIST` intent path (same as App Manager does)."

Разработчик решал **ровно нашу задачу** и в итоге **отключил** свой `VoiceInteractionService`, оставив `ACTION_ASSIST`-активити:

```xml
<!-- DISABLED: OPPO/ColorOS blocks VoiceInteractionService with IsQuickLaunchSupport=false
     Using ASSIST intent filter on MainActivity instead (like App Manager does) -->
<service android:name=".assistant.ClawdbotVoiceInteractionService"
         android:enabled="false" ... />
...
<activity android:name=".MainActivity" android:launchMode="singleTop">
    <intent-filter>
        <action android:name="android.intent.action.ASSIST" />
        <action android:name="android.intent.action.VOICE_ASSIST" />
        <action android:name="android.intent.action.VOICE_COMMAND" />
        <action android:name="android.intent.action.SEARCH_LONG_PRESS" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>
</activity>
```

Заявлено «tested working on **OnePlus 13** (ColorOS)», сборка **OxygenOS 16.0.3.501**.

**Как это оценивать честно:**
* Это **один** разработчик, **одно** устройство, и версия **OxygenOS 16**, а не 15. Второго подтверждения нет. `IsQuickLaunchSupport` — проприетарная строка OPPO, в AOSP её нет, проверить по исходникам невозможно.
* Но это самое релевантное свидетельство во всём исследовании: та же модель телефона, та же задача, эмпирическая проверка.
* **Оно переворачивает рекомендацию из §1.5.** Каноничный AOSP-путь (`VoiceInteractionService`) на этом OEM может оказаться именно тем, что ломает кнопку, а «примитивный» путь `ACTION_ASSIST`-активити — рабочим.

⇒ **Probe обязан проверить ОБА варианта** (P1a/P1b ниже). Архитектура §6 переписана так, чтобы переключение между ними стоило одну строку в манифесте.

#### 2.7.2. Путь в настройках

**[ВТОРИЧНЫЕ, три независимых источника согласны]** —
`Settings → Accessibility & convenience → Power button → Press and hold the Power button` → **«Power menu»** / **«Digital assistant»**:
* https://www.trustedreviews.com/news/oneplus-13-tips-tricks-4598165 (статья именно про OnePlus 13)
* https://gadgets.beebom.com/guides/how-to-switch-off-oneplus-phones
* https://www.cashify.in/how-to-switch-off-oneplus-phone (указывает, что вариант «Voice Assistant» — **по умолчанию**)

**[ОФИЦИАЛЬНО]** Старый путь дословно из мануала OxygenOS 14:
> "Go to 'Settings > Additional settings > Power Button > Press and hold the power button', you can choose to bring up the 'Power Menu' or activate the 'Voice Assistant' by pressing and holding the Power button."

То есть переключатель у OnePlus **есть** и исторически по умолчанию стоит на ассистенте. Переименование `Additional settings` → `Accessibility & convenience` в OxygenOS 15 подтверждено только вторично.

Это согласуется с AOSP-дефолтом `config_longPressOnPowerBehavior = 5` и таймаутом 500 мс (§2.2): вторичный источник описывает то же поведение — «hold the power button for half a second to launch the Google Assistant», а меню выключения уезжает на 3-секундное удержание
(https://oneplus.gadgethacks.com/how-to/make-power-button-launch-google-assistant-your-oneplus-0214266/).

#### 2.7.3. Пикер «Digital assistant app»

**[АНЕКДОТИЧЕСКИЙ, но device-specific]** Тот же PR: «Open Settings > Apps > Default Apps > Digital assistant app», и «✅ Clawdbot appears in 'Digital assistant app' settings» — проверено на **OnePlus 13**.
⇒ Пикер существует и **показывает сторонние приложения**.

**[ОФИЦИАЛЬНО, отрицательный результат]** В мануале OxygenOS 14 раздел «Setting the default app» описывает только типы файлов (TXT/PDF/Word/Excel/PPT) и просьбу не менять Launcher/Messages/Phone/Browser — **пикера ассистента там нет вообще**. OnePlus его не документирует.

**[ОФИЦИАЛЬНО, AOSP]** Напоминание: OEM может скрыть пикер целиком через `config_showDefaultAssistant` (дефолт `true`, §2.2). Проверить на устройстве.

#### 2.7.4. Гемини — дефолт, а не монополия

**[ОФИЦИАЛЬНО]** Страница OxygenOS 15 у OnePlus продвигает Gemini как «your AI assistant from Google» — https://www.oneplus.com/us/oxygenos15
**[ВТОРИЧНО]** «Gemini as the default smart assistant» — https://www.androidauthority.com/oxygen-os-15-review-3493444/

Свидетельств того, что OnePlus **архитектурно** прибивает Gemini к кнопке питания, не найдено: подпись пункта родовая («Digital assistant»), маршрутизация — через `Settings.Secure.ASSISTANT`. Ограничение из §2.7.1 касается механизма `VoiceInteractionService`, а не «Google против не-Google».

#### 2.7.5. Исправление: у OnePlus 13 нет «кнопки быстрого доступа»

**[ВТОРИЧНО]** У OnePlus 13 — классический **Alert Slider** (трёхпозиционный ползунок звука), а не программируемая кнопка:
> «the power key and the volume rocker are on the right, while the knurled alert slider is on the left»
> https://www.gsmarena.com/oneplus_13-review-2777p2.php

Программируемая **Plus Key / Shortcut key** появилась на **OnePlus 13T / 13s** — это другой аппарат (https://www.androidauthority.com/oneplus-13t-shortcut-key-3542093/), и она **не умеет запускать сторонние приложения** (фиксированный список: Mind Space, Sound & vibration, DND, Camera, Torch, Recorder, Translate, Screenshot, No action — https://gadgetbridge.com/how-to/what-is-the-plus-key-on-oneplus-13s-heres-how-you-can-customise-it).

**Alert Slider публичного API не имеет.** Единственная зацепка **[АНЕКДОТИЧЕСКИ]** — глобальная настройка `three_Key_mode` (3 = Ring, 2 = Vibrate, 1 = Silence), читаемая через `Settings.Global.getInt()` — https://github.com/home-assistant/android/issues/2239. `ContentObserver` на неё сработает, **только пока процесс уже жив**, поэтому как холодный триггер старта она бесполезна; теоретически годится как триггер **стопа**.

## 3. Повторный вызов (тумблер стоп)

### 3.1. Дебаунса нет

`VoiceInteractionSessionConnection.showLocked()` — при уже установленном биндинге безусловно вызывает `mSession.show(...)`:

```java
public boolean showLocked(Bundle args, int flags, String attributionTag, int disabledContext, …) {
    if (mBound) {
        if (!mFullyBound) { mFullyBound = mContext.bindServiceAsUser(mBindIntent, mFullConnection, …); }
        mShown = true;                    // ← нет проверки "уже показана"
        mShowArgs = args; mShowFlags = flags;
        …
        if (mSession != null) {
            mSession.show(mShowArgs, mShowFlags, showCallback);   // ← каждый раз
        }
        …
        mCallback.onSessionShown(this);
        return true;
    }
    …
}
```
https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/voiceinteraction/java/com/android/server/voiceinteraction/VoiceInteractionSessionConnection.java

Более того, повторный вызов — это **документированный контракт**, а не побочный эффект. Javadoc `onShow()`:

> "Called when the session UI is going to be shown. This is called after `onCreateContentView` (if the session's content UI needed to be created) and immediately prior to the window being shown. **This may be called while the window is already shown, if a show request has come in while it is shown, to allow you to update the UI to match the new show arguments.**"
> https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/service/voice/VoiceInteractionSession.java

`VoiceInteractionSession.doShow()` тоже вызывает `onShow()` безусловно; единственная защита — от **реентерабельности в том же потоке**:

```java
void doShow(Bundle args, int flags, IVoiceInteractionSessionShowCallback showCallback) {
    if (mInShowWindow) { Log.w(TAG, "Re-entrance in to showWindow"); return; }
    try {
        mInShowWindow = true;
        onPrepareShow(args, flags);
        if (!mWindowVisible) { ensureWindowAdded(); }
        onShow(args, flags);      // ← вызывается и когда окно уже видимо
        …
```
https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/service/voice/VoiceInteractionSession.java

В SystemUI `AssistManager` есть приватный `isVoiceSessionRunning()`, но в пути `startAssist()` он **не используется** — то есть SystemUI не глушит повторный вызов (см. `AssistManager.java`, ссылка выше).

### 3.2. Один и тот же объект сессии

`VoiceInteractionManagerServiceImpl.showSessionLocked()` создаёт `mActiveSession` **однократно** и переиспользует:

```java
if (mActiveSession == null) {
    mActiveSession = new VoiceInteractionSessionConnection(…);
}
```
https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/voiceinteraction/java/com/android/server/voiceinteraction/VoiceInteractionManagerServiceImpl.java

`hideLocked()` сбрасывает `mShown` и отвязывает только «полный» биндинг (`mFullConnection`), не разрушая базовое соединение:

```java
public boolean hideLocked() {
    if (mBound) {
        if (mShown) { mShown = false; … mSession.hide(); … mCallback.onSessionHidden(this); }
        if (mFullyBound) { mContext.unbindService(mFullConnection); mFullyBound = false; }
    …
```

⇒ **Второе длинное нажатие приходит в тот же `VoiceInteractionSession` новым `onShow()`.** Состояние тумблера можно держать в сессии, но надёжнее — в самом foreground-сервисе записи (сессия может быть пересоздана, если процесс убьют).

### 3.3. Что приходит в `onShow`

`args` содержат (документировано в javadoc `onShow`):
`invocation_type` (`= 6` для long-press power), `invocation_phone_state`, `KEY_SHOW_SESSION_ID`, `invocation_time_ms`, `Intent.EXTRA_TIME`, `Intent.EXTRA_ASSIST_INPUT_DEVICE_ID`.
`showFlags` от SystemUI = `SHOW_SOURCE_ASSIST_GESTURE | SHOW_WITH_ASSIST | SHOW_WITH_SCREENSHOT`
(`showSessionForActiveService` добавляет два последних флага — см. `VoiceInteractionManagerService.java`).

Отличить кнопку питания от long-press home можно по `args.getInt("invocation_type")`:
`5 = HOME_BUTTON_LONG_PRESS`, `6 = POWER_BUTTON_LONG_PRESS`, `8 = NAV_HANDLE_LONG_PRESS`
https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/com/android/internal/app/AssistUtils.java

Рекомендация: **не** запрашивать assist-данные (`setDisabledShowContext(SHOW_WITH_ASSIST | SHOW_WITH_SCREENSHOT)`) — иначе система будет собирать скриншот и структуру экрана и показывать «assist disclosure», что нам не нужно и замедляет отклик.

### 3.4. Известная точка подавления

`showSessionForActiveService` вернёт `false`, если сервис временно выключен:

```java
if (mTemporarilyDisabled) {
    Slog.i(TAG, "showSessionForActiveService(): ignored while temporarily disabled");
    return false;
}
```
(`VoiceInteractionManagerService.java`, устанавливается через `setDisabled()` под пермишном `ACCESS_VOICE_INTERACTION_SERVICE` — системный путь, не наш кейс.)

---

## 4. Экран блокировки и микрофон

### 4.1. ⚠️ Главное ограничение: погашенный экран

`PhoneWindowManager`, обработчик long-press питания:

```java
@Override
void onLongPress(long eventTime) {
    if (mSingleKeyGestureDetector.beganFromNonInteractive()
            && !mSupportLongPressPowerWhenNonInteractive) {
        Slog.v(TAG, "Not support long press power when device is not interactive.");
        return;
    }
    powerLongPress(eventTime);
}
```

`beganFromNonInteractive` фиксируется в момент **нажатия** (ACTION_DOWN):

```java
void interceptKey(KeyEvent event, boolean interactive, boolean defaultDisplayOn) {
    if (event.getAction() == KeyEvent.ACTION_DOWN) {
        // Store the non interactive state and display on state when first down.
        if (mDownKeyCode == KEYCODE_UNKNOWN || mDownKeyCode != event.getKeyCode()) {
            mBeganFromNonInteractive = !interactive;
            …
```
https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/policy/SingleKeyGestureDetector.java

А дефолт конфига:

```xml
<!-- If this is true, long press on power button will be available from the non-interactive state -->
<bool name="config_supportLongPressPowerWhenNonInteractive">false</bool>
```

⇒ **На стоковых дефолтах AOSP: экран погашен → длинное нажатие питания разбудит экран, но ассистента не позовёт.** Заблокированный, но включённый экран — позовёт (см. ниже). Значение этого флага у OnePlus — **обязательный пункт проверки на устройстве** (`adb shell dumpsys window policy` / overlay-ресурсы).

### 4.2. Заблокированный экран (keyguard, экран включён)

`launchAssistAction()` — путь для `LONG_PRESS_POWER_ASSISTANT` — **не содержит проверки keyguard**:

```java
private void launchAssistAction(String hint, int deviceId, long eventTime, int invocationType) {
    sendCloseSystemWindows(SYSTEM_DIALOG_REASON_ASSIST);
    if (!isUserSetupComplete()) { return; }   // единственная блокировка
    … searchManager.launchAssist(args);
}
```

Сравните с `LONG_PRESS_POWER_GO_TO_VOICE_ASSIST` (значение 4), где keyguard проверяется явно и требуется разблокировка:

```java
/** Launches ACTION_VOICE_ASSIST_RETAIL if in retail mode, or ACTION_VOICE_ASSIST otherwise
 *  Does nothing on keyguard except for watches. */
private void launchVoiceAssist(boolean allowDuringSetup) {
    final boolean keyguardActive = mKeyguardDelegate != null && mKeyguardDelegate.isShowing();
    if (!keyguardActive) { startActivityAsUser(new Intent(Intent.ACTION_VOICE_ASSIST), …); }
    else { mKeyguardDelegate.dismissKeyguardToLaunch(new Intent(Intent.ACTION_VOICE_ASSIST)); }
}
```
(оба фрагмента — `PhoneWindowManager.java`, ссылка выше)

⇒ **Значение 5 (ASSISTANT) — то, что нам нужно; значение 4 (VOICE_ASSIST) потребовало бы разблокировки.** В SystemUI единственная блокировка в `startAssist()` — режим lock-task (`LOCK_TASK_MODE_LOCKED`), не keyguard.

**Но:** когда локскрин *появляется*, система гасит сессию.

```java
// CentralSurfacesImpl
@Override public void showKeyguard() {
    …
    mAssistManagerLazy.get().onLockscreenShown();
}
```
https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SystemUI/src/com/android/systemui/statusbar/phone/CentralSurfacesImpl.java

и дефолтная реализация в сессии:

```java
/** Called when the lockscreen was shown. */
public void onLockscreenShown() { hide(); }
```
(`VoiceInteractionSession.java`)

⇒ Если состояние записи держать в сессии — оно умрёт при блокировке экрана. Держим в foreground-сервисе; `onLockscreenShown()` при желании переопределяем пустым телом.

Есть также отдельный, **другой** механизм — `onLaunchVoiceAssistFromKeyguard()` (аффорданс на локскрине, а не кнопка питания):

> "Called when a user has activated an affordance to launch voice assist from the Keyguard. This method will only be called if the VoiceInteractionService has set `android.R.attr#supportsLaunchVoiceAssistFromKeyguard` and the Keyguard is showing. **A valid implementation must start a new activity that should use `WindowManager.LayoutParams#FLAG_SHOW_WHEN_LOCKED` to display on top of the lock screen.**"
> https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/service/voice/VoiceInteractionService.java

Ставить `supportsLaunchVoiceAssistFromKeyguard="true"` стоит, но полагаться на этот колбэк как на основной триггер — нет.

### 4.3. Микрофон: что требует Android 14/15

**Тип foreground-сервиса.** Начиная с Android 14 (API 34) тип обязателен:

> "Beginning with Android 14 (API level 34), you must declare an appropriate service type for each foreground service."
> Тип `microphone`: манифест `android:foregroundServiceType="microphone"`, константа `FOREGROUND_SERVICE_TYPE_MICROPHONE`, permission `FOREGROUND_SERVICE_MICROPHONE`, runtime-permission `RECORD_AUDIO`.
> "The `RECORD_AUDIO` runtime permission is subject to while-in-use restrictions. For this reason: you **cannot create a `microphone` foreground service while your app is in the background**; you **cannot launch a `microphone` foreground service from a `BOOT_COMPLETED` receiver**, with a few exceptions."
> https://developer.android.com/develop/background-work/services/fgs/service-types

Ограничение на `BOOT_COMPLETED` для микрофона действует с Android 14:
> "Apps that target Android 14 (API level 34) or higher are not allowed to launch a microphone foreground service from a `BOOT_COMPLETED` broadcast receiver."
> https://developer.android.com/about/versions/15/changes/foreground-service-types

**Правило while-in-use и исключения.**

> "On Android 14 (API level 34) or higher… if your app is in the background and tries to create a foreground service requiring while-in-use permissions, the system throws a `SecurityException`…
> You must call `Context.startForegroundService()` or `Context.bindService()` while your app has a visible activity, unless the service falls into one of the defined exemptions."
>
> Список исключений:
> 1. A system component starts the service
> 2. The service starts by interacting with app widgets
> 3. The service starts by interacting with a notification
> 4. The service starts as a `PendingIntent` sent from a different, visible app
> 5. The service starts by an app that is a device policy controller running in device owner mode
> 6. **The service starts by an app which provides the `VoiceInteractionService`**
> 7. The service starts by an app that has the `START_ACTIVITIES_FROM_BACKGROUND` privileged permission
>
> https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start#wiu-restrictions

**Исключение №6 — ровно наш случай.** Это официальная, документированная опора всего плана.

Диагностика при провале (полезно для probe):
> `Foreground service started from background can not have location/camera/microphone access: service SERVICE_NAME`
> (там же)

**Механизм в коде (вывод из исходников, не документация).** Система биндит session-сервис ассистента с флагами, которые дают приложению право стартовать FGS из фона:

```java
mFullyBound = mContext.bindServiceAsUser(mBindIntent, mFullConnection,
        Context.BIND_AUTO_CREATE | Context.BIND_TREAT_LIKE_ACTIVITY
                | Context.BIND_SCHEDULE_LIKE_TOP_APP
                | Context.BIND_TREAT_LIKE_VISIBLE_FOREGROUND_SERVICE
                | Context.BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS,
        new UserHandle(mUser));
```
(`VoiceInteractionSessionConnection.showLocked`, ссылка выше)

а `ActiveServices.shouldAllowFgsWhileInUsePermissionLocked()` выдаёт `REASON_ACTIVITY_STARTER`, когда для процесса разрешены background FGS starts:

```java
// "Allow FGS while-in-use if the WindowManager allows background activity start… The binding flag
//  BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS is also allowed by the check here."
if (pr.getWindowProcessController().areBackgroundFgsStartsAllowed()) { return REASON_ACTIVITY_STARTER; }
```
https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/am/ActiveServices.java

⇒ **Практический вывод: `startForegroundService()` надо звать синхронно внутри `onShow()`,** пока сессия привязана системой. Отложенный старт (через `Handler.postDelayed`, корутину с задержкой, WorkManager) может выпасть из окна привилегии.

**Почему без FGS нельзя вообще.** Ещё с Android 9:

> "Android 9 limits the ability for background apps to access user input and sensor data… Your app cannot access the microphone or camera… If your app needs to detect sensor events on devices running Android 9, use a foreground service."
> https://developer.android.com/about/versions/pie/android-9.0-changes-all

То есть запись из обычного сервиса или корутины без FGS даст тишину. Единственный легальный путь — `microphone` foreground service.

**«Просто попросить фоновый микрофон» нельзя.** У `RECORD_AUDIO` есть парная background-пермишка, но она закрыта:

```xml
<permission android:name="android.permission.RECORD_AUDIO"
    android:backgroundPermission="android.permission.RECORD_BACKGROUND_AUDIO"
    android:protectionLevel="dangerous|instant" />

<!-- @SystemApi @TestApi Allows an application to record audio while in the background.
     This permission is not intended to be held by apps.
     <p>Protection level: internal  @hide -->
<permission android:name="android.permission.RECORD_BACKGROUND_AUDIO" … />
```
https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/res/AndroidManifest.xml

Аналога `ACCESS_BACKGROUND_LOCATION` для микрофона у сторонних приложений нет. `FOREGROUND_SERVICE_MICROPHONE` — protection level `normal`, объявляется в манифесте и не требует диалога (там же).

**Заблокированный экран сам по себе микрофон не блокирует** — в документации нет ограничения «нельзя писать звук при keyguard». Блокируют: (а) отсутствие FGS, (б) while-in-use правила, (в) глобальный микрофонный тумблер Android 12+.

### 4.4. Бонус роли: подписка на состояние блокировки

Роль ASSISTANT даёт `android.permission.SUBSCRIBE_TO_KEYGUARD_LOCKED_STATE` (minSdkVersion 33, см. §1.6). Это открывает публичный API:

```java
@RequiresPermission(Manifest.permission.SUBSCRIBE_TO_KEYGUARD_LOCKED_STATE)
public void addKeyguardLockedStateListener(Executor executor, KeyguardLockedStateListener listener)
```
https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/KeyguardManager.java

Плюс всегда доступные `KeyguardManager.isKeyguardLocked()` / `isDeviceLocked()`. Полезно, чтобы решать: показывать ли UI после старта записи или молча уйти в фон.

### 4.5. Что должен дать пользователь

| Что | Как | Обязательно? |
|---|---|---|
| Роль «Цифровой ассистент» | Настройки → Приложения → Приложения по умолчанию → Цифровой ассистент | да, вручную (роль не `requestable`) |
| «Удерживать кнопку питания» = Ассистент | Настройки → Система → Жесты (в OxygenOS путь иной) | да |
| `RECORD_AUDIO` | runtime-диалог | да (роль микрофон не даёт, предгрант только системным пакетам) |
| `POST_NOTIFICATIONS` | runtime-диалог (Android 13+) | да — иначе уведомление FGS не видно |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MICROPHONE` | манифест, normal-permissions | да |
| Отключить оптимизацию батареи | см. §5 | практически да — но **ради выживания сервиса, а не ради микрофона** (§5.5) |
| Микрофонный тумблер (Android 12+) | Быстрые настройки → «Микрофон» | должен быть включён — глобальный kill-switch |

---

## 5. OxygenOS: подводные камни

### 5.1. Официальной документации по фоновым ограничениям НЕТ

Проверено прямыми запросами:
* `open.oppomobile.com/new/wiki` — индекс девелоперской документации OPPO отрендерен целиком; документов про автозапуск / keep-alive / фоновый whitelist **нет**.
* `service.oneplus.com/*/user-manual` — только «quick guides»; мануала настроек OnePlus 13 нет, прямые PDF-пробы 404.
* `support.oppo.com` (гайд ColorOS 15) — **AccessDenied**, не опубликован.
* `oneplus.com/global/oxygenos15` — про батарею/фон **ни слова**, только маркетинговое «Lean and Clean» (−20 % footprint).
* **[ОФИЦИАЛЬНО]** Release notes OxygenOS **15.0.0.862** для OnePlus 13 (`CPH2655_15.0.0.862(EX01)`, сент. 2025): анимации локскрина, Private Safe, screen time, ИК-пульт, патч безопасности. **Про фоновые лимиты — ничего.**
  https://community.oneplus.com/thread/1958157704140685317

⇒ Ниже: **[ОФИЦИАЛЬНО]** = дословно из мануала OxygenOS 14; остальное — вторичное/анекдотическое.

### 5.2. Официальные пути (мануал OxygenOS 14, дословно)

| Функция | Путь | Что делает |
|---|---|---|
| **App battery management** ← ключевое | `Settings > Battery > More settings > App battery management` | «You can turn off 'Allow foreground activity' to save battery power. However, the app may not run properly and app notifications may be delayed.» Есть также **«Allow background activity»**. ⚠️ Фраза мануала про фоновый тумблер переведена криво — доверять UI, а не мануалу. |
| **Sleep standby optimization** | `Settings > Battery > More settings` | «switch your phone to a low-power state while you sleep, reducing the frequency of push notifications» — **выключить** |
| **Power saving mode** | `Settings > Battery > Power saving mode` | + подсписок «Default optimizations» |
| **Super power saving mode** | там же | «limits the number of apps that can be used and **strictly controls background activity**» — убьёт запись гарантированно |
| **Auto launch** | `Settings > Apps > Auto launch` | «Some apps will start automatically in the background… You can block these apps from auto-launching» |

Мануал: https://service.oneplus.com/content/dam/support/user-manuals/common/OxygenOS_14.0_User_Manual.pdf

### 5.3. ⚠️ В OxygenOS 15 ожидать НАЗВАНИЯ ИЗ ColorOS

Путь, который цитируют все гайды (`Battery > Battery optimization > ⋮ > Advanced optimization`), восходит к эпохе **OnePlus 6**. **Ни один источник не подтверждает, что это меню ещё существует в OxygenOS 15.**

> «Since OxygenOS 12 the shell shares a codebase with Oppo, so on OxygenOS 15 and 16 the option names are close to ColorOS — if the paths differ, check the Oppo section.»
> **[ВТОРИЧНО]** https://express.ms/en/faq/background-work-fix/

**[АНЕКДОТИЧЕСКИ, официальный форум OPPO]** Гайд «Fix Battery Drain After ColorOS 15 / OxygenOS 15 Update» (ноябрь 2025) даёт ColorOS-образное дерево: `Settings → Battery → More Settings → App Battery Management` с пер-приложенческими «Smart optimization» / «Restrict background activity» —
https://community.oppo.com/thread/1996943813003706370

**[АНЕКДОТИЧЕСКИ]** Пункт `Settings > Apps > Auto launch` в OxygenOS 15, возможно, **удалён** — «i tried finding the auto launch setting in my phone it was removed» (OnePlus 12, OS 15), вместо него якобы Trinity Engine решает сам («It says 139 blocked auto launches on my phone») — https://community.oneplus.com/thread/1741627536758013957. Ответа сотрудников нет, express.ms утверждает обратное. **Противоречие не разрешено — смотреть на устройстве.**

### 5.4. DontKillMyApp

**[ВТОРИЧНО, специализированный]** https://dontkillmyapp.com/oneplus — **OnePlus: 5 💩, 3-е место снизу** (OPPO: 4 💩, 8-е).

* «one of the most severe background limits on the market to date, dwarfing even those performed by Xiaomi or Huawei»
* «…even got reset with firmware updates» — **настройки исключений слетают при обновлении прошивки**
* «Deep optimization / Adaptive Battery — **This is the main app killer.** If you need any apps to run in the background, disable it»
* «Sleep standby optimization … will prevent push notifications from being delivered»
* Замок в Recents нужен не только против выгрузки: «OnePlus phones started reverting this [battery optimization] setting randomly for random apps»
* App Auto-Launch «essentially prevents apps from working in the background. Please disable it.»
* 🔴 **«No known solution on the developer end.»** (страница OPPO: «No known solution on the dev end.»)

⚠️ Оговорка: обе страницы **не упоминают** OxygenOS 15, ColorOS 15, OnePlus 13 или Android 15 — гайд написан против OnePlus 3/5/6. Это отправная точка, а не актуальная спецификация.

### 5.5. Чеклист, чтобы mic-FGS выжил

1. **Замок в Recents** (долгое нажатие на карточку → 🔒) — по DontKillMyApp самый результативный одиночный шаг
2. Battery optimization → **Don't optimize / Unrestricted**
3. **Advanced optimization** → выключить **Deep optimization / Adaptive Battery** и **Sleep standby optimization** *(либо ColorOS-эквиваленты, если меню переехало)*
4. **App battery management** → «Allow background activity» ON, «Allow foreground activity» ON, автозапуск ON, если есть
5. `Settings > Advanced > Recent apps management` → **Normal clear** (чтобы свайп карточки не делал force-kill)
6. Никогда не включать **Super power saving mode**

⚠️ **Важное различение, на котором легко обмануться.** Снятие battery optimization действительно есть в списке исключений из запрета **запускать** FGS из фона (§4.3, пункт 13) — но в списке `ActiveServices.shouldAllowFgsWhileInUsePermissionLocked`, который решает, **дадут ли микрофон**, его **нет**. Там только: proc state ≤ TOP · видимый UID · привилегии background-activity-start · системные UID · `areBackgroundFgsStartsAllowed()` · temp-allowlist · инструментация · `START_ACTIVITIES_FROM_BACKGROUND` · хардкод-список (AttentionService, SystemCaptionsService) · device owner.

То же касается `SYSTEM_ALERT_WINDOW` (§6.6). ⇒ **«Приложение исключено из оптимизации батареи» НЕ даёт фоновый микрофон.** Его дают только биндинг ассистента (путь A) или TOP-активити (путь B). Снятие battery optimization нужно ради выживания сервиса, а не ради доступа к микрофону.

**[ВТОРИЧНО]** Страница OPPO на DontKillMyApp перечисляет четыре шага и подчёркивает, что нужны **все**, включая «Give the service a persistent notification to remain in the foreground» — то есть уведомление FGS само по себе недостаточно.

### 5.6. Риски по компонентам

| Компонент | Риск | Смягчение |
|---|---|---|
| Триггер (роль ассистента) | **средний** — см. §2.7.1, механизм `VoiceInteractionService` может быть заблокирован | использовать `ACTION_ASSIST`-активити (§6) |
| `RecordingService` (FGS `microphone`) | **высокий** — длинная запись с погашенным экраном это ровно то, что OxygenOS убивает | чеклист §5.5 |
| Очередь отправки заметок (сеть) | **высокий** — Sleep standby optimization выключает сеть | выключить опцию; `WorkManager` с сетевым constraint |
| Сохранённые настройки | **средний** — слетают при обновлении прошивки | самопроверка при старте (`isIgnoringBatteryOptimizations`, `isRoleHeld`) + напоминание пользователю |

### 5.7. Диагностика на устройстве

Убийца OxygenOS исторически логируется под тегом **BgDetect**:

```bash
adb logcat | grep -iE "BgDetect|OppoBgApp|ColorOs|athena|ActivityManager.*died|SecurityException.*FGS"
```

Это назовёт подсистему, которая нас прибила, быстрее, чем любой поиск по путям настроек.

### 5.8. Чего проверить не удалось

* Ломает ли OxygenOS 15 сторонний `VoiceInteractionService` — есть **одно** свидетельство (§2.7.1), для OxygenOS 16.
* Ограничения на **микрофон с локскрина** сверх AOSP — свидетельств нет (единственное смежное анекдотическое сообщение — что штатный диктофон OPPO сам останавливается при выключении экрана; это поведение приложения, не платформы).
* Ограничения на FGS типа `microphone` сверх AOSP — свидетельств нет.
* Значение `config_supportLongPressPowerWhenNonInteractive` на OnePlus 13 — **ключевой неизвестный** (§4.1, P4).
* Актуальная версия прошивки: по состоянию на август 2026 OnePlus 13 уже переведён на **OxygenOS 16**; последняя подтверждённая сборка OxygenOS 15 — `15.0.0.862` (сент. 2025). Уточнить, что реально стоит на аппарате.
* **[ВТОРИЧНО, без даты и версии, не подтверждено]** MetaCtrl утверждает, что OnePlus прибивает фоновые сервисы, работающие дольше ~5 секунд (https://metactrl.com/docs/oneplus/). Если это правда хоть в каком-то виде — это прямая угроза непрерывной записи звука. Проверить эмпирически (P9).
* **[АНЕКДОТИЧЕСКИ]** В OxygenOS 16 двойное нажатие питания, по сообщениям, убрано — то есть и этот резервный жест может исчезнуть.

## 6. Рекомендуемая архитектура (следствие из §1–§5)

⚠️ Из-за находки §2.7.1 архитектура должна поддерживать **два пути вызова** и переключаться между ними одной строкой манифеста. Оба ведут в один и тот же код тумблера.

```
                    long-press power
                            │
        ┌───────────────────┴────────────────────┐
   путь A (AOSP-каноничный)              путь B (обходной, OnePlus)
   VoiceInteractionService                ACTION_ASSIST Activity
   → …SessionService → onShow()           → AssistActivity.onCreate/onNewIntent
        └───────────────────┬────────────────────┘
                            ▼
              RecordingService (FGS type=microphone)
              ← здесь ЖИВЁТ состояние тумблера
```

### 6.1. Путь A — `VoiceInteractionService` (каноничный)

```kotlin
class AssistService : VoiceInteractionService() {
    override fun onReady() {
        // не тянуть скриншот/структуру экрана и не показывать assist-disclosure
        setDisabledShowContext(
            VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT)
    }
}

class AssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?) = ToggleSession(this)
}

class ToggleSession(ctx: Context) : VoiceInteractionSession(ctx) {
    override fun onCreate() { super.onCreate(); setUiEnabled(false) }   // headless
    override fun onShow(args: Bundle?, showFlags: Int) {
        RecordingService.toggle(context)   // СНАЧАЛА старт FGS…
        hide()                             // …и только ПОТОМ отпускаем сессию
    }
    override fun onLockscreenShown() { /* НЕ вызывать super — super делает hide() */ }
}
```

### 6.2. Путь B — `ACTION_ASSIST`-активити (обход блокировки OnePlus)

```xml
<activity android:name=".AssistActivity"
    android:exported="true"
    android:launchMode="singleTop"
    android:theme="@android:style/Theme.NoDisplay"
    android:excludeFromRecents="true"
    android:taskAffinity=""
    android:showWhenLocked="true"
    android:turnScreenOn="true">
    <intent-filter>
        <action android:name="android.intent.action.ASSIST" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>
</activity>
```

```kotlin
class AssistActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        RecordingService.toggle(this)   // мы в TOP → while-in-use микрофон разрешён
        finish()
    }
    override fun onNewIntent(i: Intent) { super.onNewIntent(i); RecordingService.toggle(this) }
}
```

🔴 **`android:showWhenLocked="true"` здесь несущий, а не косметический.** Javadoc `Activity.setShowWhenLocked`:

> "Specifies whether an Activity should be shown on top of the lock screen whenever the lockscreen is up and the activity is resumed. **Normally an activity will be transitioned to the stopped state if it is started while the lockscreen is up**, but with this flag set the activity will remain in the resumed state visible on-top of the lock screen. This value can be set as a manifest attribute using `android.R.attr#showWhenLocked`."
> https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/Activity.java

Без него активити на локскрине уйдёт в **stopped**, никогда не станет TOP — и `startForegroundService` для микрофона упадёт с `SecurityException` (§4.3). `launchMode="singleTop"` + `onNewIntent` обеспечивают тумблер при повторном вызове (второй `ACTION_ASSIST` не создаёт новый экземпляр).

⚠️ Путь B **не** получает `SHOW_SOURCE_*`/`invocation_type` так же удобно — extras приходят в Intent (`Intent.EXTRA_ASSIST_*`, `AssistUtils.INVOCATION_TYPE_KEY`), но `AssistManager.startAssistActivity()` их прокидывает (`intent.putExtras(args)`, §2.1).

### 6.3. Если делать только один вариант — начинать с B

Единственное **эмпирическое** свидетельство, снятое на OnePlus 13, говорит, что работает путь B (§2.7.1). Путь A — это то, что говорит документация AOSP, но документация не знает про оверлеи OPPO. Поэтому при дефиците времени: **сначала собрать B, убедиться, что кнопка вообще доходит до приложения, и только потом пробовать A** (он даёт более чистый UX — без активити и без вспышки окна).

### 6.4. Как переключаться

Держать **оба** компонента в манифесте, а выбирать — через `android:enabled` + `PackageManager.setComponentEnabledSetting`, либо просто двумя build-вариантами для probe. Ключ: **держатель роли — это пакет**, а система сама решает, есть ли у него квалифицирующий `VoiceInteractionService` (§1.4). Если сервис выключен, `RoleObserver` запишет в `Secure.ASSISTANT` активити, а `VOICE_INTERACTION_SERVICE` очистит — и OnePlus пойдёт по пути B.

### 6.5. Общее для обоих путей

* **Состояние тумблера — в `RecordingService`**, а не в сессии/активити: сессия гасится при появлении локскрина, активити мгновенно `finish()`, процесс могут убить (§3.2, §4.2).
* **Порядок важен:** сначала `startForegroundService`, потом `hide()`/`finish()`. `hideLocked()` отвязывает «полный» биндинг и снимает привилегию, на которой держится право стартовать mic-FGS из фона (§4.3).
* **`recognitionService` в XML обязателен всегда** — иначе роль слетает при каждом обновлении APK (§1.4a). Формально `VoiceInteractionServiceInfo` только читает строку атрибута, но гайд AOSP требует реальный `RecognitionService` — положить заглушку.
* **Один процесс.** Javadoc рекомендует `android:process=":session"`, но нам это вредно: проще держать всё в одном процессе, чтобы состояние было общим в памяти. Проверка while-in-use в `ActiveServices` идёт **по UID**, а не по процессу, поэтому привилегия сохраняется.
* **Почему привилегия вообще есть.** Систему биндит сам `VoiceInteractionService` с флагами, прямо предназначенными для передачи while-in-use возможностей:
  ```java
  mBound = mContext.bindServiceAsUser(intent, mConnection,
          Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE
          | Context.BIND_INCLUDE_CAPABILITIES
          | Context.BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS, new UserHandle(mUser));
  ```
  https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/voiceinteraction/java/com/android/server/voiceinteraction/VoiceInteractionManagerServiceImpl.java

  `BIND_INCLUDE_CAPABILITIES` документирован как «allow the bound app to get the same capabilities… so the other app can have while-in-use access such as location, camera, **microphone** from background» (`Context.java`). На пути B этой привилегии нет — её заменяет попадание активити в TOP, отсюда и требование `showWhenLocked`.

### 6.6. ⚠️ Android 15: `SYSTEM_ALERT_WINDOW` больше не спасает

Роль ассистента автоматически даёт app-op `SYSTEM_ALERT_WINDOW` (§1.6), и может возникнуть соблазн опереться на него как на исключение из запрета фонового старта FGS. В Android 15 этого мало:

> "If your app targets Android 15 or higher, it must have the `SYSTEM_ALERT_WINDOW` permission **and** the app must currently have a visible overlay window."
> https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start

И главное — `SYSTEM_ALERT_WINDOW` в списке `ActiveServices.shouldAllowFgsWhileInUsePermissionLocked` **отсутствует вовсе**: он освобождает от запрета *запустить* FGS, но **не даёт while-in-use микрофон** (§4.3). Опираться нужно на биндинг ассистента (путь A) или на TOP-активити (путь B).

## Verdict: реализуемо ли задуманное и с какими оговорками

### Что подтверждено документами и исходниками — «зелёное»

1. **Приложение может стать ассистентом.** Требования формальные и выполнимые: `VoiceInteractionService` + `VoiceInteractionSessionService` + XML с тремя атрибутами. Никакого «только системные приложения» в квалификации нет (§1.1–1.2).
2. **Long-press power → держатель роли ассистента — штатный путь AOSP.** Дефолт `config_longPressOnPowerBehavior = 5`, маршрутизация через `Settings.Secure.ASSISTANT`. Хардкода Google в платформе нет (§2.1–2.2).
3. **Тумблер (второе нажатие) архитектурно поддержан.** Ни дебаунса, ни «уже показано» в коде нет; `onShow()` приходит каждый раз (§3.1).
4. **Микрофон с фона легален именно для нас.** Официальное исключение №6 из while-in-use ограничений — «The service starts by an app which provides the VoiceInteractionService» (§4.3). Это не хак, а документированный сценарий.
5. **Заблокированный экран не мешает.** Путь `LONG_PRESS_POWER_ASSISTANT` (5), в отличие от `GO_TO_VOICE_ASSIST` (4), не требует разблокировки (§4.2).
6. **Повторный вызов — документированный контракт**, а не случайность: javadoc `onShow()` прямо описывает повторный вызов при уже показанной сессии (§3.1).
7. **Схема работает в поле.** ChatGPT и Perplexity уже используют её как сторонние ассистенты, вызываемые long-press питания (вторичные источники, §2.5a).

### Оговорки — «жёлтое», это риски реализации

1. **Роль не запрашивается программно.** `requestable="false"` → `createRequestRoleIntent()` не сработает. Onboarding приложения — это инструкция пользователю + deep-link в настройки. Одноразово, но неустранимо (§1.3).
2. **`RECORD_AUDIO` придётся просить как обычно.** Роль ассистента микрофон не предгрантит — предгрант только для системных пакетов (§1.6).
3. **Стартовать FGS нужно синхронно в `onShow()`.** Любая задержка рискует выпасть из окна привилегии (§4.3).
4. **Нужно погасить сбор assist-контекста**, иначе на каждое нажатие будет disclosure-анимация как у стороннего ассистента (§6).
4a. **`recognitionService` обязателен всегда.** Забудешь — роль слетает при каждом обновлении APK, включая `installDebug` в цикле разработки (§1.4a).
5. **Состояние тумблера — только в foreground-сервисе.** Сессия гасится при появлении локскрина (`onLockscreenShown() { hide(); }`) (§4.2).

### Красное — то, что может сломать замысел целиком

1. 🔴 **OxygenOS/ColorOS, судя по всему, блокирует `VoiceInteractionService` на кнопке питания** (`IsQuickLaunchSupport=false`, §2.7.1). Свидетельство одно, анекдотическое, для OxygenOS 16 — но получено **на OnePlus 13 при решении ровно нашей задачи**. Митигация известна и дешёвая: путь B (`ACTION_ASSIST`-активити + `showWhenLocked`, §6.2). Поэтому это риск архитектуры, а не риск замысла — но проверять обязательно **первым** (P1a/P1b).
2. **Погашенный экран.** `config_supportLongPressPowerWhenNonInteractive = false` по дефолту AOSP: если экран был выключен в момент нажатия, ассистент **не вызовется** (§4.1). Для «достал телефон из кармана и зажал кнопку» это критично. Значение флага у OnePlus неизвестно — **P4**. Если флаг `false`, у нас есть хороший запасной вариант, который как раз работает с погашенного экрана — **жесты при выключенном экране** (см. ниже).
3. **Перехват вызова OEM-лончером.** `AssistManager.shouldOverrideAssist()` позволяет лончеру забрать `INVOCATION_TYPE_POWER_BUTTON_LONG_PRESS` до маршрутизации по роли (§2.5). Документации нет — **P3**.
4. **Агрессивный менеджмент OxygenOS** (§5). DontKillMyApp: OnePlus 5/5 «💩», «one of the most severe background limits on the market», настройки исключений **слетают при обновлениях прошивки**, и прямым текстом — «No known solution on the developer end». Под ударом `RecordingService` и очередь отправки, а не сам триггер: платформу ре-биндит ассистента даже после force-stop (§1.4, P7).

### Итог

**Замысел реализуем на уровне платформы Android 15** — все нужные механизмы официально документированы и не требуют root, системных прав или недокументированных API. Схема уже используется в поле сторонними приложениями (§2.5a).

**Но на OnePlus 13 каноничный путь может не сработать, и это надо проверить до написания кода.** Хорошая новость: обходной путь (`ACTION_ASSIST`-активити) документирован в AOSP, разрешён CDD и подтверждён на нашей модели телефона; переключение между путями стоит одной строки в манифесте (§6.4).

Реальные неизвестные, которые не закрываются чтением документации:
(а) блокируется ли `VoiceInteractionService` на нашей прошивке;
(б) не перехватывает ли лончер вызов;
(в) работает ли long-press power с погашенного экрана;
(г) выживаемость foreground-сервиса под OxygenOS.

Официальных документов OnePlus по (а)–(в) **не существует** — мануала OxygenOS 15 нет вовсе, новейший опубликованный это OxygenOS 14 (§2.7.0). Проверять только на железе.

Рекомендация: **не писать полный пайплайн до probe.** Собрать минимальный «скелет» — оба пути вызова + `RecordingService`, который просто пишет 5 секунд в файл и логирует, — и прогнать P1–P11.

### Запасные триггеры

⚠️ **Исправление к раннему предположению:** long-press HOME и nav-handle — плохие запасные варианты, потому что **на локскрине они недоступны**. Гораздо лучше подходят три механизма OxygenOS/AOSP, которые работают именно с заблокированного (а два из них — и с погашенного) экрана.

| Триггер | Сторонние приложения? | Экран погашен? | Заблокирован? | Источник |
|---|---|---|---|---|
| **Жесты при выключенном экране** (нарисовать ∧ / M / W) | **да**, пользователь выбирает приложение | **да** (по определению) | да | **[ВТОРИЧНО]** |
| **Quick Launch** (долгое нажатие на сканер отпечатка) | **да**, есть вкладка «Apps» | нет | **да, явно** | **[ОФИЦИАЛЬНО]** мануал OOS14 |
| **Ярлык спец-возможностей** (обе клавиши громкости 3 с) | **да**, любой `AccessibilityService` | да | да, если включён тумблер на локскрине | **[ОФИЦИАЛЬНО, AOSP]** |
| Long-press HOME (3 кнопки) | да, через роль ассистента | нет | **нет** | AOSP |
| Long-press nav handle (жесты) | да, через роль | нет | **нет** | AOSP |

**Жесты при выключенном экране — лучший кандидат.** **[ВТОРИЧНО]**
> «In the Settings app, navigate to **Accessibility & convenience**. Tap on **Gestures & motions**. Under Gestures, select **Screen-off gestures**.» … «By default, the inverted V opens Calculator, M opens Gemini, and W opens Recorder. But you're free to change which apps these three gestures open.»
> https://www.bgr.com/2233618/oneplus-features-hidden-inside-oxygenos/
> подтверждение: https://devicology.com/guide/oneplus-gesture-guide-oxygenos/9608/ («Draw 'S,' 'M,' or 'W' launch user-selected apps»)

Уточнение: жесты **O** и **V** зашиты (камера / фонарик); настраиваемы только **∧ (перевёрнутая V), M и W**.

В мануале OxygenOS 14 этого раздела нет — источник только вторичный, и **попадают ли в этот пикер произвольные сторонние приложения, не подтверждено**. Но если это так, жест «W» на погашенном экране закрывает **ровно тот сценарий, который может не закрыть кнопка питания** (§4.1). Ограничение: это запуск активити, значит нужен тот же `showWhenLocked`, и это не тумблер «из коробки» — нужен `singleTop` + `onNewIntent`.

**Quick Launch — единственный официально задокументированный локскрин-триггер.** **[ОФИЦИАЛЬНО]** мануал OxygenOS 14:
> «Go to 'Settings > Security & privacy > Face & Fingerprint Unlock > Fingerprint > Quick Launch'… **When the screen is locked**, touch and hold the fingerprint sensor on the screen until an icon menu appears, then swipe your finger to the target icon to open the app.»

**[ВТОРИЧНО]** В OxygenOS 15 путь — `Settings > Accessibility and Convenience > Quick Launch`, вкладки «Shortcuts» и «Apps», где Apps содержит «regular apps from your app drawer» — https://www.trustedreviews.com/news/oneplus-13-tips-tricks-4598165
Бонус: жест одновременно **аутентифицирует** пользователя, то есть проблема «активити на локскрине» решается сама.

**Ярлык спец-возможностей** — **[ОФИЦИАЛЬНО, AOSP]** `PhoneWindowManager` гейтит аккорд громкости через `mAccessibilityShortcutController.isAccessibilityShortcutAvailable(isKeyguardLocked())`, а `AccessibilityShortcutController` реализует это как `return mIsShortcutEnabled && (!phoneLocked || mEnabledOnLockScreen);` (`mEnabledOnLockScreen` читает `Settings.Secure.ACCESSIBILITY_SHORTCUT_ON_LOCK_SCREEN`). Цели берутся из `Settings.Secure.ACCESSIBILITY_SHORTCUT_TARGET_SERVICE` — подходит любой установленный сервис. **[ОФИЦИАЛЬНО]** мануал OOS14 подтверждает наличие аккорда («Hold volume keys: Press and hold both volume buttons for 3 seconds»); Google: https://support.google.com/accessibility/android/answer/7650693
⚠️ Две оговорки. Первая: developer.android.com описывает для `AccessibilityService` только кнопку в навбаре (`flagRequestAccessibilityButton`), но **не** аккорд громкости — пригодность подтверждена исходниками AOSP, а не девелоперской документацией. Вторая, практическая: **`AccessibilityService` на OxygenOS/ColorOS сам по себе первоочередная мишень для «убийцы» фоновых процессов** — то есть этот запасной вариант наследует ровно ту проблему, от которой мы бежим (§5).

**Двойное нажатие питания** — **[ОФИЦИАЛЬНО, AOSP]** шлёт `MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE` при заблокированном экране (и обычный вариант иначе), если OEM не прибил `config_cameraGesturePackage`. Технически это настоящий локскрин-хук, но семантически это злоупотребление ролью камеры, и OnePlus почти наверняка прибивает свою камеру. Не рекомендуется.

## Что обязан проверить on-device probe (`nikitatrubaev-pdj.3`)

Документация не может ответить на эти вопросы — только железо с OxygenOS.

**Нулевой шаг: зафиксировать версию прошивки.** По состоянию на август 2026 OnePlus 13 уже переведён на OxygenOS 16, а всё исследование велось против 15. `adb shell getprop ro.build.version.ota` / `ro.build.display.id`.

### P1. 🔴 Приоритет №1: какой путь вызова работает (§2.7.1)

Собрать **две** сборки, отличающиеся одной строкой:

**P1a — путь A:** `VoiceInteractionService` включён.
**P1b — путь B:** сервис `android:enabled="false"`, работает `ACTION_ASSIST`-активити с `showWhenLocked`.

Для каждой:
```bash
adb shell cmd role get-role-holders android.app.role.ASSISTANT
adb shell settings get secure assistant
adb shell settings get secure voice_interaction_service   # у пути B должно быть пусто
adb logcat -c && adb logcat | grep -iE "QuickLaunch|VoiceInteraction|launchAssist|AssistManager|powerLongPress"
```
затем длинное нажатие питания. **Если A молчит, а B срабатывает — гипотеза `IsQuickLaunchSupport=false` подтверждена, и это определяет всю архитектуру.**

### P2. Пикер и роль
Открыть штатный пикер (Settings → Apps → Default apps → Digital assistant app) и проверить, **есть ли noteapp в списке**. Если пункта нет вовсе — проверить, не выключил ли OnePlus `config_showDefaultAssistant`.
Если приложения нет в списке:
```bash
adb shell cmd role add-role-holder android.app.role.ASSISTANT com.example.noteapp
adb shell cmd role set-bypassing-role-qualification true   # если отказ по квалификации
```

### P3. Значение long-press power и его настраиваемость
```bash
adb shell settings get global power_button_long_press
adb shell dumpsys window policy | grep -iE "LongPressOnPower|PowerAssistantTimeout"
```
Проверить наличие пункта `Settings → Accessibility & convenience → Power button → Press and hold` и возможность выбрать «Digital assistant».
Если UI не даёт — `adb shell settings put global power_button_long_press 5` (применяется без перезагрузки).

**Как отличить оверлей OnePlus от пользовательской настройки:**
```bash
adb shell settings delete global power_button_long_press
adb shell dumpsys window policy | grep -i longPressOnPower
```

Учесть предохранители (§2.1): при непровиженном устройстве 5 деградирует в 1, `launchAssistAction` выходит рано при `!isUserSetupComplete()`.

### P4. Перехват вызова OEM-лончером (§2.5)
```bash
adb logcat -c && adb logcat | grep -iE "AssistManager|onAssistantOverrideInvoked|OverviewProxy|Gemini"
```
Смотреть: дошло ли до `showSessionForActiveService`/`onShow` нашего процесса, или всплыл Gemini. Косвенная проверка: сравнить long-press power vs long-press home vs свайп из угла — если home работает, а power нет, это override лончера либо OEM-обработка клавиши.

### P5. Экран погашен vs заблокирован (§4.1)
Три сценария **по отдельности**:
1. экран включён, разблокирован → `onShow`/активити?
2. экран **включён, заблокирован** → ?
3. экран **погашен** → ? (по дефолтам AOSP — НЕТ)

Флаг в `dumpsys` не печатается. Косвенно:
```bash
adb logcat | grep "Not support long press power when device is not interactive"
```
Если строка появляется — `config_supportLongPressPowerWhenNonInteractive = false`, и сценарий «старт с погашенного экрана» через питание невозможен.

### P6. Тумблер: второе нажатие
Запустить запись, нажать ещё раз. Проверить, что вызов пришёл повторно и запись остановилась.
Путь A: логировать `args.getString(VoiceInteractionSession.KEY_SHOW_SESSION_ID)` — id должны быть **разными**.
Путь B: убедиться, что сработал `onNewIntent`, а не создался второй экземпляр активити (`launchMode="singleTop"`).
Замерить минимальный интервал между нажатиями, при котором доходят оба.

### P7. Микрофон из фона / с локскрина
Стартовать `microphone` FGS при **заблокированном** экране. Проверить:
* нет `SecurityException` / `ForegroundServiceStartNotAllowedException`;
* в логе **нет** `Foreground service started from background can not have location/camera/microphone access: service …`;
* нет `Starting FGS with type microphone … requires permissions`;
* в файле реально есть звук, а не тишина;
* **отдельно**: продолжается ли запись после того, как экран погас (нет официальных данных ни за, ни против).

```bash
adb shell appops get com.example.noteapp RECORD_AUDIO
adb shell dumpsys media.audio_flinger | grep -i "input\|record"
adb logcat | grep -iE "SecurityException.*microphone|FGS|can not have"
```

На пути B особенно проверить, что активити реально доходит до TOP: без `showWhenLocked` она уйдёт в stopped и микрофон не дадут (§6.2).

### P8. Устойчивость роли к переустановке APK (§1.4a)
Назначить ассистентом → `adb install -r` → `adb shell cmd role get-role-holders android.app.role.ASSISTANT`. Роль **должна остаться**.
Контрольный опыт: убрать `recognitionService` из XML, переустановить — роль должна слететь с логом `The RecognitionService must be set to avoid boot loop…`.

### P9. Выживаемость под OxygenOS (§5)
* Запись 30–60 мин с погашенным экраном → жив ли FGS.
* Пройти чеклист §5.5 и зафиксировать, **какие пункты реально существуют** в этой прошивке: есть ли `Battery > Battery optimization > ⋮ > Advanced optimization` или уже ColorOS-дерево `Battery > More settings > App battery management`; есть ли `Settings > Apps > Auto launch` (источники противоречат).
* Через несколько дней перепроверить, **не откатилось ли** исключение из battery optimization (DontKillMyApp утверждает, что OnePlus делает это произвольно).
* После перезагрузки: сохранились ли роль и настройка кнопки.
* Когда сервис умирает:
```bash
adb logcat | grep -iE "BgDetect|OppoBgApp|ColorOs|athena|ActivityManager.*died|SecurityException.*FGS"
adb shell dumpsys package com.example.noteapp | grep -i "stopped\|enabled"
adb shell dumpsys deviceidle whitelist
adb shell dumpsys voiceinteraction
```

### P10. Запасные триггеры (если P1/P5 провалятся)
Проверить, появляется ли noteapp в списках:
* `Settings → Accessibility & convenience → Gestures & motions → Screen-off gestures` (жест W) — **единственный кандидат, работающий с погашенного экрана**;
* `Settings → Accessibility & convenience → Quick Launch` → вкладка «Apps» — работает с локскрина и заодно аутентифицирует;
* ярлык спец-возможностей (обе клавиши громкости 3 с):
```bash
adb shell settings get secure accessibility_shortcut_target_service
adb shell settings get secure accessibility_shortcut_on_lock_screen   # должно быть 1
```

### P11. Конфликты жестов
Убедиться, что назначение long-press power на ассистента не сломало доступ к меню выключения (`Power + Volume Up` должен стать меню, `KEY_CHORD_POWER_VOLUME_UP = 2`, §2.3), и что двойное нажатие питания (камера) не конфликтует.

## Сводный список источников

### developer.android.com (официальная документация для разработчиков)
- RoleManager — https://developer.android.com/reference/android/app/role/RoleManager
- Foreground service types (в т.ч. `microphone`) — https://developer.android.com/develop/background-work/services/fgs/service-types
- Restrictions on starting FGS from the background — https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- …и раздел while-in-use с исключениями — https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start#wiu-restrictions
- Android 15: изменения типов foreground-сервисов — https://developer.android.com/about/versions/15/changes/foreground-service-types
- Android 15: package stopped state — https://developer.android.com/about/versions/15/behavior-changes-all#stopped-state
- Android 9: ограничение доступа к сенсорам/микрофону в фоне — https://developer.android.com/about/versions/pie/android-9.0-changes-all

### source.android.com (AOSP-документация)
- Android roles (таблица ролей, требования ASSISTANT) — https://source.android.com/docs/core/permissions/android-roles
- Voice interaction: app development — https://source.android.com/docs/automotive/voice/voice_interaction_guide/app_development
- Voice interaction: integration flows (RoleManager, предгрант прав) — https://source.android.com/docs/automotive/voice/voice_interaction_guide/integration_flows

### android.googlesource.com (исходники AOSP, ветка `main`)
**Роль ассистента**
- `androidx/core/role/RoleManagerCompat.java` — https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/core/core-role/src/main/java/androidx/core/role/RoleManagerCompat.java
- `PermissionController/res/xml/roles.xml` — https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/main/PermissionController/res/xml/roles.xml
- `AssistantRoleBehavior.java` — https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/main/PermissionController/role-controller/java/com/android/role/controller/behavior/AssistantRoleBehavior.java
- `role/controller/model/Role.java` — https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/main/PermissionController/role-controller/java/com/android/role/controller/model/Role.java
- `RequestRoleActivity.java` — https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/main/PermissionController/src/com/android/permissioncontroller/role/ui/RequestRoleActivity.java
- `RoleShellCommand.java` (`adb shell cmd role …`) — https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/main/service/java/com/android/role/RoleShellCommand.java

**Voice interaction**
- `VoiceInteractionService.java` — https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/service/voice/VoiceInteractionService.java
- `VoiceInteractionSession.java` — https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/service/voice/VoiceInteractionSession.java
- `VoiceInteractionSessionService.java` — https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/service/voice/VoiceInteractionSessionService.java
- `VoiceInteractionServiceInfo.java` — https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/service/voice/VoiceInteractionServiceInfo.java
- `VoiceInteractionManagerService.java` — https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/voiceinteraction/java/com/android/server/voiceinteraction/VoiceInteractionManagerService.java
- `VoiceInteractionManagerServiceImpl.java` — https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/voiceinteraction/java/com/android/server/voiceinteraction/VoiceInteractionManagerServiceImpl.java
- `VoiceInteractionSessionConnection.java` — https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/voiceinteraction/java/com/android/server/voiceinteraction/VoiceInteractionSessionConnection.java
- эталонный манифест `tests/VoiceInteraction/AndroidManifest.xml` — https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/tests/VoiceInteraction/AndroidManifest.xml
- пример `development/samples/VoiceInteractionService/AndroidManifest.xml` — https://android.googlesource.com/platform/development/+/refs/heads/main/samples/VoiceInteractionService/AndroidManifest.xml

**Кнопка питания / политика окон**
- `PhoneWindowManager.java` — https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/policy/PhoneWindowManager.java
- `SingleKeyGestureDetector.java` — https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/policy/SingleKeyGestureDetector.java
- `core/res/res/values/config.xml` — https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/res/res/values/config.xml
- `core/res/res/values/attrs.xml` — https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/res/res/values/attrs.xml

**Маршрутизация вызова ассистента**
- `SearchManager.java` — https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/SearchManager.java
- `SearchManagerService.java` — https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/search/SearchManagerService.java
- `AssistUtils.java` (INVOCATION_TYPE_*, disclosure) — https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/com/android/internal/app/AssistUtils.java
- SystemUI `AssistManager.java` (override-механизм) — https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SystemUI/src/com/android/systemui/assist/AssistManager.java
- SystemUI `CentralSurfacesImpl.java` (`onLockscreenShown`) — https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SystemUI/src/com/android/systemui/statusbar/phone/CentralSurfacesImpl.java

**Foreground service / права**
- `ActiveServices.java` (`shouldAllowFgsWhileInUsePermissionLocked`) — https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/am/ActiveServices.java
- `DefaultPermissionGrantPolicy.java` — https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/pm/permission/DefaultPermissionGrantPolicy.java
- `KeyguardManager.java` — https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/KeyguardManager.java
- `android/provider/Settings.java` — https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/provider/Settings.java
- `android/content/Intent.java` — https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/Intent.java

**Settings app**
- `PowerMenuSettingsUtils.java` — https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/main/src/com/android/settings/gestures/PowerMenuSettingsUtils.java
- `PowerMenuPreferenceController.java` — https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/main/src/com/android/settings/gestures/PowerMenuPreferenceController.java
- `LongPressPowerForAssistantPreferenceController.java` — https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/main/src/com/android/settings/gestures/LongPressPowerForAssistantPreferenceController.java

### Дополнительные источники (второй проход)

**AOSP, ветка `android15-release` (для сверки версий)**
- `packages/apps/Settings` — экран long-press power по версиям: [android15](https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/android15-release/res/xml/power_menu_settings.xml) · [android14](https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/android14-release/res/xml/power_menu_settings.xml) · [android13](https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/android13-release/res/xml/power_menu_settings.xml) · [android12](https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/android12-release/res/xml/power_menu_settings.xml) · [android11](https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/android11-release/res/xml/power_menu_settings.xml)
- `DefaultAssistPreferenceController.java` (ярлык на роль) — https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/android15-release/src/com/android/settings/applications/assist/DefaultAssistPreferenceController.java
- `ISystemUiProxy.aidl` / `IOverviewProxy.aidl` (контракт override) — https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android15-release/packages/SystemUI/shared/src/com/android/systemui/shared/recents/ISystemUiProxy.aidl · https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android15-release/packages/SystemUI/shared/src/com/android/systemui/shared/recents/IOverviewProxy.aidl
- Launcher3 `quickstep/util/AssistUtils.java` (в AOSP override — заглушка) — https://android.googlesource.com/platform/packages/apps/Launcher3/+/refs/heads/android15-release/quickstep/src/com/android/quickstep/util/AssistUtils.java
- `packages/Shell/AndroidManifest.xml` (у shell есть `WRITE_SECURE_SETTINGS` и `MANAGE_ROLE_HOLDERS`) — https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android15-release/packages/Shell/AndroidManifest.xml
- `tests/VoiceInteraction/res/xml/interaction_service.xml` — https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android15-release/tests/VoiceInteraction/res/xml/interaction_service.xml

**Google-документация**
- Android 15 CDD (3.8.4 [C‑2‑2], [H‑SR‑2]; 9.8 про hotword) — https://source.android.com/docs/compatibility/15/android-15-cdd
- Pixel: «Use Gemini on your Pixel phone» (Gemini как ассистент **по умолчанию**, не эксклюзивный) — https://support.google.com/pixelphone/answer/15283615?hl=en
- Pixel: «Use gestures on your Pixel phone» (Hold for Assistant) — https://support.google.com/pixelphone/answer/7443425?hl=en

**Вторичные источники — помечены как таковые в тексте**
- Android Authority: ChatGPT как ассистент по умолчанию — https://www.androidauthority.com/chatgpt-default-assistant-on-android-3535089/
- 9to5Google: ChatGPT default assistant — https://9to5google.com/2025/03/14/chatgpt-default-assistant-android/
- Android Authority: Perplexity сбрасывается на Gemini — https://www.androidauthority.com/perplexity-auto-switches-to-gemini-3534111/
- XDA (архив): включение «Hold for Assistant» через adb — http://web.archive.org/web/20221219102616/https://www.xda-developers.com/enable-android-12-hold-for-assistant-gesture/
- Button Mapper (Google Play): «does not work with … the power button» — https://play.google.com/store/apps/details?id=flar2.homebutton&hl=en
- DontKillMyApp — OnePlus (оценка 5/5 «💩») — https://dontkillmyapp.com/oneplus

### Источники по OxygenOS / OnePlus

**[ОФИЦИАЛЬНО] — единственные официальные источники, которые удалось прочитать**
- Мануал **OxygenOS 14.0** (новейший опубликованный; мануала OxygenOS 15 и OnePlus 13 **не существует** — прямые пробы CDN дают 404) — https://service.oneplus.com/content/dam/support/user-manuals/common/OxygenOS_14.0_User_Manual.pdf
- Страница OxygenOS 15 у OnePlus (Gemini как ассистент; про батарею/фон — ничего) — https://www.oneplus.com/us/oxygenos15
- Release notes OxygenOS 15.0.0.862 для OnePlus 13 (про фоновые лимиты — ничего) — https://community.oneplus.com/thread/1958157704140685317
- OPPO, управление батареей приложений (эпоха ColorOS 7.2, но это единственная официальная страница по теме) — https://support.oppo.com/en/answer/?aid=2020322

**[АНЕКДОТИЧЕСКИ, но device-specific — самая ценная находка]**
- GitHub PR moltbot/moltbot#3790 — `IsQuickLaunchSupport=false`, обход через `ACTION_ASSIST`, протестировано на **OnePlus 13**, OxygenOS 16.0.3.501 — https://github.com/moltbot/moltbot/pull/3790 · дифф: https://patch-diff.githubusercontent.com/raw/moltbot/moltbot/pull/3790.diff
- Alert Slider не имеет публичного API; читаемая настройка `three_Key_mode` — https://github.com/home-assistant/android/issues/2239
- «No auto launch setting in os 15?» — https://community.oneplus.com/thread/1741627536758013957
- «Fix Battery Drain After ColorOS 15 / OxygenOS 15 Update» (форум OPPO) — https://community.oppo.com/thread/1996943813003706370
- Рост idle-drain после OxygenOS 15 — https://community.oneplus.com/thread/1731068032496697352

**[ВТОРИЧНО]**
- Путь к настройке кнопки питания, статья именно про OnePlus 13 (+ Quick Launch в OxygenOS 15) — https://www.trustedreviews.com/news/oneplus-13-tips-tricks-4598165
- То же, другие обзоры — https://gadgets.beebom.com/guides/how-to-switch-off-oneplus-phones · https://www.cashify.in/how-to-switch-off-oneplus-phone
- OnePlus 13 — Alert Slider, а не Plus Key — https://www.gsmarena.com/oneplus_13-review-2777p2.php
- Plus Key (13T/13s) не запускает сторонние приложения — https://gadgetbridge.com/how-to/what-is-the-plus-key-on-oneplus-13s-heres-how-you-can-customise-it · https://www.androidauthority.com/oneplus-13t-shortcut-key-3542093/
- Жесты при выключенном экране настраиваются на любое приложение — https://www.bgr.com/2233618/oneplus-features-hidden-inside-oxygenos/ · https://devicology.com/guide/oneplus-gesture-guide-oxygenos/9608/
- «OxygenOS 15 и 16 по названиям близки к ColorOS» — https://express.ms/en/faq/background-work-fix/
- OxygenOS = реинкарнация ColorOS — https://www.gsmarena.com/oneplus_13-review-2777p4.php
- Gemini как ассистент по умолчанию в OxygenOS 15 — https://www.androidauthority.com/oxygen-os-15-review-3493444/
- DontKillMyApp — OnePlus (5/5 «💩», «No known solution on the developer end») — https://dontkillmyapp.com/oneplus
- «Advanced optimization» на OnePlus — https://oneplus.gadgethacks.com/how-to/disable-setting-if-notifications-are-delayed-your-oneplus-0192639/ · https://metactrl.com/docs/oneplus/ · https://support.bark.us/en/articles/13461435-battery-optimization-on-oneplus-devices
- Долгое нажатие питания = 0,5 с для ассистента, 3 с для меню — https://oneplus.gadgethacks.com/how-to/make-power-button-launch-google-assistant-your-oneplus-0214266/
- Digital Assistant app в списке Default apps ColorOS — https://oppo-parts.trackit.co.in/support/how-to-change-default-app-in-coloros

**AOSP, добавленное во втором проходе**
- `Activity.setShowWhenLocked` (почему `showWhenLocked` несущий) — https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/Activity.java
- `VoiceInteractionManagerServiceImpl.startLocked` (`BIND_INCLUDE_CAPABILITIES`) — https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/voiceinteraction/java/com/android/server/voiceinteraction/VoiceInteractionManagerServiceImpl.java
- `AccessibilityShortcutController.isAccessibilityShortcutAvailable` (аккорд громкости на локскрине) — https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/com/android/internal/accessibility/AccessibilityShortcutController.java
- `CameraIntents.kt` (двойное нажатие питания, `..._SECURE`) — https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SystemUI/src/com/android/systemui/camera/CameraIntents.kt
- Google: ярлык спец-возможностей клавишами громкости — https://support.google.com/accessibility/android/answer/7650693

**Чего проверить не удалось:** содержимое тредов community.oneplus.com (SPA без доступного API), актуальные официальные пути настроек OxygenOS 15 (мануала нет), поведение `config_supportLongPressPowerWhenNonInteractive` и `config_showDefaultAssistant` на OnePlus 13, наличие сторонних приложений в пикерах Screen-off gestures и Quick Launch.
