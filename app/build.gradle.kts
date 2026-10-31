plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
}

android {
  namespace = "com.tacts.camera"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.tacts.camera"
    minSdk = 24
    targetSdk = 36
    versionCode = 4
    versionName = "2.2"
  }

  signingConfigs {
    // Release key and passwords come from the environment, never from the repository.
    create("release") {
      System.getenv("KEYSTORE_PATH")?.let { storeFile = file(it) }
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = System.getenv("KEY_ALIAS") ?: "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      if (System.getenv("KEYSTORE_PATH") != null) signingConfig = signingConfigs.getByName("release")
    }
    debug { signingConfig = signingConfigs.getByName("debugConfig") }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures { compose = true }
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  // CameraX for preview, photo and video; camera2 interop for the manual controls
  implementation(libs.androidx.camera.core)
  implementation(libs.androidx.camera.camera2)
  implementation(libs.androidx.camera.lifecycle)
  implementation(libs.androidx.camera.video)
  implementation(libs.androidx.camera.view)
  implementation(libs.kotlinx.coroutines.android)
  // Settings are one serialized object in DataStore
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.kotlinx.serialization.json)
  // Glass from T.A.R.K.A.S.: frosted blur, and liquid glass (refraction) on Android 13+
  implementation(libs.haze)
  implementation(libs.backdrop)
  testImplementation(libs.junit)
  debugImplementation(libs.androidx.compose.ui.tooling)
}
