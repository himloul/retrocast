package com.retrocast.streaming

import android.content.Context
import android.util.Log
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer

class StreamingManager(private val context: Context) {
    companion object {
        private const val MAX_BITRATE_BPS = 20_000_000
        private const val MIN_BITRATE_BPS = 5_000_000
        private const val MAX_FRAMERATE = 60
        private const val MAX_AUDIO_BYTES = 16384

        private val initLock = Any()
        private var initialized = false

        private fun ensureInitialized(context: Context) {
            if (!initialized) {
                synchronized(initLock) {
                    if (!initialized) {
                        PeerConnectionFactory.initialize(
                            PeerConnectionFactory.InitializationOptions.builder(context)
                                .createInitializationOptions()
                        )
                        initialized = true
                    }
                }
            }
        }
    }

    private val rootEglBase: EglBase = EglBase.create()
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoSource: VideoSource? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    @Volatile
    private var audioDataChannel: DataChannel? = null

    @Volatile
    private var statsBitrateBps = 0
    @Volatile
    private var statsAudioSent = 0
    @Volatile
    private var lastStatsBytes = 0L
    @Volatile
    private var lastStatsTime = 0L

    fun getBitrateBps(): Int = statsBitrateBps
    fun getAudioSent(): Int = statsAudioSent

    fun resetStats() {
        statsBitrateBps = 0
        statsAudioSent = 0
        lastStatsBytes = 0L
        lastStatsTime = 0L
    }

    fun pollStats() {
        try {
            peerConnection?.getStats(object : StatsObserver {
                override fun onComplete(reports: Array<StatsReport>) {
                    try {
                        var totalBytes = 0L
                        for (r in reports) {
                            if (r.type == "ssrc") {
                                for (v in r.values) {
                                    if (v.name == "bytesSent") totalBytes += v.value.toLong()
                                }
                            }
                        }
                        val now = System.currentTimeMillis()
                        if (lastStatsTime > 0) {
                            val elapsed = (now - lastStatsTime) / 1000.0
                            if (elapsed >= 1.0) {
                                val bps = ((totalBytes - lastStatsBytes) * 8 / elapsed).toInt()
                                if (bps >= 0) statsBitrateBps = bps
                                Log.d("StreamingManager", "pollStats: totalBytes=%d, last=%d, elapsed=%.1fs, bps=%d".format(totalBytes, lastStatsBytes, elapsed, bps))
                                lastStatsBytes = totalBytes
                                lastStatsTime = now
                            }
                        } else {
                            Log.d("StreamingManager", "pollStats: first sample, totalBytes=%d".format(totalBytes))
                            lastStatsBytes = totalBytes
                            lastStatsTime = now
                        }
                    } catch (_: Exception) {}
                }
            }, null)
        } catch (_: Exception) {}
    }

    init {
        ensureInitialized(context)

        audioDeviceModule = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .createAudioDeviceModule()

        val factoryOptions = PeerConnectionFactory.Options()
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(factoryOptions)
            .setAudioDeviceModule(audioDeviceModule)
            .setVideoEncoderFactory(createEncoderFactory())
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(rootEglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    fun createPeerConnection(observer: PeerConnection.Observer) {
        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
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

        audioDataChannel = peerConnection?.createDataChannel("audio", DataChannel.Init().apply {
            ordered = false
            maxRetransmits = 0
        })
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

    private fun createEncoderFactory(): VideoEncoderFactory =
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
                        override fun onSetSuccess() { configureBitrate(); callback(it) }
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
        if (dc.state() != DataChannel.State.OPEN) return
        buffer.rewind()
        val len = samples * 2
        if (len > MAX_AUDIO_BYTES) return
        buffer.limit(len)
        statsAudioSent++
        dc.send(DataChannel.Buffer(buffer, true))
    }

    fun addIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    fun dispose() {
        audioDataChannel?.close()
        audioDataChannel = null
        peerConnection?.dispose()
        peerConnection = null
        videoSource?.dispose()
        audioDeviceModule?.release()
        peerConnectionFactory?.dispose()
        rootEglBase.release()
    }
}
