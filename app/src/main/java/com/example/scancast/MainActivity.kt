package com.example.scancast

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var scanButton: MaterialButton
    private lateinit var connectButton: MaterialButton
    private lateinit var stopButton: MaterialButton
    private lateinit var roomInput: TextInputEditText
    private lateinit var scannerOverlay: FrameLayout
    private lateinit var previewView: PreviewView
    private lateinit var mutePhoneSwitch: SwitchMaterial

    private var pendingRoom: String? = null
    private var pendingAudioRoom: String? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var scanner: BarcodeScanner? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var scanHandled = false

    private val roomPattern = Regex("^[A-Za-z0-9]{4,32}$")

    // ---- Permisos y resultados -------------------------------------------------

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startScanner() else toast("Se necesita la cámara para escanear el QR.")
        }

    private val audioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            val room = pendingAudioRoom
            pendingAudioRoom = null
            if (room != null) launchProjectionIntent(room)
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* opcional */ }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val room = pendingRoom
            pendingRoom = null
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null && room != null) {
                ScreenCaptureService.start(this, room, data, mutePhoneSwitch.isChecked)
            } else {
                toast("Se canceló el permiso de captura de pantalla.")
            }
        }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = stopScanner()
    }

    // ---- Ciclo de vida ---------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        scanButton = findViewById(R.id.scanButton)
        connectButton = findViewById(R.id.connectButton)
        stopButton = findViewById(R.id.stopButton)
        roomInput = findViewById(R.id.roomInput)
        scannerOverlay = findViewById(R.id.scannerOverlay)
        previewView = findViewById(R.id.previewView)

        // Creamos el interruptor de "Silenciar celular" dinámicamente debajo de stopButton
        mutePhoneSwitch = SwitchMaterial(this).apply {
            text = "Silenciar altavoz del celular al transmitir"
            isChecked = true
            setPadding(0, 24, 0, 24)
            setOnCheckedChangeListener { _, isChecked ->
                if (ShareState.status == ShareStatus.STREAMING || ShareState.status == ShareStatus.CONNECTING) {
                    val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    if (isChecked) {
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                    } else {
                        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol / 2, 0)
                    }
                }
            }
        }
        (stopButton.parent as? ViewGroup)?.let { parent ->
            val index = parent.indexOfChild(stopButton)
            parent.addView(mutePhoneSwitch, index + 1)
        }

        onBackPressedDispatcher.addCallback(this, backCallback)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        scanButton.setOnClickListener { openScanner() }
        connectButton.setOnClickListener { connectWithTypedCode() }
        stopButton.setOnClickListener { ScreenCaptureService.stop(this) }
        findViewById<MaterialButton>(R.id.cancelScanButton).setOnClickListener { stopScanner() }
        roomInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                connectWithTypedCode()
                true
            } else false
        }
    }

    override fun onStart() {
        super.onStart()
        ShareState.listener = { render() }
        render()
    }

    override fun onStop() {
        ShareState.listener = null
        if (scannerOverlay.visibility == View.VISIBLE) stopScanner()
        super.onStop()
    }

    override fun onDestroy() {
        scanner?.close()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    // ---- Interfaz --------------------------------------------------------------

    private fun render() {
        val status = ShareState.status
        statusText.text = when (status) {
            ShareStatus.IDLE -> "Escanea el QR de la pantalla receptora."
            ShareStatus.CONNECTING -> "Conectando…"
            ShareStatus.STREAMING -> "Transmitiendo pantalla y audio"
            ShareStatus.RECONNECTING -> "Reconectando…"
            ShareStatus.ERROR -> ShareState.message.ifBlank { "Ocurrió un error." }
        }
        val active = status == ShareStatus.CONNECTING ||
            status == ShareStatus.STREAMING ||
            status == ShareStatus.RECONNECTING
        stopButton.visibility = if (active) View.VISIBLE else View.GONE
        scanButton.isEnabled = !active
        connectButton.isEnabled = !active
        roomInput.isEnabled = !active
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    // ---- Código de sala --------------------------------------------------------

    private fun extractRoom(raw: String?, allowRaw: Boolean): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true)) {
            val room = Uri.parse(text).getQueryParameter("room")?.trim()
            return if (room != null && roomPattern.matches(room)) room else null
        }
        return if (allowRaw && roomPattern.matches(text)) text.uppercase() else null
    }

    private fun connectWithTypedCode() {
        val room = extractRoom(roomInput.text?.toString(), allowRaw = true)
        if (room == null) {
            toast("Escribe un código de sala válido (por ejemplo ABC123) o pega el enlace completo.")
            return
        }
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(roomInput.windowToken, 0)
        beginShare(room)
    }

    /** Pide primero permiso de audio (para el sonido interno) y luego proyección de pantalla. */
    private fun beginShare(room: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingAudioRoom = room
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            launchProjectionIntent(room)
        }
    }

    private fun launchProjectionIntent(room: String) {
        pendingRoom = room
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    // ---- Escáner QR (CameraX + ML Kit) -----------------------------------------

    private fun openScanner() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startScanner() else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun startScanner() {
        scanHandled = false
        scannerOverlay.visibility = View.VISIBLE
        backCallback.isEnabled = true

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val client = BarcodeScanning.getClient(
                BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
            )
            scanner = client

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(cameraExecutor) { proxy ->
                val mediaImage = proxy.image
                if (mediaImage == null || scanHandled) {
                    proxy.close()
                    return@setAnalyzer
                }
                val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
                client.process(image)
                    .addOnSuccessListener { codes ->
                        val room = codes.firstNotNullOfOrNull { extractRoom(it.rawValue, allowRaw = false) }
                        if (room != null && !scanHandled) {
                            scanHandled = true
                            runOnUiThread {
                                stopScanner()
                                beginShare(room)
                            }
                        }
                    }
                    .addOnCompleteListener { proxy.close() }
            }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                toast("No se pudo abrir la cámara.")
                stopScanner()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopScanner() {
        backCallback.isEnabled = false
        scannerOverlay.visibility = View.GONE
        cameraProvider?.unbindAll()
        scanner?.close()
        scanner = null
    }
}
