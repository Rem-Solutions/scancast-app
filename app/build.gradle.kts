// Si tu plantilla de Android Studio ya trae un bloque `plugins { alias(libs.plugins...) }`, conserva el tuyo.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.scancast"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.scancast"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false // sin R8: evita problemas con las clases nativas de WebRTC
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.google.android.material:material:1.12.0")

    // CameraX (vista previa + análisis de frames)
    val cameraX = "1.4.1"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")

    // ML Kit: lectura de códigos QR (modelo incluido en la app)
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // WebSocket para el servidor de señalización de PeerJS
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // WebRTC nativo para Android (paquete org.webrtc)
    implementation("io.getstream:stream-webrtc-android:1.3.10")
}
