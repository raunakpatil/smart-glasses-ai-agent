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
    // Vosk: offline "Ronie" wake word.
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
}
