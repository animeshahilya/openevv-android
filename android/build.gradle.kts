// Top-level build file for the Compose UI (ported from EloquenceRevived).
// The legacy no-Gradle debug app (tools/build_debug_apk.py) ignores this.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
}
