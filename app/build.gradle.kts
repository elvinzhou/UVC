import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.borescope.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.elvinzhou.borescope" // permanent once published on Play
        minSdk = 29          // MediaStore writes without storage permission
        targetSdk = 36
        // CI passes these in; local builds fall back to the defaults.
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "0.1.0-dev"
    }

    // Release signing comes from the environment (GitHub Actions secrets in CI).
    // Without it, assembleRelease produces an unsigned APK.
    val keystorePath = System.getenv("SIGNING_KEYSTORE_PATH")
    signingConfigs {
        // Fixed, non-secret debug key so every CI/nightly APK has the same signature
        // and installs over the previous one (a per-runner random key can't update).
        // CI nightly key (from secrets). Without it the default per-machine debug key is used.
        val nightlyPath = System.getenv("NIGHTLY_KEYSTORE_PATH")
        if (nightlyPath != null) {
            getByName("debug") {
                storeFile = file(nightlyPath)
                storePassword = System.getenv("NIGHTLY_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("NIGHTLY_KEY_ALIAS")
                keyPassword = System.getenv("NIGHTLY_KEY_PASSWORD")
            }
        }
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SIGNING_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Nightly/dev builds install next to the release app instead of fighting its signature.
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

kotlin {
    compilerOptions { jvmTarget = JvmTarget.JVM_17 }
}

dependencies {
    implementation(project(":uvc"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
