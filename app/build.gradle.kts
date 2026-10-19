import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing credentials: CI supplies them as env vars, local builds read
// them from local.properties (gitignored). Env wins so CI never picks up a
// stray local file.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signingCredential(envName: String, propName: String): String? =
    System.getenv(envName) ?: localProps.getProperty(propName)

android {
    namespace = "com.garrietta.relay"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.garrietta.relay"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        create("release") {
            storeFile = file("${project.rootDir}/keystore/relay.jks")
            storePassword = signingCredential("KEYSTORE_PASSWORD", "keystore.storePassword")
            keyAlias = signingCredential("KEY_ALIAS", "keystore.keyAlias")
            keyPassword = signingCredential("KEY_PASSWORD", "keystore.keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sign only when the keystore is present (CI decodes it before the
            // build). Local release builds without the keystore stay unsigned
            // instead of failing.
            if (file("${project.rootDir}/keystore/relay.jks").exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            // Shared-transition APIs are @ExperimentalSharedTransitionApi;
            // opt in once here instead of annotating every call site.
            freeCompilerArgs.add("-Xopt-in=androidx.compose.animation.ExperimentalSharedTransitionApi")
        }
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    // compose-bom 2026.06.01 (compose 1.11.x) — newest BOM compatible with
    // compileSdk 36 + AGP 8.13.x. (material3 1.5.0-beta01 was evaluated but
    // requires SDK 37 / AGP 9.1.0+, neither publicly available as of 2026-10;
    // expressive is hand-rolled on stable APIs instead.)
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Material 3 stable — expressive components (MaterialShapes, LoadingIndicator,
    // FloatingActionButtonMenu) are 1.5.0-beta01+ only; not usable until the
    // SDK 37 / AGP 9.x toolchain is public. Bubbly shapes are hand-rolled below.
    implementation("androidx.compose.material3:material3:1.4.0")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("androidx.activity:activity-compose:1.10.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-core")
    // Extended set: gallery/camera/draw composer icons (core lacks them).
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")

    // Palette: extract vibrant colors from contact profile photos to seed
    // per-conversation chat theming (same pattern as Koda's artwork theming).
    implementation("androidx.palette:palette:1.0.0")

    // Coil: load contact profile photos (content:// photo URIs) into avatars.
    implementation("io.coil-kt:coil-compose:2.7.0")

    // RCS transport: clean-room Messages-for-web protocol client (branch rcs).
    implementation("com.google.protobuf:protobuf-javalite:3.25.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")


    // graphics-shapes (stable): the sanctioned shape-morphing path on
    // material3 1.4.0 — powers the expressive seven-shape loading loop and
    // any morphing UI without the SDK 37 / 1.5.0-beta01 toolchain.
    implementation("androidx.graphics:graphics-shapes:1.0.1")
}

