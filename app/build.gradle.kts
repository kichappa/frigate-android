import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Version lives in gradle.properties (release.ps1 bumps it). versionCode is derived so
// it always grows with the version name: 1.2.3 -> 10203.
val appVersionName = providers.gradleProperty("appVersionName").get()
val appVersionCode = appVersionName.split(".").map { it.toInt() }
    .let { (major, minor, patch) -> major * 10000 + minor * 100 + patch }
val updateRepo = providers.gradleProperty("updateRepo").getOrElse("")

// Release signing key, kept out of git: keystore.properties in the project root with
// storeFile, storePassword, keyAlias, keyPassword. Every release must use the same key,
// or phones refuse the update.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Optional server address pre-filled on the setup screen, from local.properties (not in
// git), e.g. defaultServer=https://192.168.1.10:8971
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val defaultServer = localProps.getProperty("defaultServer", "")

android {
    namespace = "app.frigate.shell"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.frigate.shell"
        minSdk = 29
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
        buildConfigField("String", "DEFAULT_SERVER", "\"$defaultServer\"")
    }

    signingConfigs {
        if (!keystoreProps.isEmpty) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
            // Published APKs carry no private address; the user types it once.
            buildConfigField("String", "DEFAULT_SERVER", "\"\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
}
