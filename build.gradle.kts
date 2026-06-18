// Top-level build file for the VideoAnalyzer Android project.
// We use Kotlin 2.0 + the bundled Compose Compiler plugin (kotlin.plugin.compose)
// — that's the modern path that replaces the old composeOptions { kotlinCompilerExtensionVersion = … }.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
}