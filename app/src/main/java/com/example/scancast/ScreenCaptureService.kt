package com.example.scancast

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import org.webrtc.AudioSource
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnectionFactory
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import kotlin.math.max
import kotlin.math.min

class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_START = "com.example.scancast.START"
        const val ACTION_STOP = "com.example.scancast.STOP"
        const val ACTION_SET_MUTE = "com.example.scancast.SET_MUTE"
        private const val EXTRA_ROOM = "room"
        private const val EXTRA_DATA = "projection_data"
        private const val EXTRA_MUTE = "mute_phone"
        private const val CHANNEL_ID = "screen_share"
        private const val NOTIFICATION_ID = 1001
        private const val MAX_SIDE = 1920f
        private const val FPS = 30

        fun start(context: Context, room: String, projectionData: Intent, mutePhone: Boolean = true) {
            val intent = Intent(context, ScreenCaptureService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_ROOM, room).putExtra(EXTRA_DATA, projectionData).putExtra(EXTRA_MUTE, mutePhone)
            ContextCompat.startForegroundService(context, intent)
        }

        fun setMute(context: Context, mutePhone: Boolean) {
            context.startService(
                Intent(context, ScreenCaptureService::class.java).setAction(ACTION_SET_MUTE).putExtra(EXTRA_MUTE, mutePhone)
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var audioModule: JavaAudioDeviceModule? = null
    private var audioBridge: PlaybackAudioBridge? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: org.webrtc.AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var capturer: ResizableScreenCapturer? = null
    private var sender: PeerJsSender? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var finishing = false
    private var previousVolume = -1
    private var isPhoneMuted = false
    private var lastW = 0
    private var lastH = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Transmisión de pantalla y audio", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val room = intent.getStringExtra(EXTRA_ROOM)
                val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
                val mutePhone = intent.getBooleanExtra(EXTRA_MUTE, true)
                if (room == null || data == null) { stopSelf(); return START_NOT_STICKY }
                try {
                    // El tipo "microphone" solo se declara si el permiso está concedido; si no, se transmite solo video.
                    val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && hasMicPermission()) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    } else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification("Conectando…"), type)
                } catch (e: Exception) {
                    ShareState.update(ShareStatus.ERROR, "Error de servicio: ${e.message}")
                    stopSelf(); return START_NOT_STICKY
                }
                startSession(room, data, mutePhone)
            }
            ACTION_SET_MUTE -> applyPhoneMute(intent.getBooleanExtra(EXTRA_MUTE, true))
            ACTION_STOP -> stopSession()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    /** Silencia (o restaura) el altavoz del celular. El audio interno se captura aparte. */
    private fun applyPhoneMute(mute: Boolean) {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (mute) {
                if (!isPhoneMuted) previousVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                isPhoneMuted = true
            } else {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                val target = if (previousVolume > 0) previousVolume else am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 2
                am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                previousVolume = -1
                isPhoneMuted = false
            }
        } catch (_: Throwable) {}
    }

    private fun startSession(room: String, projectionData: Intent, mutePhone: Boolean) {
        releaseMedia(); finishing = false; ShareState.update(ShareStatus.CONNECTING, "")
        try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(applicationContext).createInitializationOptions()
            )
            val egl = EglBase.create().also { eglBase = it }

            // 1) El MediaProjection se obtiene primero: lo necesita el audio interno.
            val cap = ResizableScreenCapturer(projectionData) { main.post { stopSession() } }.also { capturer = it }
            cap.prepare(applicationContext)
            val projection = cap.mediaProjection ?: throw IllegalStateException("No se obtuvo la captura de pantalla")

            // 2) Fábrica WebRTC. Con permiso de micrófono y Android 10+, el audio sale del audio interno.
            val factoryBuilder = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            var withAudio = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasMicPermission()) {
                val bridge = PlaybackAudioBridge(projection).also { audioBridge = it }
                val module = bridge.createModule(applicationContext).also { audioModule = it }
                factoryBuilder.setAudioDeviceModule(module)
                withAudio = true
            }
            val pf = factoryBuilder.createPeerConnectionFactory().also { factory = it }

            var aTrack: org.webrtc.AudioTrack? = null
            if (withAudio) {
                // Sin procesado de voz: es música/video, no una llamada.
                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "false"))
                    mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "false"))
                    mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "false"))
                    mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "false"))
                }
                val aSource = pf.createAudioSource(constraints).also { audioSource = it }
                aTrack = pf.createAudioTrack("audio0", aSource).also { audioTrack = it }
            }

            // 3) Video
            val vSource = pf.createVideoSource(true).also { videoSource = it }
            val helper = SurfaceTextureHelper.create("ScreenCaptureThread", egl.eglBaseContext).also { surfaceHelper = it }
            cap.initialize(helper, applicationContext, vSource.capturerObserver)
            val (w, h) = captureSize().also { lastW = it.first; lastH = it.second }
            cap.startCapture(w, h, FPS)
            val vTrack = pf.createVideoTrack("screen0", vSource).also { videoTrack = it }

            watchRotation()
            applyPhoneMute(mutePhone)

            sender = PeerJsSender(
                pf, vTrack, aTrack, room,
                onStatus = { s, m -> ShareState.update(s, m); updateNotification(s) },
                onFatal = { m -> stopSession(m) }
            ).also { it.start() }
        } catch (e: Throwable) {
            finish("Error al iniciar captura: ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // Rotación: se redimensiona el VirtualDisplay, nunca se recrea
    // ------------------------------------------------------------------

    private fun watchRotation() {
        val manager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) applyCaptureSize()
            }
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
        }
        displayListener = listener
        manager.registerDisplayListener(listener, main)
    }

    private fun applyCaptureSize() {
        try {
            val (w, h) = captureSize()
            if (w > 0 && h > 0 && (w != lastW || h != lastH)) {
                lastW = w; lastH = h
                capturer?.resize(w, h)
            }
        } catch (_: Throwable) {}
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        main.postDelayed({ applyCaptureSize() }, 150)
    }

    private fun captureSize(): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics)
        val scale = min(1f, MAX_SIDE / max(metrics.widthPixels, metrics.heightPixels).toFloat())
        return max(16, (metrics.widthPixels * scale).toInt() and 15.inv()) to
            max(16, (metrics.heightPixels * scale).toInt() and 15.inv())
    }

    // ------------------------------------------------------------------
    // Cierre
    // ------------------------------------------------------------------

    private fun stopSession(error: String? = null) {
        if (finishing) return
        val cur = sender
        if (cur == null) finish(error) else cur.stop { finish(error) }
    }

    private fun finish(error: String?) {
        if (finishing) return
        finishing = true; releaseMedia()
        ShareState.update(if (error == null) ShareStatus.IDLE else ShareStatus.ERROR, error ?: "")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf()
    }

    private fun releaseMedia() {
        if (isPhoneMuted) {
            try {
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                if (previousVolume != -1) am.setStreamVolume(AudioManager.STREAM_MUSIC, previousVolume, 0)
            } catch (_: Throwable) {}
        }
        previousVolume = -1; isPhoneMuted = false; lastW = 0; lastH = 0

        displayListener?.let {
            try { (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(it) } catch (_: Throwable) {}
        }
        displayListener = null

        try { sender?.shutdownNow() } catch (_: Throwable) {}; sender = null
        try { audioBridge?.release() } catch (_: Throwable) {}; audioBridge = null
        try { capturer?.stopCapture() } catch (_: Throwable) {}
        try { capturer?.dispose() } catch (_: Throwable) {}; capturer = null
        try { audioTrack?.dispose() } catch (_: Throwable) {}; audioTrack = null
        try { audioSource?.dispose() } catch (_: Throwable) {}; audioSource = null
        try { videoTrack?.dispose() } catch (_: Throwable) {}; videoTrack = null
        try { videoSource?.dispose() } catch (_: Throwable) {}; videoSource = null
        try { surfaceHelper?.dispose() } catch (_: Throwable) {}; surfaceHelper = null
        try { factory?.dispose() } catch (_: Throwable) {}; factory = null
        try { audioModule?.release() } catch (_: Throwable) {}; audioModule = null
        try { eglBase?.release() } catch (_: Throwable) {}; eglBase = null
    }

    override fun onDestroy() {
        releaseMedia()
        if (ShareState.status != ShareStatus.ERROR) ShareState.update(ShareStatus.IDLE, "")
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Notificación
    // ------------------------------------------------------------------

    private fun updateNotification(status: ShareStatus) {
        val text = when (status) {
            ShareStatus.STREAMING -> "Transmitiendo pantalla y audio"
            ShareStatus.RECONNECTING -> "Reconectando…"
            else -> "Conectando…"
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_share).setContentTitle("ScanCast").setContentText(text)
            .setOngoing(true).setContentIntent(openIntent).addAction(0, "Detener", stopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE).build()
    }
}
