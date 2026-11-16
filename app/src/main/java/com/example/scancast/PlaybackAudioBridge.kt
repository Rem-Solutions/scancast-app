package com.example.scancast

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import org.webrtc.audio.AudioRecordDataCallback
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import kotlin.math.max

/**
 * Audio interno del celular -> WebRTC, sin hacks de reflexión.
 *
 * WebRTC en Android solo sabe generar audio desde el micrófono: su hilo lee 10 ms del micrófono y
 * los manda al codificador. Con setAudioRecordDataCallback() WebRTC nos entrega ese mismo buffer
 * ANTES de enviarlo; aquí lo sobrescribimos con 10 ms de audio interno (AudioPlaybackCapture).
 * Lo que capte el micrófono se descarta, así que no hay eco, ni chillido, ni sonido ambiente.
 *
 * Solo se debe instanciar en Android 10+ (API 29), que es cuando existe AudioPlaybackCapture.
 */
@SuppressLint("NewApi")
class PlaybackAudioBridge(private val projection: MediaProjection) : AudioRecordDataCallback {

    /** 1.0 = sin cambios. Súbelo con cuidado (por encima de ~1.5 empieza a distorsionar). */
    @Volatile var gain: Float = 1.0f

    @Volatile private var released = false
    private var failed = false
    private var record: AudioRecord? = null
    private var scratch = ByteArray(0)

    fun createModule(context: Context): JavaAudioDeviceModule =
        JavaAudioDeviceModule.builder(context)
            .setSampleRate(48000)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioRecordDataCallback(this)
            .createAudioDeviceModule()

    // Se llama cada ~10 ms desde el hilo de grabación de WebRTC.
    override fun onAudioDataRecorded(
        audioFormat: Int,
        channelCount: Int,
        sampleRate: Int,
        audioBuffer: ByteBuffer
    ) {
        val total = audioBuffer.capacity()
        if (scratch.size != total) scratch = ByteArray(total)

        var got = 0
        val rec = ensureRecord(sampleRate, channelCount)
        if (rec != null) {
            while (got < total && !released) {
                val n = rec.read(scratch, got, total - got) // bloqueante: sincroniza con el reloj de audio
                if (n <= 0) break
                got += n
            }
        }
        if (got < total) java.util.Arrays.fill(scratch, got, total, 0.toByte()) // silencio si no hay audio

        applyGain(total)

        audioBuffer.clear()
        audioBuffer.put(scratch, 0, total)
        audioBuffer.clear()
    }

    /** El AudioRecord de reproducción se crea con el formato real que usa WebRTC y arranca en el primer buffer, para no acumular retraso. */
    @SuppressLint("MissingPermission")
    private fun ensureRecord(sampleRate: Int, channelCount: Int): AudioRecord? {
        record?.let { return it }
        if (failed || released) return null
        return try {
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
            val created = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(format)
                .setBufferSizeInBytes(max(minBuffer, bytes100ms))
                .build()
            created.startRecording()
            record = created
            created
        } catch (_: Throwable) {
            failed = true
            null
        }
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
        try { record?.stop() } catch (_: Throwable) {}
        try { record?.release() } catch (_: Throwable) {}
        record = null
    }
}
