package com.example.scancast

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

/**
 * Reemplazo de ScreenCapturerAndroid.
 *
 * Por qué existe: a partir de Android 14 un MediaProjection solo puede crear UN VirtualDisplay.
 * ScreenCapturerAndroid.changeCaptureFormat() (lo que se ejecuta al girar el celular) libera el
 * VirtualDisplay y crea otro; Android 14+ lo considera un abuso, cierra la proyección y la
 * transmisión se corta. Aquí el VirtualDisplay se crea una sola vez y al girar solo se
 * redimensiona con VirtualDisplay.resize().
 *
 * También expone el MediaProjection (prepare()) ANTES de crear la fábrica de WebRTC, porque la
 * captura de audio interno lo necesita.
 */
class ResizableScreenCapturer(
    private val resultData: Intent,
    private val onProjectionStopped: () -> Unit
) : VideoCapturer, VideoSink {

    private companion object {
        const val DPI = 400
        const val FLAGS = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            onProjectionStopped()
        }
    }

    @Volatile
    var mediaProjection: MediaProjection? = null
        private set

    private var helper: SurfaceTextureHelper? = null
    private var observer: CapturerObserver? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var width = 0
    private var height = 0
    private var disposed = false

    /** Obtiene el MediaProjection (una sola vez) y registra el callback obligatorio en Android 14+. */
    @Synchronized
    fun prepare(context: Context) {
        if (mediaProjection != null) return
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = manager.getMediaProjection(Activity.RESULT_OK, resultData)
            ?: throw IllegalStateException("No se pudo obtener la captura de pantalla")
        projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
        mediaProjection = projection
    }

    override fun initialize(
        surfaceTextureHelper: SurfaceTextureHelper?,
        applicationContext: Context?,
        capturerObserver: CapturerObserver?
    ) {
        helper = surfaceTextureHelper
        observer = capturerObserver
    }

    @Synchronized
    override fun startCapture(width: Int, height: Int, framerate: Int) {
        check(!disposed) { "El capturador ya fue liberado" }
        val projection = mediaProjection ?: throw IllegalStateException("Falta llamar a prepare()")
        val textureHelper = helper ?: throw IllegalStateException("Falta llamar a initialize()")
        this.width = width
        this.height = height
        textureHelper.setTextureSize(width, height)
        virtualDisplay = projection.createVirtualDisplay(
            "ScanCast", width, height, DPI, FLAGS,
            Surface(textureHelper.surfaceTexture), null, null
        )
        observer?.onCapturerStarted(true)
        textureHelper.startListening(this)
    }

    /** Cambia el tamaño al girar el celular, SIN crear otro VirtualDisplay. */
    @Synchronized
    fun resize(newWidth: Int, newHeight: Int) {
        val display = virtualDisplay ?: return
        if (disposed || newWidth <= 0 || newHeight <= 0) return
        if (newWidth == width && newHeight == height) return
        width = newWidth
        height = newHeight
        helper?.setTextureSize(newWidth, newHeight)
        display.resize(newWidth, newHeight, DPI)
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        resize(width, height)
    }

    @Synchronized
    override fun stopCapture() {
        try { helper?.stopListening() } catch (_: Throwable) {}
        try { observer?.onCapturerStopped() } catch (_: Throwable) {}
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        virtualDisplay = null
        mediaProjection?.let {
            try { it.unregisterCallback(projectionCallback) } catch (_: Throwable) {}
            try { it.stop() } catch (_: Throwable) {}
        }
        mediaProjection = null
    }

    @Synchronized
    override fun dispose() {
        disposed = true
    }

    override fun isScreencast(): Boolean = true

    // Frames que llegan desde el SurfaceTexture
    override fun onFrame(frame: VideoFrame?) {
        if (frame != null) observer?.onFrameCaptured(frame)
    }
}
