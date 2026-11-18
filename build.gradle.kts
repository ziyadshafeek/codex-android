// Top-level build file — plugin versions only; module config lives in app/build.gradle.kts.
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "1.9.25" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.25" apply false
    // Compose Compiler 1.5.15 (Compose BOM 2024.09.00) requires Kotlin 1.9.25;
    // 1.9.24 fails :app:compileDebugKotlin (CI run 44289474958).
}
