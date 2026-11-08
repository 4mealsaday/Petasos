plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("androidx.room")
}

// The permissionless demo shares compiled, unused product speech adapters. Keep
// their four file-specific exceptions confined to an explicitly requested demo
// Lint invocation; product Lint and mixed task invocations must never use them.
val demoLint = providers.gradleProperty("petasosDemoLint").orNull == "true"
require(!demoLint || gradle.startParameter.taskNames.singleOrNull() in setOf("lintDemo", ":app:lintDemo")) {
    "petasosDemoLint requires only :app:lintDemo"
}

android {
    namespace = "com.petasos.app"
    compileSdk = 36
    buildToolsVersion = "35.0.0"
    useLibrary("android.car")

    defaultConfig {
        applicationId = "com.petasos.app"
        minSdk = 28
        targetSdk = 34
        versionCode = 33
        versionName = "0.8.0-dev.33"
        testInstrumentationRunner = if (providers.gradleProperty("petasosTestBuildType").orNull == "demo")
            "com.petasos.app.demo.DemoScenarioInstrumentation"
        else "com.petasos.app.diagnostics.SdkProbeInstrumentation"
        // The current verified Pleos target is ARM64. Do not package untested host ABIs.
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        getByName("debug") {
            // Studio and the CLI must sign updates with the same local key.
            storeFile = rootProject.file(".local/android/debug.keystore")
        }
    }

    buildTypes {
        create("privacy") {
            // Opt-in diagnostic build with the existing app data/signature. AGP disables
            // R8 optimization for debuggable apps; run-as fixtures are unavailable here.
            initWith(getByName("debug"))
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "privacy-rules.pro",
            )
            testProguardFiles("privacy-test-rules.pro")
        }
    }

    buildTypes.create("demo") {
        // Separate UID/data sandbox, with main core but no product Application/services/permissions.
        initWith(buildTypes.getByName("privacy"))
        applicationIdSuffix = ".demo"
        matchingFallbacks += listOf("release")
    }

    sourceSets.getByName("privacy") {
        // initWith copies options, not source sets. This diagnostic variant explicitly
        // shares debug's localhost-only CA domain config; ordinary release does not.
        manifest.srcFile("src/debug/AndroidManifest.xml")
        res.srcDirs("src/debug/res")
    }

    val requestedTestBuildType = providers.gradleProperty("petasosTestBuildType").orElse("debug").get()
    require(requestedTestBuildType in setOf("debug", "privacy", "demo")) { "Unsupported Petasos test build type" }
    testBuildType = requestedTestBuildType
    sourceSets.getByName("androidTest").assets.srcDir("schemas")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        lintConfig = file(if (demoLint) "lint-demo.xml" else "lint.xml")
        // Deliberately pin the documented toolchain and the Pleos API 34 target.
        // Revisit these version notices before upgrading the image or distributing.
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi", "AndroidGradlePluginVersion")
        warningsAsErrors = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.room:room-runtime:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("androidx.work:work-runtime:2.12.0")
    androidTestImplementation("androidx.work:work-testing:2.12.0")
    implementation("ai.pleos.playground:FusedLocation:1.1.3")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
}

room {
    schemaDirectory("$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Keep test forks bounded alongside the single Gradle worker on the development machine.
tasks.withType<Test>().configureEach {
    maxHeapSize = "256m"
    maxParallelForks = 1
}
