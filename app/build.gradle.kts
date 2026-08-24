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
    versionCode = 1
    versionName = "0.1-probe"
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

dependencies {
  implementation(platform("androidx.compose:compose-bom:2024.12.01"))
  implementation("androidx.activity:activity-compose:1.9.3")
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.ui:ui")
  testImplementation("junit:junit:4.13.2")
}
