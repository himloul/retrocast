package com.retrocast.console

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.ViewGroup
import android.widget.FrameLayout
import java.nio.ByteBuffer

class GamePresentation(
    outerContext: Context,
    display: Display
) : Presentation(outerContext, display) {

    private lateinit var gameView: GameView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gameView = GameView(context)
        setContentView(gameView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
    }

    fun setFrame(pixels: ByteBuffer, width: Int, height: Int) {
        gameView.setFrame(pixels, width, height)
    }

    private class GameView(context: Context) : EmulatorView(context)
}
