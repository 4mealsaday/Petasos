plugins {
    id("com.android.application") version "8.10.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    // Match the pinned Kotlin 2.2.21 / AGP 8.10.1 toolchain.
    id("com.google.devtools.ksp") version "2.2.21-2.0.5" apply false
    id("androidx.room") version "2.8.5" apply false
}
