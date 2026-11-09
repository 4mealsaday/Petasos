plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("androidx.room")
}

// Only these public Android identifiers may enter an APK. Do not load server credentials.
val publicMapKeyNames = listOf("PETASOS_NAVER_MAPS_ANDROID_KEY_ID", "PETASOS_NAVER_MAPS_CLIENT_ID")
val localPublicMapKeys = rootProject.file(".env").takeIf { it.isFile }?.useLines { lines ->
    lines.mapNotNull { line ->
        val name = line.substringBefore('=', "").trim()
        if (name !in publicMapKeyNames) null else name to line.substringAfter('=').trim()
            .removeSurrounding("\"").removeSurrounding("'")
    }.toMap()
}.orEmpty()
val naverMapPublicKeyId = publicMapKeyNames.asSequence().map { name ->
    providers.environmentVariable(name).orNull?.trim()?.takeIf { it.isNotEmpty() }
        ?: localPublicMapKeys[name].orEmpty()
}.firstOrNull { it.isNotEmpty() }.orEmpty()
require(naverMapPublicKeyId.isEmpty() || naverMapPublicKeyId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) {
    "Invalid public Naver Android map identifier format"
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
        versionCode = 36
        versionName = "0.8.0-dev.36"
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
        getByName("debug") {
            buildConfigField("boolean", "DEMO", "true")
        }
        getByName("release") {
            buildConfigField("boolean", "DEMO", "false")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    listOf("debug", "release").forEach { type ->
        sourceSets.getByName(type).java.srcDir("src/productMap/java")
        buildTypes.getByName(type).resValue("string", "petasos_naver_map_key_id", naverMapPublicKeyId)
        buildTypes.getByName(type).proguardFile("naver-map-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        lintConfig = file("lint.xml")
        // Deliberately pin the documented toolchain and the Pleos API 34 target.
        // Revisit these version notices before upgrading the image or distributing.
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi", "AndroidGradlePluginVersion")
        warningsAsErrors = true
    }
}

dependencies {
    listOf("debugImplementation", "releaseImplementation").forEach { variant ->
        add(variant, "com.naver.maps:map-sdk:3.24.0")
    }
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.room:room-runtime:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("androidx.work:work-runtime:2.12.0")
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
