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
        versionCode = 14
        versionName = "2.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing (v2.0.0): the signing config is created ONLY when the
    // CI keystore env vars are present (ANDROID_KEYSTORE_PASSWORD,
    // ANDROID_KEY_ALIAS, ANDROID_KEY_PASSWORD). Local and debug builds
    // without them are unaffected — no signingConfig is created or applied.
    // ANDROID_KEYSTORE_PATH is optional: it defaults to "release.keystore"
    // in the project root (where CI decodes ANDROID_KEYSTORE_BASE64).
    // .trim(): a secret pasted into the GitHub UI can carry a trailing
    // newline, which makes key decryption fail with "Given final block
    // not properly padded" (CI run 44640870701).
    val releaseKeystorePresent = System.getenv("ANDROID_KEYSTORE_PASSWORD") != null
        && System.getenv("ANDROID_KEY_ALIAS") != null
        && System.getenv("ANDROID_KEY_PASSWORD") != null
    if (releaseKeystorePresent) {
        signingConfigs {
            create("release") {
                storeFile = file(System.getenv("ANDROID_KEYSTORE_PATH") ?: "release.keystore")
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")?.trim()
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")?.trim()
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")?.trim()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            // Sign the release build only when the CI keystore is present;
            // without it the release build stays unsigned (debug unaffected).
            if (releaseKeystorePresent) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        jniLibs {
            useLegacyPackaging = true
            // Keep the debug symbols of the PRoot binaries: AGP strips
            // native debug symbols by default. This DSL block is the only
            // place the option exists in AGP 8.7.3 — the variant API
            // (androidComponents.onVariants { variant.packaging... }) has
            // no such member (CI: "Unresolved reference: keepDebugSymbols").
            keepDebugSymbols += "**/libproot*.so"
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
    // H6 (M10): Desktop Workstation window-size classes. BOM-managed —
    // no version here; the Compose BOM 2024.09.00 pins 1.3.0.
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // PdfBox-Android (Apache-2.0): PDF text extraction for read_pdf.
    // The Android port of Apache PDFBox; its font tables live in the
    // AAR's assets, so MainActivity calls PDFBoxResourceLoader.init
    // once at startup (see DocumentTools.kt).
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
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
    // Rosemoe Sora Editor (LGPL-2.1) — unmodified Maven Central AAR, never
    // vendored in source. Used by editor/SoraEditorScreen.kt (CodeEditor,
    // SchemeDarcula, EditorSearcher). No new repository needed: Maven Central
    // is a default repository.
    // Coordinate history (2026-11-19): the staged
    // io.github.rosemoe.sora:editor:0.24.3 does not exist — Maven Central has
    // no group io.github.rosemoe.sora at all, and no 0.24.3 anywhere (CI run
    // 44627663684: Central 404, then JitPack 401 aborted resolution). The real
    // group is io.github.Rosemoe.sora-editor (capital R). 0.23.6 (newest on
    // Central) was then tried and failed CI run 44628565583: it is built with
    // Kotlin 2.1.21, whose 2.1.0 metadata the pinned Kotlin 1.9.25 compiler
    // cannot read. 0.23.5 is the newest Sora release built with Kotlin 1.9.x
    // (1.9.23, per the sora-editor repo's version catalog at tag 0.23.5), so
    // it is the newest version compatible with this project's Kotlin 1.9.25
    // pin. Do NOT upgrade past 0.23.5 without also upgrading Kotlin. The Java
    // package inside the AAR is io.github.rosemoe.sora.*, so
    // SoraEditorScreen.kt imports are unchanged.
    implementation("io.github.Rosemoe.sora-editor:editor:0.23.5")
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
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
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
