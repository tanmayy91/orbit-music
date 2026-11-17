import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.secrets)
}

// Release signing: the key and its passwords live outside git, in keystore.properties (ignored) or in
// ORBIT_MUSIC_KEYSTORE_* environment variables. Without either, the release APK is built unsigned.
val signing = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
fun secret(key: String, env: String): String? = signing.getProperty(key) ?: System.getenv(env)
val releaseStore = secret("storeFile", "ORBIT_MUSIC_KEYSTORE_FILE")

// A release build installed beside the phone's own: -Porbitmusic.applicationIdSuffix=.rc. Testing one
// then needs no uninstall, which would wipe the listener's data.
val sideBySide = providers.gradleProperty("orbitmusic.applicationIdSuffix").orNull

@Suppress("UNCHECKED_CAST")
val versionManifest = groovy.json.JsonSlurper().parseText(
    providers.fileContents(rootProject.layout.projectDirectory.file("v.json")).asText.get(),
) as Map<String, Any?>
val appVersionName = (providers.gradleProperty("orbitmusic.version").orNull
    ?: (versionManifest["version"] as? String))
    ?.trim()?.removePrefix("v")?.removePrefix("V")
    ?.takeIf { it.isNotEmpty() }
    ?: error("Missing \"version\" in v.json")
val explicitVersionCode = (versionManifest["versionCode"] as? Number)?.toInt()
val computedVersionCode = appVersionName
    .substringBefore('-')
    .substringBefore('+')
    .split('.')
    .let { parts ->
        val major = parts.getOrNull(0)?.toIntOrNull() ?: error("Invalid version in v.json: $appVersionName")
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
        major * 1_000_000 + minor * 1_000 + patch
    }
val appVersionCode = explicitVersionCode ?: computedVersionCode

android {
    namespace = "app.orbitmusic"
    compileSdk = 37
    defaultConfig {
        applicationId = "app.orbitmusic"
        minSdk = 29
        targetSdk = 36
        // major * 1,000,000 + minor * 1,000 + patch, read from v.json so it only ever rises.
        versionCode = appVersionCode
        versionName = appVersionName
    }
    signingConfigs {
        create("debugConfig") {
            storeFile = file("${rootDir}/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (releaseStore != null) {
            create("release") {
                storeFile = file(releaseStore)
                storePassword = secret("storePassword", "ORBIT_MUSIC_KEYSTORE_PASSWORD")
                keyAlias = secret("keyAlias", "ORBIT_MUSIC_KEY_ALIAS")
                keyPassword = secret("keyPassword", "ORBIT_MUSIC_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debugConfig")
            applicationIdSuffix = ".debug"
            // Not debuggable by default, as in PODIUM: ART compiles it, so shaders and scrolling run
            // as they will for listeners. For a debugger: -Porbitmusic.debuggable=true
            isDebuggable = providers.gradleProperty("orbitmusic.debuggable").orNull == "true"
        }
        release {
            // Not shrunk: what's released is the same code that's tested on the phone every day, and
            // nothing can go missing that only reflection asks for. The libraries' baseline profiles
            // are still installed (profileinstaller), so the first run is already compiled.
            sideBySide?.let { applicationIdSuffix = it }
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

secrets {
    propertiesFileName = ".env"
    defaultPropertiesFileName = ".env.example"
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:data"))
    implementation(project(":core:lyrics"))
    implementation(project(":player"))
    implementation(project(":sources:youtube"))
    implementation(project(":sources:local"))
    implementation(project(":sources:addon"))
    implementation(project(":sources:apple"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.coil.compose)
    // The welcome's water: a short looping clip played muted into a TextureView.
    implementation(libs.media3.exoplayer)
    // An artist's short film: HLS, video only, kept in a cache of its own.
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.datasource)
    implementation(libs.media3.database)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    // The releases GitHub lists, for Orbit Music's own updates.
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.profileinstaller)

    // Verse's line model and the offline albums (./gradlew :app:testDebugUnitTest).
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
