package com.retrocast.console

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
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
                    try { surface.unlockCanvasAndPost(canvas) } catch (_: Exception) {}
                }
            } catch (_: Exception) { }
        }
    }
}
