// Root Gradle build for the DSH Android shell (Route B′ skeleton).
// Hard constraints (verified, see ../DSH-Android-移植方案.md §3.1):
//   • targetSdk MUST stay ≤ 28 — API≥29 forbids execve() of app-private files (node/dpkg die).
//   • applicationId must be EXACTLY 15 chars if ELF .dynstr repatching of the Termux prefix is used.
//   • arm64-v8a only; sideload distribution (Google Play impossible with targetSdk 28).
plugins {
    id("com.android.application") version "8.8.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
