package com.dtr.nativeapp

import android.content.Context
import android.media.AudioManager
import org.json.JSONObject
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import io.socket.client.Socket

class DtrCallEngine(
    private val context: Context,
    private val socket: Socket,
    private val peerId: String,
    private val chatId: String,
    private val video: Boolean,
    private val onStatus: (String) -> Unit
) {
    private val egl = EglBase.create()
    val eglContext: EglBase.Context get() = egl.eglBaseContext
    var localVideoTrack by androidx.compose.runtime.mutableStateOf<VideoTrack?>(null)
        private set
    var remoteVideoTrack by androidx.compose.runtime.mutableStateOf<VideoTrack?>(null)
        private set
    var micEnabled by androidx.compose.runtime.mutableStateOf(true)
        private set
    var cameraEnabled by androidx.compose.runtime.mutableStateOf(video)
        private set
    private var factory: PeerConnectionFactory
    private var peer: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var capturer: CameraVideoCapturer? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private val pendingIce = mutableListOf<IceCandidate>()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val oldAudioMode = audioManager.mode
    private val oldSpeaker = audioManager.isSpeakerphoneOn

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
        )
        val adm = JavaAudioDeviceModule.builder(context).createAudioDeviceModule()
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
        adm.release()
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = true
    }

    private fun createPeer(): PeerConnection {
        val ice = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
        val config = PeerConnection.RTCConfiguration(ice).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> onStatus("active")
                    PeerConnection.IceConnectionState.FAILED -> onStatus("failed")
                    PeerConnection.IceConnectionState.DISCONNECTED -> onStatus("connecting")
                    else -> {}
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate ?: return
                socket.emit("call:ice", JSONObject()
                    .put("to", peerId).put("chatId", chatId)
                    .put("candidate", JSONObject()
                        .put("sdpMid", candidate.sdpMid)
                        .put("sdpMLineIndex", candidate.sdpMLineIndex)
                        .put("candidate", candidate.sdp)))
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                val track = receiver?.track()
                if (track is VideoTrack) remoteVideoTrack = track
            }
            override fun onTrack(transceiver: RtpTransceiver?) {
                val track = transceiver?.receiver?.track()
                if (track is VideoTrack) remoteVideoTrack = track
            }
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                if (newState == PeerConnection.PeerConnectionState.FAILED) onStatus("failed")
                if (newState == PeerConnection.PeerConnectionState.CONNECTED) onStatus("active")
            }
        }
        return factory.createPeerConnection(config, observer)
            ?: throw IllegalStateException("Не удалось создать WebRTC-соединение")
    }

    private fun createLocalTracks() {
        val audioSrc = factory.createAudioSource(MediaConstraints())
        audioSource = audioSrc
        val audio = factory.createAudioTrack("DTR_AUDIO", audioSrc)
        audioTrack = audio
        peer?.addTrack(audio, listOf("DTR_STREAM"))
        if (!video) return
        val enumerator = Camera2Enumerator(context)
        val cameraName = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull()
            ?: throw IllegalStateException("Камера не найдена")
        capturer = enumerator.createCapturer(cameraName, null)
            ?: throw IllegalStateException("Не удалось открыть камеру")
        val source = factory.createVideoSource(false)
        videoSource = source
        val helper = SurfaceTextureHelper.create("DTR-Camera", egl.eglBaseContext)
        textureHelper = helper
        val activeCapturer = capturer ?: throw IllegalStateException("Камера не инициализирована")
        activeCapturer.initialize(helper, context, source.capturerObserver)
        activeCapturer.startCapture(1280, 720, 30)
        val track = factory.createVideoTrack("DTR_VIDEO", source)
        localVideoTrack = track
        peer?.addTrack(track, listOf("DTR_STREAM"))
    }

    fun startOutgoing() {
        peer = createPeer()
        createLocalTracks()
        peer?.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(description: SessionDescription?) {
                if (description == null) { onStatus("failed"); return }
                peer?.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        socket.emit("call:offer", JSONObject()
                            .put("to", peerId).put("chatId", chatId).put("video", video)
                            .put("offer", JSONObject().put("type", "offer").put("sdp", description.description)))
                        onStatus("calling")
                    }
                    override fun onSetFailure(error: String?) { onStatus("failed") }
                }, description)
            }
            override fun onCreateFailure(error: String?) { onStatus("failed") }
        }, MediaConstraints())
    }

    fun acceptIncoming(offer: JSONObject) {
        peer = createPeer()
        createLocalTracks()
        val sdp = SessionDescription(SessionDescription.Type.OFFER, offer.optString("sdp"))
        peer?.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                flushIce()
                peer?.createAnswer(object : SimpleSdpObserver() {
                    override fun onCreateSuccess(description: SessionDescription?) {
                        if (description == null) { onStatus("failed"); return }
                        peer?.setLocalDescription(SimpleSdpObserver(), description)
                        socket.emit("call:answer", JSONObject()
                            .put("to", peerId).put("chatId", chatId)
                            .put("answer", JSONObject().put("type", "answer").put("sdp", description.description)))
                        onStatus("connecting")
                    }
                    override fun onCreateFailure(error: String?) { onStatus("failed") }
                }, MediaConstraints())
            }
            override fun onSetFailure(error: String?) { onStatus("failed") }
        }, sdp)
    }

    fun onAnswer(answer: JSONObject) {
        val sdp = SessionDescription(SessionDescription.Type.ANSWER, answer.optString("sdp"))
        peer?.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() { flushIce(); onStatus("connecting") }
            override fun onSetFailure(error: String?) { onStatus("failed") }
        }, sdp)
    }

    fun onIce(data: JSONObject) {
        val candidate = data.optJSONObject("candidate") ?: data
        val ice = IceCandidate(
            candidate.optString("sdpMid").takeIf { it != "null" },
            candidate.optInt("sdpMLineIndex"),
            candidate.optString("candidate")
        )
        if (peer?.remoteDescription != null) peer?.addIceCandidate(ice) else pendingIce.add(ice)
    }

    private fun flushIce() {
        pendingIce.toList().forEach { peer?.addIceCandidate(it) }
        pendingIce.clear()
    }

    fun setMicEnabled(enabled: Boolean) {
        micEnabled = enabled
        audioTrack?.setEnabled(enabled)
    }

    fun setCameraEnabled(enabled: Boolean) {
        cameraEnabled = enabled
        localVideoTrack?.setEnabled(enabled)
    }

    fun switchCamera() {
        capturer?.switchCamera(null)
    }

    fun setSpeaker(enabled: Boolean) {
        audioManager.isSpeakerphoneOn = enabled
    }

    fun dispose() {
        runCatching { capturer?.stopCapture() }
        runCatching { capturer?.dispose() }
        localVideoTrack?.dispose()
        remoteVideoTrack?.dispose()
        audioTrack?.dispose()
        audioSource?.dispose()
        videoSource?.dispose()
        textureHelper?.dispose()
        peer?.close()
        peer?.dispose()
        factory.dispose()
        egl.release()
        audioManager.isSpeakerphoneOn = oldSpeaker
        audioManager.mode = oldAudioMode
    }
}

open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(description: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}
