package com.example.scancast

import android.annotation.SuppressLint
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
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
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
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.nio.ByteBuffer
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_START = "com.example.scancast.START"
        const val ACTION_STOP = "com.example.scancast.STOP"
        private const val EXTRA_ROOM = "room"
        private const val EXTRA_DATA = "projection_data"
        private const val EXTRA_MUTE = "mute_phone"

        private const val CHANNEL_ID = "screen_share"
        private const val NOTIFICATION_ID = 1001

        private const val MAX_SIDE = 1920f
        private const val FPS = 30

        fun start(context: Context, room: String, projectionData: Intent, mutePhone: Boolean = true) {
            val intent = Intent(context, ScreenCaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ROOM, room)
                .putExtra(EXTRA_DATA, projectionData)
                .putExtra(EXTRA_MUTE, mutePhone)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP))
        }
    }

    private val main = Handler(Looper.getMainLooper())

    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var customAdm: InternalAudioDeviceModule? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var sender: PeerJsSender? = null
    private var finishing = false

    private var previousVolume: Int = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Transmisión de pantalla y audio", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val room = intent.getStringExtra(EXTRA_ROOM)
                val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
                val mutePhone = intent.getBooleanExtra(EXTRA_MUTE, true)
                if (room == null || data == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                try {
                    val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    } else {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    }
                    ServiceCompat.startForeground(
                        this, NOTIFICATION_ID, buildNotification("Conectando…"),
                        serviceType
                    )
                } catch (e: Exception) {
                    ShareState.update(ShareStatus.ERROR, "No se pudo iniciar el servicio: ${e.message}")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startSession(room, data, mutePhone)
            }
            ACTION_STOP -> stopSession()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startSession(room: String, projectionData: Intent, mutePhone: Boolean) {
        releaseMedia()
        finishing = false
        ShareState.update(ShareStatus.CONNECTING, "")

        if (mutePhone) {
            try {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                previousVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            } catch (_: Throwable) {}
        }

        try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(applicationContext)
                    .createInitializationOptions()
            )
            val egl = EglBase.create()
            eglBase = egl

            val screenCapturer = ScreenCapturerAndroid(projectionData, object : MediaProjection.Callback() {
                override fun onStop() {
                    main.post { stopSession() }
                }
            })
            capturer = screenCapturer

            val adm = InternalAudioDeviceModule(applicationContext) {
                extractMediaProjection(screenCapturer)
            }
            customAdm = adm

            val peerFactory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(adm)
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
                .createPeerConnectionFactory()
            factory = peerFactory

            // Apagar procesamiento de voz de WebRTC para evitar efecto cueva/eco
            val audioConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "false"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "false"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "false"))
                mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "false"))
            }
            val aSource = peerFactory.createAudioSource(audioConstraints)
            audioSource = aSource
            val aTrack = peerFactory.createAudioTrack("audio0", aSource)
            audioTrack = aTrack

            val source = peerFactory.createVideoSource(true)
            videoSource = source

            val helper = SurfaceTextureHelper.create("ScreenCaptureThread", egl.eglBaseContext)
            surfaceHelper = helper

            screenCapturer.initialize(helper, applicationContext, source.capturerObserver)

            val (w, h) = captureSize()
            screenCapturer.startCapture(w, h, FPS)

            val vTrack = peerFactory.createVideoTrack("screen0", source)
            videoTrack = vTrack

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

    private fun extractMediaProjection(screenCapturer: ScreenCapturerAndroid): MediaProjection? {
        return try {
            val field = ScreenCapturerAndroid::class.java.getDeclaredField("mediaProjection")
            field.isAccessible = true
            field.get(screenCapturer) as? MediaProjection
        } catch (_: Throwable) {
            null
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
        if (previousVolume != -1) {
            try {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, previousVolume, 0)
            } catch (_: Throwable) {}
            previousVolume = -1
        }

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
        try { customAdm?.release() } catch (_: Throwable) {}
        customAdm = null
        try { eglBase?.release() } catch (_: Throwable) {}
        eglBase = null
    }

    override fun onDestroy() {
        releaseMedia()
        if (ShareState.status != ShareStatus.ERROR) ShareState.update(ShareStatus.IDLE, "")
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(new
