plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "dev.codex.android"
    compileSdk = 34

    defaultConfig {
        // PROVISIONAL package id — Ziyad has not chosen a final one.
        // Trivially changeable (namespace + applicationId) before first release.
        applicationId = "dev.codex.android"
        minSdk = 26
        targetSdk = 34
        versionCode = 11
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    packaging {
        // PRoot binaries are packaged as .so files so the package manager
        // extracts them to nativeLibraryDir with execute permission
        // (SELinux-compliant execve() on targetSdk >= 29).
        jniLibs { useLegacyPackaging = true }
    }
    androidComponents {
        onVariants { variant ->
            // Keep debug symbols for the PRoot native binaries.
            // (No libbusybox.so: there is no host-side BusyBox artifact in H2.)
            variant.packaging.keepDebugSymbols += listOf("**/libproot*.so")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Termux terminal emulator + view (Apache-2.0) via JitPack (see settings.gradle.kts).
    // Modules built from the termux-app v0.118.1 tag.
    implementation("com.github.termux.termux-app:terminal-emulator:v0.118.1")
    implementation("com.github.termux.termux-app:terminal-view:v0.118.1")
    // XZ for Java (public domain) — in-app decompression of the Termux proot .deb's data.tar.xz.
    implementation("org.tukaani:xz:1.9")
    // kotlinx-serialization 1.6.3 is the newest runtime compatible with
    // Kotlin 1.9.25; 1.7.3 requires Kotlin 2.0.0+ (CI run 44345429591).
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    // Instrumented (emulator) + local unit tests. Versions pinned for
    // AGP 8.7.3 / Kotlin 1.9.25 / compileSdk 34.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    // The Compose BOM is declared under implementation, which does not
    // constrain the androidTest/debug configurations — declare it here too
    // or the version-less ui-test artifacts resolve to an empty version
    // (CI run 44514588883: "Could not find androidx.compose.ui:ui-test-junit4:.").
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.09.00"))
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    debugImplementation(platform("androidx.compose:compose-bom:2024.09.00"))
}
