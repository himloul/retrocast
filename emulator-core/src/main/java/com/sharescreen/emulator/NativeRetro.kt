package com.sharescreen.emulator

import android.view.Surface
import java.nio.ByteBuffer

class NativeRetro {
    companion object {
        init {
            System.loadLibrary("emulator-core")
        }
    }

    interface FrameCallback {
        fun onFrameReady(pixels: ByteBuffer, width: Int, height: Int)
    }

    external fun getCoreVersion(): String

    external fun init(corePath: String)

    external fun loadGame(gamePath: String): Boolean

    external fun start()

    external fun stop()

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

    external fun setCallback(callback: FrameCallback?, pixels: ByteBuffer?)

    external fun setPaths(systemPath: String, savePath: String)

    external fun saveSram()
}
