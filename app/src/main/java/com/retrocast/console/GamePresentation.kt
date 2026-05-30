package com.retrocast.console

import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.view.Display
import android.view.View
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

    private class GameView(context: Context) : View(context) {
        private var frameBitmap: Bitmap? = null
        private val paint = Paint().apply { isFilterBitmap = false }
        private val destRect = Rect()

        fun setFrame(pixels: ByteBuffer, width: Int, height: Int) {
            val bitmap = frameBitmap
            if (bitmap == null || bitmap.width != width || bitmap.height != height) {
                frameBitmap?.recycle()
                frameBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            }
            pixels.rewind()
            frameBitmap!!.copyPixelsFromBuffer(pixels)
            postInvalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val bitmap = frameBitmap ?: return
            val w = width; val h = height
            val bw = bitmap.width; val bh = bitmap.height
            if (w <= 0 || h <= 0 || bw <= 0 || bh <= 0) return

            val scale = Math.min(w / bw, h / bh)
            val dw = bw * scale; val dh = bh * scale
            val dx = (w - dw) / 2; val dy = (h - dh) / 2
            destRect.set(dx, dy, dx + dw, dy + dh)
            canvas.drawBitmap(bitmap, null, destRect, paint)
        }
    }
}
