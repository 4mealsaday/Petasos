import java.util.Properties

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

// Local, git-ignored release key; passwords live in .local/android/release-signing.properties.
// Without the complete key set the release variant is not created (debug is unaffected).
val releaseKeystore = rootProject.file(".local/android/release.keystore")
val releaseProps = Properties().apply {
    rootProject.file(".local/android/release-signing.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
}
val hasReleaseKey = releaseKeystore.isFile &&
    listOf("storePassword", "keyPassword", "keyAlias").all { !releaseProps.getProperty(it).isNullOrBlank() }
if (!hasReleaseKey) {
    logger.lifecycle("Petasos: release signing key not found under .local/android; the release variant is skipped (debug builds normally).")
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
        versionCode = 37
        versionName = "1.0.0-demo"
        // The current verified Pleos target is ARM64. Do not package untested host ABIs.
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        getByName("debug") {
            // Use the shared local key when it exists. Otherwise AGP falls back to its standard debug key
            // ($ANDROID_USER_HOME or ~/.android/debug.keystore) and creates it on first build, so a fresh
            // checkout builds without any setup.
            rootProject.file(".local/android/debug.keystore").takeIf { it.isFile }?.let { storeFile = it }
        }
        if (hasReleaseKey) create("release") {
            storeFile = releaseKeystore
            storePassword = releaseProps.getProperty("storePassword")
            keyPassword = releaseProps.getProperty("keyPassword")
            keyAlias = releaseProps.getProperty("keyAlias")
        }
    }

    buildTypes {
        getByName("debug") {
            buildConfigField("boolean", "DEMO", "true")
        }
        getByName("release") {
            // The submitted APK is the demo: virtual-location consent, short music gap, trip-end OFF.
            // Debug-only helpers (receivers under src/debug) are not part of this build type.
            buildConfigField("boolean", "DEMO", "true")
            signingConfigs.findByName("release")?.let { signingConfig = it }
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

androidComponents {
    beforeVariants(selector().withBuildType("release")) { variant ->
        if (!hasReleaseKey) variant.enable = false
    }
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
