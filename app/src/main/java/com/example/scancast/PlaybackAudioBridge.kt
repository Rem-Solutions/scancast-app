package com.example.scancast

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.util.Log
import org.webrtc.audio.AudioRecordDataCallback
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import kotlin.math.max

/**
 * Audio interno del celular -> WebRTC, sin usar el micrófono.
 *
 * WebRTC en Android solo sabe grabar del micrófono: crea un AudioRecord de micrófono y un hilo que
 * lo lee cada 10 ms. Se hace en dos capas:
 *
 *  1) MODO DIRECTO (el bueno): justo cuando WebRTC arranca su hilo de grabación, se cambia el
 *     AudioRecord del micrófono por un AudioRecord de captura de reproducción (AudioPlaybackCapture)
 *     y el micrófono se detiene y se libera al instante. El hilo de WebRTC lee audio interno
 *     directamente; el micrófono ya no se usa. Usa reflexión sobre campos internos de la librería.
 *
 *  2) MODO RESPALDO: si la reflexión falla (otra versión de la librería), el micrófono queda abierto
 *     pero cada bloque de 10 ms que WebRTC iba a enviar se sobrescribe con audio interno, así que
 *     nunca sale sonido del micrófono (sin eco ni sonido ambiente).
 *
 * Solo se debe instanciar en Android 10+ (API 29).
 */
@SuppressLint("NewApi")
class PlaybackAudioBridge(private val projection: MediaProjection) : AudioRecordDataCallback {

    private companion object {
        const val TAG = "ScanCastAudio"
    }

    /** 1.0 = sin cambios. Por encima de ~1.5 empieza a distorsionar. */
    @Volatile var gain: Float = 1.0f

    /** "directo" = micrófono reemplazado; "respaldo" = micrófono abierto pero descartado. */
    @Volatile var mode: String = "respaldo"
        private set

    @Volatile private var released = false
    @Volatile private var swapped = false
    private var failed = false
    private var module: JavaAudioDeviceModule? = null
    private var fallbackRecord: AudioRecord? = null
    private var directRecord: AudioRecord? = null
    private var scratch = ByteArray(0)

    fun createModule(context: Context): JavaAudioDeviceModule {
        val created = JavaAudioDeviceModule.builder(context)
            .setSampleRate(48000)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioRecordStateCallback(object : JavaAudioDeviceModule.AudioRecordStateCallback {
                override fun onWebRtcAudioRecordStart() {
                    swapMicForPlaybackCapture()
                }

                override fun onWebRtcAudioRecordStop() {}
            })
            .setAudioRecordDataCallback(this)
            .createAudioDeviceModule()
        module = created
        return created
    }

    // ------------------------------------------------------------------
    // Capa 1: cambiar el AudioRecord del micrófono por el de captura interna
    // ------------------------------------------------------------------

    private fun swapMicForPlaybackCapture() {
        if (released || swapped) return
        var playback: AudioRecord? = null
        try {
            val adm = module ?: return
            val inputField = JavaAudioDeviceModule::class.java.declaredFields
                .firstOrNull { it.type.simpleName == "WebRtcAudioRecord" } ?: return
            inputField.isAccessible = true
            val webRtcRecord = inputField.get(adm) ?: return

            val recordField = webRtcRecord.javaClass.declaredFields
                .firstOrNull { AudioRecord::class.java.isAssignableFrom(it.type) } ?: return
            recordField.isAccessible = true
            val micRecord = recordField.get(webRtcRecord) as? AudioRecord ?: return

            playback = createPlaybackRecord(micRecord.sampleRate, micRecord.channelCount) ?: return
            playback.startRecording()
            recordField.set(webRtcRecord, playback)
            directRecord = playback
            swapped = true
            mode = "directo"
            Log.i(TAG, "Micrófono reemplazado por audio interno (modo directo)")

            // El hilo de WebRTC ya lee del nuevo AudioRecord; se apaga el micrófono.
            Thread.sleep(30)
            try { micRecord.stop() } catch (_: Throwable) {}
            try { micRecord.release() } catch (_: Throwable) {}

            // Si el modo respaldo alcanzó a crear su propio AudioRecord, ya no hace falta.
            try { fallbackRecord?.stop() } catch (_: Throwable) {}
            try { fallbackRecord?.release() } catch (_: Throwable) {}
            fallbackRecord = null
        } catch (t: Throwable) {
            Log.w(TAG, "No se pudo reemplazar el micrófono; se usa el modo respaldo", t)
            if (!swapped) {
                try { playback?.release() } catch (_: Throwable) {}
            }
        }
    }

    // ------------------------------------------------------------------
    // Capa 2 (respaldo): sobrescribir cada bloque de 10 ms con audio interno
    // ------------------------------------------------------------------

    override fun onAudioDataRecorded(
        audioFormat: Int,
        channelCount: Int,
        sampleRate: Int,
        audioBuffer: ByteBuffer
    ) {
        if (swapped) return // el modo directo ya entrega audio interno

        val total = audioBuffer.capacity()
        if (scratch.size != total) scratch = ByteArray(total)

        var got = 0
        val rec = ensureFallbackRecord(sampleRate, channelCount)
        if (rec != null) {
            while (got < total && !released && !swapped) {
                val n = rec.read(scratch, got, total - got)
                if (n <= 0) break
                got += n
            }
        }
        if (got < total) java.util.Arrays.fill(scratch, got, total, 0.toByte()) // silencio, nunca micrófono

        applyGain(total)

        audioBuffer.clear()
        audioBuffer.put(scratch, 0, total)
        audioBuffer.clear()
    }

    private fun ensureFallbackRecord(sampleRate: Int, channelCount: Int): AudioRecord? {
        fallbackRecord?.let { return it }
        if (failed || released) return null
        val created = createPlaybackRecord(sampleRate, channelCount)
        if (created == null) {
            failed = true
            return null
        }
        return try {
            created.startRecording()
            fallbackRecord = created
            created
        } catch (_: Throwable) {
            failed = true
            try { created.release() } catch (_: Throwable) {}
            null
        }
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun createPlaybackRecord(sampleRate: Int, channelCount: Int): AudioRecord? = try {
        val channelMask =
            if (channelCount == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        val bytes100ms = sampleRate * channelCount * 2 / 10
        AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(config)
            .setAudioFormat(format)
            .setBufferSizeInBytes(max(minBuffer, bytes100ms))
            .build()
    } catch (t: Throwable) {
        Log.w(TAG, "No se pudo crear la captura de audio interno", t)
        null
    }

    private fun applyGain(bytes: Int) {
        val g = gain
        if (g == 1.0f) return
        var i = 0
        while (i + 1 < bytes) {
            val sample = ((scratch[i].toInt() and 0xFF) or (scratch[i + 1].toInt() shl 8)).toShort().toInt()
            val amplified = (sample * g).toInt().coerceIn(-32768, 32767)
            scratch[i] = (amplified and 0xFF).toByte()
            scratch[i + 1] = ((amplified shr 8) and 0xFF).toByte()
            i += 2
        }
    }

    fun release() {
        released = true
        try { fallbackRecord?.stop() } catch (_: Throwable) {}
        try { fallbackRecord?.release() } catch (_: Throwable) {}
        fallbackRecord = null
        // En modo directo WebRTC libera este AudioRecord por su cuenta; aquí solo se intenta por si acaso.
        try { directRecord?.stop() } catch (_: Throwable) {}
        try { directRecord?.release() } catch (_: Throwable) {}
        directRecord = null
    }
}
