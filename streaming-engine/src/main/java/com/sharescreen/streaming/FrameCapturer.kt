package com.sharescreen.streaming

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import org.webrtc.VideoSource

class FrameCapturer(private val width: Int, private val height: Int) {
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null

    fun start() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 2000000) // 2 Mbps
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 60)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        // Ultra-low latency settings
        format.setInteger(MediaFormat.KEY_LATENCY, 0)
        format.setInteger(MediaFormat.KEY_PRIORITY, 0)

        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = encoder?.createInputSurface()
        encoder?.start()
    }

    fun getInputSurface(): Surface? = inputSurface

    fun stop() {
        encoder?.stop()
        encoder?.release()
        inputSurface?.release()
    }
}
