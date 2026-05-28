package com.sharescreen.emulator

import java.nio.ByteBuffer

class NativeRetro {
    companion object {
        init {
            System.loadLibrary("emulator-core")
        }
    }

    interface FrameCallback {
        fun onFrameReady(pixels: ByteBuffer, width: Int, height: Int, i420: ByteBuffer, yStride: Int, uvStride: Int)
    }

    interface AudioCallback {
        fun onAudioReady(buffer: ByteBuffer, samples: Int)
    }

    external fun getCoreVersion(): String

    external fun init(corePath: String)

    external fun loadGame(gamePath: String): Boolean

    external fun start()

    external fun stop()

    external fun unloadGame()

    external fun setLocalAudioMuted(muted: Boolean)

    external fun setInputState(state: Int)

    private var currentInputMask = 0

    fun setButton(id: Int, pressed: Boolean) {
        if (pressed) {
            currentInputMask = currentInputMask or (1 shl id)
        } else {
            currentInputMask = currentInputMask and (1 shl id).inv()
        }
        setInputState(currentInputMask)
    }

    external fun setCallback(callback: FrameCallback?, pixels: ByteBuffer?, i420: ByteBuffer?)

    external fun setAudioCallback(callback: AudioCallback?, buffer: ByteBuffer?)

    external fun setPaths(systemPath: String, savePath: String)

    external fun saveSram()
}
