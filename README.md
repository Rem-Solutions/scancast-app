# 📱 ScanCast

Aplicación para Android desarrollada en **Kotlin** que permite transmitir la pantalla del dispositivo y el **audio interno del sistema** en tiempo real hacia un navegador web o pantalla receptora mediante **WebRTC**, conectándose al instante con un código QR o código de sala.

---

## ✨ Características

* **Conexión rápida:** Escaneo de código QR integrado (CameraX + ML Kit) o ingreso manual por código de sala.
* **Transmisión fluida en alta calidad:** Captura de pantalla hasta **1080p a 30 FPS** con soporte para rotación automática y videos en pantalla completa.
* **Audio interno limpio:** Captura directa del audio del sistema (`AudioPlaybackCapture` a 48 kHz) sin interferencias ni captura del micrófono físico.
* **Control de silencio en vivo:** Interruptor integrado para silenciar el altavoz del celular mientras se transmite sin cortar el audio que recibe la PC.
* **Servicio en segundo plano:** Ejecución estable mediante `ForegroundService` con notificación persistente y botón de detención rápida.

---

## 📲 Cómo instalar y usar

1. **Descargar el APK:**
   * Ve a la pestaña **[Releases](../../releases)** (o a la última ejecución exitosa en **[Actions](../../actions)**) y descarga el archivo `app-debug.apk`.
   * Instálalo en tu dispositivo Android (Android 10 o superior).
2. **Conectar:**
   * Abre la pantalla receptora en tu PC para generar el código QR / código de sala.
   * Abre **ScanCast** en tu teléfono, pulsa **Escanear QR y Transmitir** (o escribe el código manualmente).
   * Concede los permisos de grabación de audio (necesario para el audio interno) y selecciona **Toda la pantalla** al iniciar la proyección.

---

## 🛠️ Tecnologías utilizadas

* **Lenguaje:** Kotlin
* **Streaming P2P:** WebRTC (`io.getstream:stream-webrtc-android`)
* **Escáner QR:** AndroidX CameraX + Google ML Kit Barcode Scanning
* **Captura multimedia:** Android `MediaProjection` + `AudioPlaybackCaptureConfiguration`
* **CI/CD:** Compilación automática de APK mediante GitHub Actions

---

## 📋 Requisitos

* **Android 10 (API 29)** o superior (requerido por Android para capturar audio interno del sistema).
