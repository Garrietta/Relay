plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

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

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            // Expressive APIs are @ExperimentalMaterial3ExpressiveApi; opt in
            // once here instead of annotating every call site.
            freeCompilerArgs.add("-Xopt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
            freeCompilerArgs.add("-Xopt-in=androidx.compose.animation.ExperimentalSharedTransitionApi")
        }
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    // Note: compose-bom 2026.09.00 pulls compose 1.12.x which requires
    // compileSdk 37; 2026.06.01 (compose 1.11.x, minCompileSdk 35) is the
    // newest BOM compatible with compileSdk 36 + AGP 8.13.x.
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Material 3 1.5.0-beta01: first release with public expressive APIs
    // (MaterialShapes + toShape(), LoadingIndicator, FloatingActionButtonMenu,
    // MaterialExpressiveTheme, MotionScheme). Verified against the published
    // artifact sources — every API name used here was checked. Overridden
    // above the compose BOM pin.
    implementation("androidx.compose.material3:material3:1.5.0-beta01")

    // SegmentedShape (Apache-2.0): FIRST/MIDDLE/LAST/SINGLE corner radii for
    // bubbly grouped message bubbles.
    implementation("com.github.NhuHuy-79:SegmentedShape:1.1.0")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("androidx.activity:activity-compose:1.10.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.foundation:foundation")
}
