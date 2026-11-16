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
import kotlin.concurrent.thread
import kotlin.math.*

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
    private var previousVolume = -1

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
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                previousVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            } catch (_: Throwable) {}
        }

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
            val aTrack = peerFactory.createAudioTrack("audio0", aSource).also { audioTrack = it }

            val source = peerFactory.createVideoSource(true).also { videoSource = it }
            val helper = SurfaceTextureHelper.create("ScreenCaptureThread", egl.eglBaseContext).also { surfaceHelper = it }
            screenCapturer.initialize(helper, applicationContext, source.capturerObserver)

            val (w, h) = captureSize()
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
            finish("No se pudo iniciar la captura: ${e.message}")
        }
    }

    private fun extractMediaProjection(sc: ScreenCapturerAndroid): MediaProjection? = try {
        ScreenCapturerAndroid::class.java.getDeclaredField("mediaProjection").apply { isAccessible = true }.get(sc) as? MediaProjection
    } catch (_: Throwable) { null }

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
                (getSystemService(Context.AUDIO_SERVICE) as AudioManager).setStreamVolume(AudioManager.STREAM_MUSIC, previousVolume, 0)
            } catch (_: Throwable) {}
            previousVolume = -1
        }
        try { sender?.shutdownNow() } catch (_: Throwable) {}; sender = null
        try { capturer?.stopCapture() } catch (_: Throwable) {}
        try { capturer?.dispose() } catch (_: Throwable) {}; capturer = null
        try { audioTrack?.dispose() } catch (_: Throwable) {}; audioTrack = null
        try { audioSource?.dispose() } catch (_: Throwable) {}; audioSource = null
        try { videoTrack?.dispose() } catch (_: Throwable) {}; videoTrack = null
        try { videoSource?.dispose() } catch (_: Throwable) {}; videoSource = null
        try { surfaceHelper?.dispose() } catch (_: Throwable) {}; surfaceHelper = null
        try { factory?.dispose() } catch (_: Throwable) {}; factory = null
        try { customAdm?.release() } catch (_: Throwable) {}; customAdm = null
        try { eglBase?.release() } catch (_: Throwable) {}; eglBase = null
    }

    override fun onDestroy() {
        releaseMedia()
        if (ShareState.status != ShareStatus.ERROR) ShareState.update(ShareStatus.IDLE, "")
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        try {
            val (w, h) = captureSize()
            capturer?.changeCaptureFormat(w, h, FPS)
        } catch (_: Throwable) {}
    }

    private fun captureSize(): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics)
        val scale = min(1f, MAX_SIDE / max(metrics.widthPixels, metrics.heightPixels).toFloat())
        return ((metrics.widthPixels * scale).toInt() and 1.inv()) to ((metrics.heightPixels * scale).toInt() and 1.inv())
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
            this, 1, Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
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

private class InternalAudioDeviceModule(
    context: Context,
    private val projectionProvider: () -> MediaProjection?
) : AudioDeviceModule {

    private val delegate: JavaAudioDeviceModule = JavaAudioDeviceModule.builder(context)
        .setUseHardwareAcousticEchoCanceler(false)
        .setUseHardwareNoiseSuppressor(false)
        .setSampleRate(48000)
        .setAudioSource(MediaRecorder.AudioSource.UNPROCESSED)
        .createAudioDeviceModule()

    @Volatile private var recording = false
    private var audioRecord: AudioRecord? = null

    init { startInternalCapture() }

    override fun getNativeAudioDeviceModulePointer(): Long = delegate.nativeAudioDeviceModulePointer

    override fun release() {
        recording = false
        try { audioRecord?.stop() } catch (_: Throwable) {}
        try { audioRecord?.release() } catch (_: Throwable) {}
        audioRecord = null
        delegate.release()
    }

    override fun setSpeakerMute(mute: Boolean) = delegate.setSpeakerMute(mute)
    override fun setMicrophoneMute(mute: Boolean) = delegate.setMicrophoneMute(mute)

    @SuppressLint("MissingPermission")
    private fun startInternalCapture() {
        if (recording || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        recording = true
        thread(start = true, name = "InternalAudioBridge") {
            var projection: MediaProjection? = null
            for (i in 0 until 30) {
                projection = projectionProvider()
                if (projection != null) break
                Thread.sleep(100)
            }
            if (projection == null) { recording = false; return@thread }

            try {
                val recordField: Field = delegate.javaClass.getDeclaredField("audioInput").apply { isAccessible = true }
                val webRtcRecord: Any = recordField.get(delegate) ?: return@thread
                val byteBufferField: Field = webRtcRecord.javaClass.getDeclaredField("byteBuffer").apply { isAccessible = true }
                val nativeDataMethod: Method = webRtcRecord.javaClass.getDeclaredMethod("nativeDataIsRecorded", Int::class.javaPrimitiveType, Long::class.javaPrimitiveType).apply { isAccessible = true }
                val nativePtrField: Field = webRtcRecord.javaClass.getDeclaredField("nativeAudioRecord").apply { isAccessible = true }

                val sampleRate = 48000
                val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()

                val format = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()

                val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val record = AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(config)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(max(minBuf, 4096))
                    .build().also { audioRecord = it }

                record.startRecording()

                while (recording) {
                    val directBuffer = byteBufferField.get(webRtcRecord) as? ByteBuffer
                    val nativeRecPtr = nativePtrField.getLong(webRtcRecord)
                    if (directBuffer != null && nativeRecPtr != 0L) {
                        directBuffer.clear()
                        val read = record.read(directBuffer, directBuffer.capacity())
                        if (read > 0) nativeDataMethod.invoke(webRtcRecord, read, nativeRecPtr)
                    } else {
                        Thread.sleep(20)
                    }
                }
            } catch (_: Throwable) {}
        }
    }
}
