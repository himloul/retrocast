package com.sharescreen.streaming

import android.content.Context
import org.webrtc.*
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule

class StreamingManager(private val context: Context) {
    private val rootEglBase: EglBase = EglBase.create()
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null

    init {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        audioDeviceModule = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .createAudioDeviceModule()

        val factoryOptions = PeerConnectionFactory.Options()
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(factoryOptions)
            .setAudioDeviceModule(audioDeviceModule)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(rootEglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(rootEglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    fun createPeerConnection(observer: PeerConnection.Observer) {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
        }
        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, observer)
    }

    fun startStreaming(capturer: LibretroVideoCapturer) {
        videoSource = peerConnectionFactory?.createVideoSource(capturer.isScreencast)
        capturer.initialize(null, context, videoSource?.capturerObserver)
        val videoTrack = peerConnectionFactory?.createVideoTrack("VIDEO_TRACK", videoSource)
        
        audioSource = peerConnectionFactory?.createAudioSource(MediaConstraints())
        val audioTrack = peerConnectionFactory?.createAudioTrack("AUDIO_TRACK", audioSource)

        videoTrack?.setEnabled(true)

        peerConnection?.addTrack(videoTrack, listOf("STREAM"))
        peerConnection?.addTrack(audioTrack, listOf("STREAM"))
    }

    private fun mangleSdp(sdp: String): String {
        return sdp.replace("useinbandfec=1", "useinbandfec=1;x-google-min-bitrate=5000;x-google-max-bitrate=10000;x-google-start-bitrate=8000")
            .replace("a=fmtp:111", "a=fmtp:111 minptime=10;useinbandfec=1")
    }

    fun createOffer(callback: (SessionDescription?) -> Unit) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            optional.add(MediaConstraints.KeyValuePair("googCpuOveruseDetection", "false"))
        }
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp?.let {
                    val mangledSdp = SessionDescription(it.type, mangleSdp(it.description))
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetSuccess() { callback(mangledSdp) }
                        override fun onCreateFailure(p0: String?) {}
                        override fun onSetFailure(p0: String?) { callback(null) }
                    }, mangledSdp)
                }
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) { callback(null) }
            override fun onSetFailure(p0: String?) { callback(null) }
        }, constraints)
    }

    fun setRemoteDescription(sdp: SessionDescription, callback: (Boolean) -> Unit) {
        val mangledSdp = SessionDescription(sdp.type, mangleSdp(sdp.description))
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() { callback(true) }
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) { callback(false) }
        }, mangledSdp)
    }

    fun addIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    fun dispose() {
        peerConnection?.dispose()
        videoSource?.dispose()
        audioSource?.dispose()
        audioDeviceModule?.release()
        peerConnectionFactory?.dispose()
        rootEglBase.release()
    }
}
