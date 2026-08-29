plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  id("org.jetbrains.kotlin.plugin.compose")
  id("com.ncorti.ktfmt.gradle")
  id("io.gitlab.arturbosch.detekt")
}

android {
  namespace = "com.roflochinsky.noteapp"
  compileSdk = 35

  defaultConfig {
    applicationId = "com.roflochinsky.noteapp"
    minSdk = 34
    targetSdk = 35
    versionCode = 2
    versionName = "1.0"
  }

  signingConfigs {
    create("release") {
      // Ключ и пароли — в ~/.gradle/gradle.properties (NOTEAPP_*), в репо не попадают.
      providers.gradleProperty("NOTEAPP_STORE_FILE").orNull?.let { path ->
        storeFile = file(path)
        storePassword = providers.gradleProperty("NOTEAPP_STORE_PASSWORD").get()
        keyAlias = providers.gradleProperty("NOTEAPP_KEY_ALIAS").get()
        keyPassword = providers.gradleProperty("NOTEAPP_KEY_PASSWORD").get()
      }
    }
  }

  buildTypes {
    release {
      // Без NOTEAPP_* (чужая машина/CI) — debug-подпись, чтобы сборка не падала.
      signingConfig =
        if (providers.gradleProperty("NOTEAPP_STORE_FILE").isPresent)
          signingConfigs.getByName("release")
        else signingConfigs.getByName("debug")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  lint { abortOnError = true }

  buildFeatures { compose = true }

  // Тесты Compose гоняются Robolectric'ом в testDebugUnitTest — ему нужны настоящие ресурсы
  // и манифест, иначе тема и ComponentActivity не поднимаются.
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

kotlin { jvmToolchain(17) }

// Вывод смоуков (*SmokeTest) должен быть виден в консоли гейта — но только их: сплошной
// showStandardStreams топил эти строки в выводе остальных полутора сотен тестов.
tasks.withType<Test>().configureEach {
  testLogging { showStandardStreams = false }
  // Смоуки включаются переменными окружения, а Gradle их входом не считает: пишущий прогон
  // получал `FROM-CACHE` и выдавал за свой результат прошлого — `clean` тут не спасает, запись
  // в кэше живёт дольше выходных файлов (ловушка 7 в docs/harness/epic.md, поймана дважды).
  // Объявляем переменные входом: без них ключ кэша прежний и гейт быстрый, с ними — новый прогон.
  val smokeVars = listOf("NOTEAPP_SMOKE_TOKEN", "NOTEAPP_MIGRATE")
  smokeVars.forEach { name ->
    inputs.property(name, providers.environmentVariable(name).orNull).optional(true)
  }
  // `inputs.property` различает «переменной нет» и «переменная есть», но НЕ различает два
  // прогона с одним значением: `gh auth token` отдаёт тот же токен, `~/.gradle` общий на все
  // деревья агентов — и живой прогон приходил `FROM-CACHE` за 600 мс, выдавая чужой результат
  // за свой. Поэтому со включённым смоуком задача не кэшируется и не бывает up-to-date:
  // прогон против живого GitHub обязан быть настоящим всегда.
  val smokeOn = provider { smokeVars.any { providers.environmentVariable(it).orNull != null } }
  outputs.cacheIf { !smokeOn.get() }
  outputs.upToDateWhen { !smokeOn.get() }
  addTestOutputListener(
    TestOutputListener { descriptor, event ->
      if (descriptor.className?.endsWith("SmokeTest") == true) print(event.message)
    }
  )
}

ktfmt { kotlinLangStyle() }

detekt {
  buildUponDefaultConfig = true
  config.setFrom(rootProject.files("config/detekt/detekt.yml"))
}

dependencies {
  implementation(platform("androidx.compose:compose-bom:2024.12.01"))
  implementation("androidx.activity:activity-compose:1.9.3")
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.ui:ui")
  implementation("androidx.work:work-runtime-ktx:2.10.0")
  testImplementation("junit:junit:4.13.2")
  // org.json есть в Android SDK, но в JVM-юнитах стабы android.jar кидают — нужна реальная либа.
  testImplementation("org.json:json:20240303")
  // Экраны проверяются на JVM: Robolectric поднимает Android, compose ui-test — дерево семантики.
  // androidTest не берём — он требует эмулятора, а гейт офлайновый и без устройства.
  testImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
  testImplementation("androidx.compose.ui:ui-test-junit4")
  testImplementation("org.robolectric:robolectric:4.14.1")
  debugImplementation("androidx.compose.ui:ui-test-manifest")
}
