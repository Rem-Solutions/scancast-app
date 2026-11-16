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
            val intent = Intent(context, ScreenCaptureService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_ROOM, room).putExtra(EXTRA_DATA, projectionData).putExtra(EXTRA_MUTE, mutePhone)
            ContextCompat.startForegroundService(context, intent)
        }

        fun setMute(context: Context, mutePhone: Boolean) {
            context.startService(Intent(context, ScreenCaptureService::class.java).setAction(ACTION_SET_MUTE).putExtra(EXTRA_MUTE, mutePhone))
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
                if (room == null || data == null) { stopSelf(); return START_NOT_STICKY }
                try {
                    val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
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

    private fun applyPhoneMute(mute: Boolean) {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (mute) {
                if (!isPhoneMuted) previousVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                customAdm?.gainMultiplier = 4.0f
                isPhoneMuted = true
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                val target = if (previousVolume > 0) previousVolume else am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 2
                am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                previousVolume = -1
                customAdm?.gainMultiplier = 1.8f
                isPhoneMuted = false
            }
        } catch (_: Throwable) {}
    }

    private fun startSession(room: String, projectionData: Intent, mutePhone: Boolean) {
        releaseMedia(); finishing = false; ShareState.update(ShareStatus.CONNECTING, "")
        try {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(applicationContext).createInitializationOptions())
            val egl = EglBase.create().also { eglBase = it }
            val sc = ScreenCapturerAndroid(projectionData, object : MediaProjection.Callback() {
                override fun onStop() { main.post { stopSession() } }
            }).also { capturer = it }

            val adm = InternalAudioDeviceModule(applicationContext) { extractMediaProjection(sc) }.also { customAdm = it }
            applyPhoneMute(mutePhone)

            val pf = PeerConnectionFactory.builder()
                .setAudioDeviceModule(adm)
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
                .createPeerConnectionFactory().also { factory = it }

            val ac = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "false"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "false"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "false"))
                mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "false"))
            }
            val aSource = pf.createAudioSource(ac).also { audioSource = it }
            val aTrack = pf.createAudioTrack("audio0", aSource).apply { setVolume(3.0) }.also { audioTrack = it }

            val vSource = pf.createVideoSource(true).also { videoSource = it }
            val helper = SurfaceTextureHelper.create("ScreenCaptureThread", egl.eglBaseContext).also { surfaceHelper = it }
            sc.initialize(helper, applicationContext, vSource.capturerObserver)

            val (w, h) = captureSize().also { lastW = it.first; lastH = it.second }
            sc.startCapture(w, h, FPS)
            val vTrack = pf.createVideoTrack("screen0", vSource).also { videoTrack = it }

            sender = PeerJsSender(pf, vTrack, aTrack, room,
                onStatus = { s, m -> ShareState.update(s, m); updateNotification(s) },
                onFatal = { m -> stopSession(m) }
            ).also { it.start() }
        } catch (e: Throwable) {
            finish("Error al iniciar captura: ${e.message}")
        }
    }
    private fun extractMediaProjection(sc: ScreenCapturerAndroid): MediaProjection? {
        for (f in ScreenCapturerAndroid::class.java.declaredFields) {
            if (MediaProjection::class.java.isAssignableFrom(f.type)) {
                try { f.isAccessible = true; (f.get(sc) as? MediaProjection)?.let { return it } } catch (_: Throwable) {}
            }
        }
        return null
    }

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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                if (previousVolume != -1) am.setStreamVolume(AudioManager.STREAM_MUSIC, previousVolume, 0)
            } catch (_: Throwable) {}
        }
        previousVolume = -1; isPhoneMuted = false; lastW = 0; lastH = 0
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
        main.postDelayed({
            try {
                val (w, h) = captureSize()
                if (w > 0 && h > 0 && (w != lastW || h != lastH)) { lastW = w; lastH = h; capturer?.changeCaptureFormat(w, h, FPS) }
            } catch (_: Throwable) {}
        }, 150)
    }

    private fun captureSize(): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics)
        val scale = min(1f, MAX_SIDE / max(metrics.widthPixels, metrics.heightPixels).toFloat())
        return max(16, (metrics.widthPixels * scale).toInt() and 15.inv()) to max(16, (metrics.heightPixels * scale).toInt() and 15.inv())
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
        val stopIntent = PendingIntent.getService(this, 1, Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val openIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_share).setContentTitle("ScanCast").setContentText(text)
            .setOngoing(true).setContentIntent(openIntent).addAction(0, "Detener", stopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE).build()
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
        .setAudioSource(MediaRecorder.AudioSource.DEFAULT)
        .createAudioDeviceModule()

    @Volatile var gainMultiplier: Float = 3.5f
    @Volatile private var recording = false
    private var internalRecord: AudioRecord? = null

    init { startInternalCapture() }

    override fun getNativeAudioDeviceModulePointer(): Long = delegate.nativeAudioDeviceModulePointer
    override fun release() {
        recording = false
        try { internalRecord?.stop() } catch (_: Throwable) {}
        try { internalRecord?.release() } catch (_: Throwable) {}
        internalRecord = null; delegate.release()
    }
    override fun setSpeakerMute(mute: Boolean) = delegate.setSpeakerMute(mute)
    override fun setMicrophoneMute(mute: Boolean) = delegate.setMicrophoneMute(true)

    @SuppressLint("MissingPermission")
    private fun startInternalCapture() {
        if (recording || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        recording = true
        thread(start = true, name = "InternalAudioBridge") {
            var projection: MediaProjection? = null
            for (i in 0 until 40) { projection = projectionProvider(); if (projection != null) break; Thread.sleep(100) }
            if (projection == null) { recording = false; return@thread }

            try {
                val webRtcRecord: Any = delegate.javaClass.declaredFields.firstNotNullOfOrNull { f ->
                    f.isAccessible = true
                    val v = f.get(delegate)
                    if (v != null && v.javaClass.declaredFields.any { ByteBuffer::class.java.isAssignableFrom(it.type) }) v else null
                } ?: return@thread

                val byteBufferField = webRtcRecord.javaClass.declaredFields.first { ByteBuffer::class.java.isAssignableFrom(it.type) }.apply { isAccessible = true }
                val nativeDataMethod = webRtcRecord.javaClass.declaredMethods.first { m -> m.parameterTypes.size == 2 && m.parameterTypes[0] == Int::class.javaPrimitiveType && m.parameterTypes[1] == Long::class.javaPrimitiveType }.apply { isAccessible = true }
                val nativePtrField = webRtcRecord.javaClass.declaredFields.first { it.type == Long::class.javaPrimitiveType }.apply { isAccessible = true }
                val micRecordField = webRtcRecord.javaClass.declaredFields.firstOrNull { AudioRecord::class.java.isAssignableFrom(it.type) }?.apply { isAccessible = true }
                val micThreadField = webRtcRecord.javaClass.declaredFields.firstOrNull { Thread::class.java.isAssignableFrom(it.type) }?.apply { isAccessible = true }

                val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME).addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
                val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
                val minBuf = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val record = AudioRecord.Builder().setAudioPlaybackCaptureConfig(config).setAudioFormat(format).setBufferSizeInBytes(max(minBuf, 4096)).build().also { internalRecord = it }
                record.startRecording()

                while (recording) {
                    try {
                        val micThread = micThreadField?.get(webRtcRecord) as? Thread
                        if (micThread != null && micThread.isAlive) {
                            micThread.javaClass.declaredFields.firstOrNull { it.type == Boolean::class.javaPrimitiveType }?.apply { isAccessible = true; setBoolean(micThread, false) }
                            micThread.interrupt()
                        }
                        val physicalMic = micRecordField?.get(webRtcRecord) as? AudioRecord
                        if (physicalMic != null && physicalMic !== internalRecord) {
                            try { physicalMic.stop() } catch (_: Throwable) {}; try { physicalMic.release() } catch (_: Throwable) {}
                            micRecordField?.set(webRtcRecord, null)
                        }
                    } catch (_: Throwable) {}

                    val directBuffer = byteBufferField.get(webRtcRecord) as? ByteBuffer
                    val nativeRecPtr = nativePtrField.getLong(webRtcRecord)
                    if (directBuffer != null && nativeRecPtr != 0L) {
                        directBuffer.clear()
                        val read = record.read(directBuffer, directBuffer.capacity())
                        if (read > 0) {
                            if (gainMultiplier > 1.01f) amplifyPcm16(directBuffer, read, gainMultiplier)
                            nativeDataMethod.invoke(webRtcRecord, read, nativeRecPtr)
                        }
                    } else Thread.sleep(20)
                }
            } catch (_: Throwable) {}
        }
    }

    private fun amplifyPcm16(buffer: ByteBuffer, bytesRead: Int, gain: Float) {
        val shortBuf = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        for (i in 0 until bytesRead / 2) {
            shortBuf.put(i, (shortBuf.get(i) * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
        }
    }
}
