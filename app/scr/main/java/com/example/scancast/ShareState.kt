package com.example.scancast

import android.os.Handler
import android.os.Looper

enum class ShareStatus { IDLE, CONNECTING, STREAMING, RECONNECTING, ERROR }

/** Estado global muy simple: el servicio lo actualiza y la Activity lo dibuja. */
object ShareState {
    @Volatile var status: ShareStatus = ShareStatus.IDLE
        private set
    @Volatile var message: String = ""
        private set

    /** La Activity lo asigna en onStart y lo limpia en onStop. Siempre se invoca en el hilo principal. */
    @Volatile var listener: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())

    fun update(newStatus: ShareStatus, newMessage: String = "") {
        status = newStatus
        message = newMessage
        main.post { listener?.invoke() }
    }
}
