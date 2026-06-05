package com.retrocast.console

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.SurfaceView
import java.nio.ByteBuffer

open class EmulatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr) {
    private var frameBitmap: Bitmap? = null
    private val paint = Paint().apply { isFilterBitmap = false }
    private val destRect = Rect()
    private var showStats = false
    private val fpsCounter = FpsCounter()

    private val overlayBg = Paint().apply { color = 0x80000000.toInt() }
    private val overlayText = Paint().apply {
        color = Color.WHITE
        textSize = 36f
        isAntiAlias = true
        typeface = Typeface.MONOSPACE
    }

    fun setShowStats(enabled: Boolean) { showStats = enabled }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            MeasureSpec.getSize(heightMeasureSpec)
        )
    }

    fun setFrame(pixels: ByteBuffer, width: Int, height: Int) {
        val bmp = frameBitmap
        if (bmp == null || bmp.width != width || bmp.height != height) {
            frameBitmap?.recycle()
            frameBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
        pixels.rewind()
        val ready = frameBitmap!!
        ready.copyPixelsFromBuffer(pixels)
        val surface = holder.surface
        if (surface != null && surface.isValid) {
            try {
                val canvas = surface.lockCanvas(null)
                if (canvas != null) {
                    val vw = canvas.width; val vh = canvas.height
                    val bw = ready.width; val bh = ready.height
                    val scale = Math.min(vw.toFloat() / bw, vh.toFloat() / bh)
                    val dw = (bw * scale).toInt(); val dh = (bh * scale).toInt()
                    val dx = (vw - dw) / 2; val dy = (vh - dh) / 2
                    destRect.set(dx, dy, dx + dw, dy + dh)
                    canvas.drawBitmap(ready, null, destRect, paint)
                    if (showStats) {
                        val fps = fpsCounter.update()
                        canvas.drawRoundRect(8f, 8f, 160f, 52f, 8f, 8f, overlayBg)
                        canvas.drawText("FPS: %.0f".format(fps), 18f, 40f, overlayText)
                    }
                    try { surface.unlockCanvasAndPost(canvas) } catch (_: Exception) {}
                }
            } catch (_: Exception) { }
        }
    }
}
