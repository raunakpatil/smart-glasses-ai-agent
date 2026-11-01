import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    packaging {
        // BouncyCastle's jars each carry the same OSGi manifest; the app doesn't need them.
        resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
    namespace = "com.ronie.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ronie.app"
        minSdk = 34 // the Meta DAT Android SDK needs Android 14+
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // Meta Wearables SDK app credentials. "0" works while Developer Mode is on in the Meta AI app.
        manifestPlaceholders["mwdat_application_id"] = "0"
        manifestPlaceholders["mwdat_client_token"] = "0"
    }

    // Release signing comes from android-app/keystore.properties (never committed). Without it, release builds are unsigned.
    val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f ->
        Properties().apply { f.inputStream().use { load(it) } }
    }
    signingConfigs {
        if (keystoreProps != null) create("release") {
            storeFile = file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false // the Meta and Vosk SDKs use reflection; keep it simple
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // AGP's release lint crashes on this toolchain ("25.0.4" internal error); the debug build checks the same code.
    lint { checkReleaseBuilds = false }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1") // the phone's own certificate for the TV remote
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Meta Wearables Device Access Toolkit (glasses camera), from Maven Central.
    implementation("com.meta.wearable:mwdat-core:1.0.1")
    implementation("com.meta.wearable:mwdat-camera:1.0.1")
    // Google's QR scanner (no camera permission; Play services shows the scanner) for the brain pairing code.
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    // Vosk: offline "Ronie" wake word.
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
}
