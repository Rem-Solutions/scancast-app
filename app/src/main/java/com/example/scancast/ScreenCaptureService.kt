package com.example.scancast

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.media.AudioAttributes
import android.media.AudioPlaybackCaptureConfiguration
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
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
import org.webrtc.AudioTrack
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnectionFactory
import org.webrtc.ScreenCapturerAndroid
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
        private const val EXTRA_ROOM = "room"
        private const val EXTRA_DATA = "projection_data"

        private const val CHANNEL_ID = "screen_share"
        private const val NOTIFICATION_ID = 1001

        private const val MAX_SIDE = 1280f
        private const val FPS = 30

        fun start(context: Context, room: String, projectionData: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ROOM, room)
                .putExtra(EXTRA_DATA, projectionData)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP))
        }
    }

    private val main = Handler(Looper.getMainLooper())

    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var mediaProjection: MediaProjection? = null
    private var sender: PeerJsSender? = null
    private var finishing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Transmisión de pantalla", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val room = intent.getStringExtra(EXTRA_ROOM)
                val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
                if (room == null || data == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                try {
                    ServiceCompat.startForeground(
                        this, NOTIFICATION_ID, buildNotification("Conectando…"),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    )
                } catch (e: Exception) {
                    ShareState.update(ShareStatus.ERROR, "No se pudo iniciar el servicio: ${e.message}")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startSession(room, data)
            }
            ACTION_STOP -> stopSession()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startSession(room: String, projectionData: Intent) {
        releaseMedia()
        finishing = false
        ShareState.update(ShareStatus.CONNECTING, "")

        try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(applicationContext)
                    .createInitializationOptions()
            )
            val egl = EglBase.create()
            eglBase = egl

            // 1. Obtener MediaProjection para compartirlo entre video y audio interno
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = projectionManager.getMediaProjection(Activity.RESULT_OK, projectionData)
            mediaProjection = projection

            // 2. Configurar módulo de audio interno (Android 10+)
            val admBuilder = JavaAudioDeviceModule.builder(applicationContext)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && projection != null) {
                val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .build()
                admBuilder.setAudioPlaybackCaptureConfiguration(captureConfig)
            }
            val adm = admBuilder.createAudioDeviceModule()
            audioDeviceModule = adm

            // 3. Crear PeerConnectionFactory con soporte de audio y video
            val peerFactory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(adm)
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
                .createPeerConnectionFactory()
            factory = peerFactory

            // 4. Crear Track de Audio
            val aSource = peerFactory.createAudioSource(MediaConstraints())
            audioSource = aSource
            val aTrack = peerFactory.createAudioTrack("audio0", aSource)
            audioTrack = aTrack

            // 5. Crear Track de Video
            val source = peerFactory.createVideoSource(true)
            videoSource = source

            val helper = SurfaceTextureHelper.create("ScreenCaptureThread", egl.eglBaseContext)
            surfaceHelper = helper

            val screenCapturer = ScreenCapturerAndroid(projectionData, object : MediaProjection.Callback() {
                override fun onStop() {
                    main.post { stopSession() }
                }
            })
            capturer = screenCapturer
            screenCapturer.initialize(helper, applicationContext, source.capturerObserver)

            val (w, h) = captureSize()
            screenCapturer.startCapture(w, h, FPS)

            val vTrack = peerFactory.createVideoTrack("screen0", source)
            videoTrack = vTrack

            // 6. Enviar Video + Audio por PeerJsSender
            val peerSender = PeerJsSender(
                factory = peerFactory,
                videoTrack = vTrack,
                audioTrack = aTrack,
                room = room,
                onStatus = { status, message ->
                    ShareState.update(status, message)
                    updateNotification(status)
                },
                onFatal = { message -> stopSession(message) }
            )
            sender = peerSender
            peerSender.start()
        } catch (e: Throwable) {
            finish("No se pudo iniciar la captura: ${e.message}")
        }
    }

    private fun stopSession(error: String? = null) {
        if (finishing) return
        val current = sender
        if (current == null) finish(error) else current.stop { finish(error) }
    }

    private fun finish(error: String?) {
        if (finishing) return
        finishing = true
        releaseMedia()
        ShareState.update(if (error == null) ShareStatus.IDLE else ShareStatus.ERROR, error ?: "")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseMedia() {
        try { sender?.shutdownNow() } catch (_: Throwable) {}
        sender = null
        try { capturer?.stopCapture() } catch (_: Throwable) {}
        try { capturer?.dispose() } catch (_: Throwable) {}
        capturer = null
        try { audioTrack?.dispose() } catch (_: Throwable) {}
        audioTrack = null
        try { audioSource?.dispose() } catch (_: Throwable) {}
        audioSource = null
        try { videoTrack?.dispose() } catch (_: Throwable) {}
        videoTrack = null
        try { videoSource?.dispose() } catch (_: Throwable) {}
        videoSource = null
        try { surfaceHelper?.dispose() } catch (_: Throwable) {}
        surfaceHelper = null
        try { factory?.dispose() } catch (_: Throwable) {}
        factory = null
        try { audioDeviceModule?.release() } catch (_: Throwable) {}
        audioDeviceModule = null
        try { mediaProjection?.stop() } catch (_: Throwable) {}
        mediaProjection = null
        try { eglBase?.release() } catch (_: Throwable) {}
        eglBase = null
    }

    override fun onDestroy() {
        releaseMedia()
        if (ShareState.status != ShareStatus.ERROR) ShareState.update(ShareStatus.IDLE, "")
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val screenCapturer = capturer ?: return
        try {
            val (w, h) = captureSize()
            screenCapturer.changeCaptureFormat(w, h, FPS)
        } catch (_: Throwable) {
        }
    }

    private fun captureSize(): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        val display = (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        val scale = min(1f, MAX_SIDE / max(metrics.widthPixels, metrics.heightPixels).toFloat())
        val width = (metrics.widthPixels * scale).toInt() and 1.inv()
        val height = (metrics.heightPixels * scale).toInt() and 1.inv()
        return width to height
    }

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
            this, 1,
            Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("ScanCast")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "Detener", stopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
