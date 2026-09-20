import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.google.services)
}

// ---- Signing -------------------------------------------------------------------------------
// Every build (GitHub or local, debug or release) is signed with the SAME key, so a new APK
// always installs over the old one. The key ships in keystore/ - keep this repository private.
// To use a different key, set OT_KEYSTORE_FILE / OT_KEYSTORE_PASSWORD / OT_KEY_ALIAS /
// OT_KEY_PASSWORD as environment variables or in ~/.gradle/gradle.properties.
fun secret(name: String): String? =
  (System.getenv(name) ?: providers.gradleProperty(name).orNull)?.takeIf { it.isNotBlank() }

val keystorePath = secret("OT_KEYSTORE_FILE") ?: rootProject.file("keystore/officetracker.jks").path
val keystorePassword = secret("OT_KEYSTORE_PASSWORD") ?: "officetracker_key_2026"
val keyAliasName = secret("OT_KEY_ALIAS") ?: "officetracker"
val keyPasswordValue = secret("OT_KEY_PASSWORD") ?: keystorePassword

// ---- Versioning / updates ------------------------------------------------------------------
val ciVersionCode = secret("OT_VERSION_CODE")?.toIntOrNull()
val ciVersionName = secret("OT_VERSION_NAME")
val releaseRepo = secret("OT_RELEASE_REPO") ?: secret("GITHUB_REPOSITORY") ?: "Hasebul47/office_tracker_v2"
val hasReleaseKey = file(keystorePath).exists()

android {
  namespace = "com.officetracker"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    // Kept identical to v1 so installed phones can upgrade in place.
    applicationId = "com.aistudio.officetracker.vmpjxq"
    minSdk = 26
    targetSdk = 36
    // CI sets these from the build number so every push is a newer, installable update.
    versionCode = ciVersionCode ?: 21
    versionName = ciVersionName ?: "2.1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // Change these if you fork the repository or use a different login domain.
    // Where the app looks for updates: the repository that publishes the releases.
    buildConfigField("String", "GITHUB_REPO", "\"$releaseRepo\"")
    buildConfigField("String", "AUTH_EMAIL_DOMAIN", "\"staff.officetracker.app\"")
  }

  signingConfigs {
    if (hasReleaseKey) {
      create("release") {
        storeFile = file(keystorePath)
        storePassword = keystorePassword
        keyAlias = keyAliasName
        keyPassword = keyPasswordValue
        storeType = "PKCS12"
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
    }
    debug {
      // Same key as release when available, so a debug build can be replaced by a release build.
      if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }

  testOptions { unitTests { isReturnDefaultValues = true } }

  packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.lifecycle.service)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.play.services)
  implementation(libs.play.services.location)
  implementation(libs.firebase.auth)
  implementation(libs.firebase.firestore)
  implementation(libs.osmdroid.android)
  implementation(libs.okhttp)

  ksp(libs.androidx.room.compiler)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)
  debugImplementation(libs.androidx.compose.ui.tooling)
}
