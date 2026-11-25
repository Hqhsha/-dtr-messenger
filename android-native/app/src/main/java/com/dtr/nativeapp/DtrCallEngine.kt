package com.dtr.nativeapp

import android.content.Context
import org.json.JSONObject
import org.webrtc.*
import io.socket.client.Socket

/**
 * Native WebRTC transport for one-to-one DTR calls.
 * Signalling event names and payloads match the existing DTR web client/server.
 */
class DtrCallEngine(
    context: Context,
    private val socket: Socket,
    private val peerId: String,
    private val chatId: String,
    private val videoEnabled: Boolean,
    private val onStatus: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val egl = EglBase.create()
    val eglContext: EglBase.Context get() = egl.eglBaseContext

    private val factory: PeerConnectionFactory
    private val peer: PeerConnection
    private val audioSource: AudioSource
    private val audioTrack: AudioTrack
    private var videoSource: VideoSource? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var localVideo: VideoTrack? = null
    var remoteVideoTrack: VideoTrack? = null
        private set
    val localVideoTrack: VideoTrack? get() = localVideo
    var micEnabled: Boolean = true
        private set
    var cameraEnabled: Boolean = videoEnabled
        private set
    private var disposed = false

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .setEnableInternalTracer(false).createInitializationOptions()
        )
        val audioModule = JavaAudioDeviceModule.builder(appContext).createAudioDeviceModule()
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioModule)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
        audioSource = factory.createAudioSource(MediaConstraints())
        audioTrack = factory.createAudioTrack("dtr-audio-$chatId", audioSource)

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer()
        )
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        peer = factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> onStatus("active")
                    PeerConnection.IceConnectionState.FAILED -> onStatus("failed")
                    PeerConnection.IceConnectionState.DISCONNECTED -> onStatus("connecting")
                    else -> Unit
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate ?: return
                socket.emit("call:ice", JSONObject()
                    .put("to", peerId).put("chatId", chatId)
                    .put("candidate", JSONObject()
                        .put("candidate", candidate.sdp)
                        .put("sdpMid", candidate.sdpMid)
                        .put("sdpMLineIndex", candidate.sdpMLineIndex)))
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {
                stream?.videoTracks?.firstOrNull()?.let { remoteVideoTrack = it }
            }
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                (receiver?.track() as? VideoTrack)?.let { remoteVideoTrack = it }
            }
            override fun onTrack(transceiver: RtpTransceiver?) {
                (transceiver?.receiver?.track() as? VideoTrack)?.let { remoteVideoTrack = it }
            }
        }) ?: throw IllegalStateException("Не удалось создать WebRTC-соединение")

        peer.addTrack(audioTrack, listOf("dtr-stream"))
        if (videoEnabled) startCamera()
    }

    private fun startCamera() {
        val enumerator = Camera2Enumerator(appContext)
        val cameraName = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull()
            ?: throw IllegalStateException("Камера не найдена")
        val capturer = enumerator.createCapturer(cameraName, null) as? CameraVideoCapturer
            ?: throw IllegalStateException("Не удалось открыть камеру")
        videoCapturer = capturer
        val helper = SurfaceTextureHelper.create("DTR-Camera", egl.eglBaseContext)
        surfaceTextureHelper = helper
        val source = factory.createVideoSource(capturer.isScreencast)
        videoSource = source
        capturer.initialize(helper, appContext, source.capturerObserver)
        capturer.startCapture(960, 540, 24)
        val track = factory.createVideoTrack("dtr-video-$chatId", source)
        localVideo = track
        peer.addTrack(track, listOf("dtr-stream"))
    }

    fun startOutgoing() {
        onStatus("calling")
        peer.createOffer(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription?) {
                if (description == null) { onStatus("failed"); return }
                peer.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        socket.emit("call:offer", JSONObject()
                            .put("to", peerId).put("chatId", chatId)
                            .put("video", videoEnabled)
                            .put("offer", JSONObject().put("type", description.type.canonicalForm()).put("sdp", description.description)))
                    }
                    override fun onSetFailure(error: String?) { onStatus("failed") }
                    override fun onCreateSuccess(value: SessionDescription?) {}
                    override fun onCreateFailure(error: String?) {}
                }, description)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) { onStatus("failed") }
            override fun onSetFailure(error: String?) { onStatus("failed") }
        }, MediaConstraints())
    }

    fun acceptIncoming(offer: JSONObject) {
        val type = SessionDescription.Type.fromCanonicalForm(offer.optString("type", "offer"))
        val remote = SessionDescription(type, offer.optString("sdp"))
        peer.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                peer.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(answer: SessionDescription?) {
                        if (answer == null) { onStatus("failed"); return }
                        peer.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                socket.emit("call:answer", JSONObject()
                                    .put("to", peerId).put("chatId", chatId)
                                    .put("answer", JSONObject().put("type", answer.type.canonicalForm()).put("sdp", answer.description)))
                                onStatus("connecting")
                            }
                            override fun onSetFailure(error: String?) { onStatus("failed") }
                            override fun onCreateSuccess(value: SessionDescription?) {}
                            override fun onCreateFailure(error: String?) {}
                        }, answer)
                    }
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(error: String?) { onStatus("failed") }
                    override fun onSetFailure(error: String?) { onStatus("failed") }
                }, MediaConstraints())
            }
            override fun onSetFailure(error: String?) { onStatus("failed") }
            override fun onCreateSuccess(value: SessionDescription?) {}
            override fun onCreateFailure(error: String?) {}
        }, remote)
    }

    fun onAnswer(answer: JSONObject) {
        val type = SessionDescription.Type.fromCanonicalForm(answer.optString("type", "answer"))
        peer.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() { onStatus("connecting") }
            override fun onSetFailure(error: String?) { onStatus("failed") }
            override fun onCreateSuccess(value: SessionDescription?) {}
            override fun onCreateFailure(error: String?) {}
        }, SessionDescription(type, answer.optString("sdp")))
    }

    fun onIce(data: JSONObject) {
        val candidate = data.optJSONObject("candidate") ?: data
        val sdp = candidate.optString("candidate")
        if (sdp.isBlank()) return
        val mid = if (candidate.isNull("sdpMid")) null else candidate.optString("sdpMid")
        val line = candidate.optInt("sdpMLineIndex", 0)
        peer.addIceCandidate(IceCandidate(mid, line, sdp))
    }

    fun setMicEnabled(enabled: Boolean) {
        micEnabled = enabled
        audioTrack.setEnabled(enabled)
    }

    fun setCameraEnabled(enabled: Boolean) {
        cameraEnabled = enabled
        localVideo?.setEnabled(enabled)
    }

    fun switchCamera() {
        videoCapturer?.switchCamera(null)
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        runCatching { videoCapturer?.stopCapture() }
        runCatching { videoCapturer?.dispose() }
        runCatching { surfaceTextureHelper?.dispose() }
        runCatching { peer.close() }
        runCatching { peer.dispose() }
        runCatching { audioTrack.dispose() }
        runCatching { audioSource.dispose() }
        runCatching { localVideo?.dispose() }
        runCatching { videoSource?.dispose() }
        runCatching { factory.dispose() }
        runCatching { egl.release() }
    }
}
