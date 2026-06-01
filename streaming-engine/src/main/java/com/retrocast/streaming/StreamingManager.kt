package com.retrocast.streaming

import android.content.Context
import android.util.Log
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer

class StreamingManager(private val context: Context) {
    companion object {
        private const val STUN_URL = "stun:stun.l.google.com:19302"
        private const val MAX_BITRATE_BPS = 5_000_000
        private const val MIN_BITRATE_BPS = 1_000_000
        private const val MAX_FRAMERATE = 60
        private const val MAX_AUDIO_BYTES = 16384
    }

    private val rootEglBase: EglBase = EglBase.create()
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoSource: VideoSource? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private val audioBuf = ByteBuffer.allocateDirect(MAX_AUDIO_BYTES)
    @Volatile
    private var audioDataChannel: DataChannel? = null

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
            .setVideoEncoderFactory(createH264EncoderFactory())
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(rootEglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    fun createPeerConnection(observer: PeerConnection.Observer) {
        val iceServers = listOf(
            PeerConnection.IceServer.builder(STUN_URL).createIceServer()
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

        videoTrack?.setEnabled(true)

        peerConnection?.addTrack(videoTrack, listOf("STREAM"))
        configureBitrate()
        videoSource?.adaptOutputFormat(240, 160, MAX_FRAMERATE)

        audioDataChannel = peerConnection?.createDataChannel("audio", DataChannel.Init())
    }

    private fun configureBitrate() {
        peerConnection?.transceivers?.forEach { transceiver ->
            if (transceiver.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO) {
                val sender = transceiver.sender
                try {
                    val params = sender.parameters
                    params.encodings?.forEach { encoding ->
                        encoding.maxBitrateBps = MAX_BITRATE_BPS
                        encoding.minBitrateBps = MIN_BITRATE_BPS
                        encoding.maxFramerate = MAX_FRAMERATE
                        encoding.scaleResolutionDownBy = 1.0
                    }
                    sender.parameters = params
                } catch (e: Exception) {
                    Log.w("StreamingManager", "Failed to configure bitrate: ${e.message}")
                }
            }
        }
    }

    private fun createH264EncoderFactory(): VideoEncoderFactory =
        DefaultVideoEncoderFactory(rootEglBase.eglBaseContext, true, true)

    fun createOffer(callback: (SessionDescription?) -> Unit) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            optional.add(MediaConstraints.KeyValuePair("googCpuOveruseDetection", "false"))
        }
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp?.let {
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetSuccess() { callback(it) }
                        override fun onCreateFailure(p0: String?) {}
                        override fun onSetFailure(p0: String?) { callback(null) }
                    }, it)
                }
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) { callback(null) }
            override fun onSetFailure(p0: String?) { callback(null) }
        }, constraints)
    }

    fun setRemoteDescription(sdp: SessionDescription, callback: (Boolean) -> Unit) {
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() { callback(true) }
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) { callback(false) }
        }, sdp)
    }

    fun sendAudio(buffer: ByteBuffer, samples: Int) {
        val dc = audioDataChannel ?: return
        buffer.rewind()
        val len = samples * 2
        if (len > MAX_AUDIO_BYTES) return
        audioBuf.clear()
        buffer.limit(len)
        audioBuf.put(buffer)
        audioBuf.flip()
        if (!dc.send(DataChannel.Buffer(audioBuf, true))) {
            Log.w("StreamingManager", "audio DataChannel send returned false")
        }
    }

    fun addIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    fun dispose() {
        audioDataChannel?.close()
        audioDataChannel = null
        peerConnection?.dispose()
        videoSource?.dispose()
        audioDeviceModule?.release()
        peerConnectionFactory?.dispose()
        rootEglBase.release()
    }
}
