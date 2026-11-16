package com.example.scancast

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpSender
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random

/** Configuración. Debe coincidir con la web receptora. */
object ScanCastConfig {
    const val PEER_PREFIX = "pantalla-"
    const val PEER_HOST = "0.peerjs.com"
    const val PEER_KEY = "peerjs"

    val iceServers: List<PeerConnection.IceServer> = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun3.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun4.l.google.com:19302").createIceServer(),
    )
}

private open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}

private fun randomToken(length: Int): String {
    val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
    return buildString { repeat(length) { append(chars[Random.nextInt(chars.length)]) } }
}

class PeerJsSender(
    private val factory: PeerConnectionFactory,
    private val videoTrack: VideoTrack,
    private val audioTrack: AudioTrack?,
    room: String,
    private val onStatus: (ShareStatus, String) -> Unit,
    private val onFatal: (String) -> Unit
) {
    private companion object {
        const val KIND_MEDIA = "media"
        const val KIND_DATA = "data"
        const val MAX_TRIES = 6
        const val MAX_VIDEO_BITRATE_BPS = 8_000_000 // 8 Mbps estables para 1080p sin delay ni parones
        const val MAX_AUDIO_BITRATE_BPS = 128_000   // 128 kbps alta calidad estéreo para música y YouTube
    }

    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    private val target = ScanCastConfig.PEER_PREFIX + room
    private var myId = randomToken(16)
    private val token = randomToken(12)

    private var ws: WebSocket? = null
    private var socketOpen = false
    private var socketRetries = 0

    private var media: Link? = null
    private var data: Link? = null

    private var everConnected = false
    private var tries = 0
    private var retryRunnable: Runnable? = null
    private var stopping = false
    private var finished = false

    private val heartbeat = object : Runnable {
        override fun run() {
            if (finished) return
            ws?.send("""{"type":"HEARTBEAT"}""")
            main.postDelayed(this, 5000)
        }
    }

    fun start() {
        connectSocket()
    }

    fun stop(onDone: () -> Unit) {
        if (stopping || finished) return
        stopping = true
        cancelRetry()
        data?.sendBye()
        main.postDelayed({
            shutdownNow()
            onDone()
        }, 300)
    }

    fun shutdownNow() {
        if (finished) return
        finished = true
        stopping = true
        cancelRetry()
        main.removeCallbacks(heartbeat)
        closeLinks()
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
        ws = null
        http.dispatcher.executorService.shutdown()
    }

    private fun connectSocket() {
        if (finished || stopping) return
        socketOpen = false
        val url = "wss://${ScanCastConfig.PEER_HOST}/peerjs" +
            "?key=${ScanCastConfig.PEER_KEY}&id=$myId&token=$token"
        val socket = http.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    main.post { handleMessage(webSocket, text) }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    main.post { socketLost(webSocket) }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    main.post { socketLost(webSocket) }
                }
            }
        )
        ws = socket
    }

    private fun socketLost(socket: WebSocket) {
        if (finished || stopping || socket !== ws) return
        socketOpen = false
        main.removeCallbacks(heartbeat)
        if (!everConnected) onStatus(ShareStatus.CONNECTING, "Conectando…")
        socketRetries++
        main.postDelayed({ connectSocket() }, min(1000L * socketRetries, 8000L))
    }

    private fun handleMessage(socket: WebSocket, text: String) {
        if (finished || stopping || socket !== ws) return
        val msg = try { JSONObject(text) } catch (_: Exception) { return }
        when (msg.optString("type")) {
            "OPEN" -> {
                socketOpen = true
                socketRetries = 0
                main.removeCallbacks(heartbeat)
                main.postDelayed(heartbeat, 5000)
                connectLinks()
            }
            "ID-TAKEN" -> {
                myId = randomToken(16)
                try { ws?.close(1000, null) } catch (_: Exception) {}
                ws = null
                connectSocket()
            }
            "INVALID-KEY", "ERROR" -> {
                fail("Error del servidor de señalización: ${msg.optString("payload")}")
            }
            "EXPIRE" -> {
                if (msg.optString("src") != target) return
                if (!everConnected) fail("No se encontró la pantalla receptora. Revisa el código de sala.")
                else lost()
            }
            "LEAVE" -> {
                if (msg.optString("src") == target && everConnected) lost()
            }
            "ANSWER" -> {
                val payload = msg.optJSONObject("payload") ?: return
                val link = findLink(payload.optString("connectionId")) ?: return
                val sdp = payload.optJSONObject("sdp")?.optString("sdp") ?: return
                link.onAnswer(sdp)
            }
            "CANDIDATE" -> {
                val payload = msg.optJSONObject("payload") ?: return
                val link = findLink(payload.optString("connectionId")) ?: return
                val cand = payload.optJSONObject("candidate") ?: return
                link.onRemoteCandidate(cand)
            }
        }
    }

    private fun send(json: JSONObject) {
        ws?.send(json.toString())
    }

    private fun fail(message: String) {
        if (finished || stopping) return
        onFatal(message)
    }

    private fun findLink(connectionId: String): Link? =
        listOfNotNull(media, data).firstOrNull { it.connectionId == connectionId }

    private fun connectLinks() {
        if (finished || stopping) return
        cancelRetry()
        closeLinks()
        media = Link("mc_" + randomToken(10), KIND_MEDIA).also { it.start() }
        data = Link("dc_" + randomToken(10), KIND_DATA).also { it.start() }
    }

    private fun closeLinks() {
        val old = listOfNotNull(media, data)
        media = null
        data = null
        old.forEach { it.close() }
    }

    private fun cancelRetry() {
        retryRunnable?.let { main.removeCallbacks(it) }
        retryRunnable = null
    }

    private fun lost() {
        if (finished || stopping || retryRunnable != null) return
        onStatus(ShareStatus.RECONNECTING, "Reconectando…")
        val runnable = Runnable {
            retryRunnable = null
            if (finished || stopping) return@Runnable
            if (tries >= MAX_TRIES) {
                fail("Se perdió la conexión con la pantalla receptora.")
                return@Runnable
            }
            tries++
            if (socketOpen) connectLinks()
        }
        retryRunnable = runnable
        main.postDelayed(runnable, min(1000L * (tries + 1), 6000L))
    }

    private inner class Link(val connectionId: String, val kind: String) {
        private var pc: PeerConnection? = null
        private var dc: DataChannel? = null
        private var isClosed = false
        private var offerSent = false
        private var remoteSet = false
        private val pendingLocal = mutableListOf<IceCandidate>()
        private val pendingRemote = mutableListOf<IceCandidate>()

        private val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                main.post { onIce(state) }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate != null) main.post { onLocalCandidate(candidate) }
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
        }

        fun start() {
            val config = PeerConnection.RTCConfiguration(ScanCastConfig.iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }
            val connection = factory.createPeerConnection(config, observer)
            if (connection == null) {
                fail("No se pudo crear la conexión WebRTC.")
                return
            }
            pc = connection

            if (kind == KIND_MEDIA) {
                val videoSender = connection.addTrack(videoTrack, listOf("screen"))
                if (videoSender != null) {
                    tuneVideo(videoSender)
                }
                if (audioTrack != null) {
                    val audioSender = connection.addTrack(audioTrack, listOf("screen"))
                    if (audioSender != null) {
                        tuneAudio(audioSender)
                    }
                }
            } else {
                dc = connection.createDataChannel(connectionId, DataChannel.Init().apply { ordered = true })
            }

            connection.createOffer(object : SimpleSdpObserver() {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    val offer = desc ?: return
                    main.post {
                        if (isClosed) return@post
                        val modifiedSdp = if (kind == KIND_MEDIA) optimizeSdpForMedia(offer.description) else offer.description
                        val finalOffer = SessionDescription(offer.type, modifiedSdp)

                        connection.setLocalDescription(object : SimpleSdpObserver() {
                            override fun onSetSuccess() {
                                main.post { if (!isClosed) sendOffer(finalOffer) }
                            }
                            override fun onSetFailure(error: String?) {
                                main.post { if (!isClosed && kind == KIND_MEDIA) lost() }
                            }
                        }, finalOffer)
                    }
                }

                override fun onCreateFailure(error: String?) {
                    main.post { if (!isClosed && kind == KIND_MEDIA) lost() }
                }
            }, MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            })
        }

        private fun tuneVideo(sender: RtpSender) {
            try {
                val params = sender.parameters
                for (encoding in params.encodings) {
                    encoding.maxBitrateBps = MAX_VIDEO_BITRATE_BPS
                    encoding.maxFramerate = 30
                }
                sender.setParameters(params)
            } catch (_: Exception) {
            }
        }

        private fun tuneAudio(sender: RtpSender) {
            try {
                val params = sender.parameters
                for (encoding in params.encodings) {
                    encoding.maxBitrateBps = MAX_AUDIO_BITRATE_BPS
                }
                sender.setParameters(params)
            } catch (_: Exception) {
            }
        }

        private fun optimizeSdpForMedia(sdp: String): String {
            return sdp.replace("useinbandfec=1", "useinbandfec=1; stereo=1; maxaveragebitrate=128000")
        }

        private fun sendOffer(offer: SessionDescription) {
            val sdp = JSONObject().put("type", "offer").put("sdp", offer.description)
            val payload = JSONObject()
                .put("sdp", sdp)
                .put("type", kind)
                .put("connectionId", connectionId)
                .put("browser", "chrome")
            if (kind == KIND_DATA) {
                payload.put("label", connectionId)
                    .put("reliable", true)
                    .put("serialization", "raw")
            }
            send(JSONObject().put("type", "OFFER").put("payload", payload).put("dst", target))
            offerSent = true
            pendingLocal.forEach { sendCandidate(it) }
            pendingLocal.clear()
        }

        private fun onLocalCandidate(candidate: IceCandidate) {
            if (isClosed) return
            if (offerSent) sendCandidate(candidate) else pendingLocal.add(candidate)
        }

        private fun sendCandidate(candidate: IceCandidate) {
            val cand = JSONObject()
                .put("candidate", candidate.sdp)
                .put("sdpMid", candidate.sdpMid)
                .put("sdpMLineIndex", candidate.sdpMLineIndex)
            val payload = JSONObject()
                .put("candidate", cand)
                .put("type", kind)
                .put("connectionId", connectionId)
            send(JSONObject().put("type", "CANDIDATE").put("payload", payload).put("dst", target))
        }

        fun onAnswer(sdp: String) {
            val connection = pc ?: return
            if (isClosed) return
            connection.setRemoteDescription(object : SimpleSdpObserver() {
                override fun onSetSuccess() {
                    main.post {
                        if (isClosed) return@post
                        remoteSet = true
                        pendingRemote.forEach { connection.addIceCandidate(it) }
                        pendingRemote.clear()
                    }
                }
                override fun onSetFailure(error: String?) {
                    main.post { if (!isClosed && kind == KIND_MEDIA) lost() }
                }
            }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
        }

        fun onRemoteCandidate(json: JSONObject) {
            val line = json.optString("candidate")
            if (line.isNullOrBlank() || isClosed) return
            val candidate = IceCandidate(
                json.optString("sdpMid"),
                json.optInt("sdpMLineIndex", 0),
                line
            )
            if (remoteSet) pc?.addIceCandidate(candidate) else pendingRemote.add(candidate)
        }

        private fun onIce(state: PeerConnection.IceConnectionState?) {
            if (isClosed || kind != KIND_MEDIA || media !== this) return
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> {
                    everConnected = true
                    tries = 0
                    cancelRetry()
                    onStatus(ShareStatus.STREAMING, "")
                }
                PeerConnection.IceConnectionState.DISCONNECTED -> {
                    onStatus(ShareStatus.RECONNECTING, "Conexión inestable…")
                    main.postDelayed({
                        if (!isClosed && media === this) {
                            val current = pc?.iceConnectionState()
                            if (current == PeerConnection.IceConnectionState.DISCONNECTED ||
                                current == PeerConnection.IceConnectionState.FAILED
                            ) lost()
                        }
                    }, 5000)
                }
                PeerConnection.IceConnectionState.FAILED -> lost()
                else -> {}
            }
        }

        fun sendBye() {
            val channel = dc ?: return
            try {
                if (channel.state() == DataChannel.State.OPEN) {
                    channel.send(DataChannel.Buffer(ByteBuffer.wrap("bye".toByteArray()), false))
                }
            } catch (_: Exception) {
            }
        }

        fun close() {
            if (isClosed) return
            isClosed = true
            try { dc?.close(); dc?.dispose() } catch (_: Exception) {}
            try { pc?.close(); pc?.dispose() } catch (_: Exception) {}
            dc = null
            pc = null
        }
    }
}
