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
}

kotlin { jvmToolchain(17) }

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
}
