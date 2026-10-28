import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: either a keystore.properties file in the project root,
// or environment variables (used by the GitHub Actions workflow).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? =
    keystoreProps.getProperty(key) ?: System.getenv(env)

val releaseStore = signingValue("storeFile", "NDT_KEYSTORE_PATH")

android {
    namespace = "com.niraj.drivetest"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.niraj.drivetest"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            if (releaseStore != null) {
                storeFile = rootProject.file(releaseStore)
                storePassword = signingValue("storePassword", "NDT_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "NDT_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "NDT_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseStore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // OpenStreetMap map view (no Google Maps API key needed)
    implementation("org.osmdroid:osmdroid-android:6.1.20")
}
