package com.example.scancast

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.media.*
import android.media.projection.MediaProjection
import android.os.*
import android.util.DisplayMetrics
import android.view.Display
import androidx.core.app.*
import androidx.core.content.*
import org.webrtc.*
import org.webrtc.audio.*
import java.lang.reflect.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.*

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
            val intent = Intent(context, ScreenCaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ROOM, room)
                .putExtra(EXTRA_DATA, projectionData)
                .putExtra(EXTRA_MUTE, mutePhone)
            ContextCompat.startForegroundService(context, intent)
        }

        fun setMute(context: Context, mutePhone: Boolean) {
            val intent = Intent(context, ScreenCaptureService::class.java)
                .setAction(ACTION_SET_MUTE)
                .putExtra(EXTRA_MUTE, mutePhone)
            context.startService(intent)
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
    private var audioTrack: org.webrtc.AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var sender: PeerJsSender? = null
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
                    val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    } else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification("Conectando…"), type)
                } catch (e: Exception) {
                    ShareState.update(ShareStatus.ERROR, "No se pudo iniciar el servicio: ${e.message}")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startSession(room, data, mutePhone)
            }
            ACTION_SET_MUTE -> {
                val mutePhone = intent.getBooleanExtra(EXTRA_MUTE, true)
                applyPhoneMute(mutePhone)
            }
            ACTION_STOP -> stopSession()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun applyPhoneMute(mute: Boolean) {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (mute) {
                if (!isPhoneMuted) {
                    previousVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
                }
                am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                customAdm?.gainMultiplier = 4.0f
                isPhoneMuted = true
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                }
                if (previousVolume > 0) {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, previousVolume, 0)
                } else {
                    val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol / 2, 0)
                }
                previousVolume = -1
                customAdm?.gainMultiplier = 1.8f
                isPhoneMuted = false
            }
        } catch (_: Throwable) {}
    }

    private fun startSession(room: String, projectionData: Intent, mutePhone: Boolean) {
        releaseMedia()
        finishing = false
        ShareState.update(ShareStatus.CONNECTING, "")

        try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(applicationContext).createInitializationOptions()
            )
            val egl = EglBase.create().also { eglBase = it }
            val screenCapturer = ScreenCapturerAndroid(projectionData, object : MediaProjection.Callback() {
                override fun onStop() { main.post { stopSession() } }
            }).also { capturer = it }

            val adm = InternalAudioDeviceModule(applicationContext) {
                extractMediaProjection(screenCapturer)
            }.also { customAdm = it }

            applyPhoneMute(mutePhone)

            val peerFactory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(adm)
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
                .createPeerConnectionFactory().also { factory = it }

            val audioConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "false"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "false"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "false"))
                mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "false"))
            }
            val aSource = peerFactory.createAudioSource(audioConstraints).also { audioSource = it }
            val aTrack = peerFactory.createAudioTrack("audio0", aSource).apply {
                setVolume(3.0)
            }.also { audioTrack = it }

            val source = peerFactory.createVideoSource(true).also { videoSource = it }
            val helper = SurfaceTextureHelper.create("ScreenCaptureThread", egl.eglBaseContext).also { surfaceHelper = it }
            screenCapturer.initialize(helper, applicationContext, source.capturerObserver)

            val (w, h) = captureSize()
            lastW = w
            lastH = h
            screenCapturer.startCapture(w, h, FPS)
            val vTrack = peerFactory.createVideoTrack("screen0", source).also { videoTrack = it }

            sender = PeerJsSender(
                factory = peerFactory,
                videoTrack = vTrack,
                audioTrack = aTrack,
                room = room,
                onStatus = { status, message ->
                    ShareState.update(status, message)
                    updateNotification(status)
                },
                onFatal = { message -> stopSession(message) }
            ).also { it.start() }
        } catch (e: Throwable) {
            finish("No se pudo iniciar la captura
