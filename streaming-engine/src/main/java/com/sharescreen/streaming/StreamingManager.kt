package com.sharescreen.streaming

import android.content.Context
import android.util.Log
import org.webrtc.*
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer

class StreamingManager(private val context: Context) {
    private val rootEglBase: EglBase = EglBase.create()
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null
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

        videoTrack?.setEnabled(true)

        peerConnection?.addTrack(videoTrack, listOf("STREAM"))
        configureBitrate()

        audioDataChannel = peerConnection?.createDataChannel("audio", DataChannel.Init())
    }

    private fun configureBitrate() {
        peerConnection?.getSenders()?.forEach { sender ->
            if (sender.track()?.kind() == "video") {
                try {
                    val params = sender.parameters
                    params.encodings?.forEach { encoding ->
                        encoding.maxBitrateBps = 4000000
                        encoding.minBitrateBps = 512000
                        encoding.maxFramerate = 60
                    }
                    sender.parameters = params
                } catch (e: Exception) {
                    Log.w("StreamingManager", "Failed to configure bitrate: ${e.message}")
                }
            }
        }
    }

    private fun createH264EncoderFactory(): VideoEncoderFactory {
        val defaultFactory = DefaultVideoEncoderFactory(rootEglBase.eglBaseContext, true, true)
        return try {
            val h264Codecs = defaultFactory.getSupportedCodecs().filter { it.name == "H264" }
            if (h264Codecs.isNotEmpty()) {
                object : VideoEncoderFactory {
                    override fun createEncoder(info: VideoCodecInfo): VideoEncoder? =
                        defaultFactory.createEncoder(info)
                    override fun getSupportedCodecs(): Array<VideoCodecInfo> =
                        h264Codecs.toTypedArray()
                }
            } else defaultFactory
        } catch (e: Exception) {
            defaultFactory
        }
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
        val copy = ByteBuffer.allocateDirect(len)
        buffer.limit(len)
        copy.put(buffer)
        copy.flip()
        if (!dc.send(DataChannel.Buffer(copy, true))) {
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
        audioSource?.dispose()
        audioDeviceModule?.release()
        peerConnectionFactory?.dispose()
        rootEglBase.release()
    }
}
