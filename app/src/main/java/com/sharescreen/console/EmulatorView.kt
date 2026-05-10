package com.sharescreen.console

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.sharescreen.emulator.NativeRetro

class EmulatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    private var nativeRetro: NativeRetro? = null

    init {
        holder.addCallback(this)
    }

    fun setNativeRetro(retro: NativeRetro) {
        this.nativeRetro = retro
        // STAFF: CRITICAL - If the surface is already valid (e.g. after a Compose recomposition),
        // we MUST bind it immediately to the engine to avoid a black screen.
        if (holder.surface.isValid) {
            Log.d("EmulatorView", "Binding valid surface on setNativeRetro")
            retro.setSurface(holder.surface)
        }
    }

    /**
     * Enforce GBA 3:2 Aspect Ratio (240x160)
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)

        if (width <= 0 || height == 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        // Calculate aspect ratio: 3:2
        val targetWidth: Int
        val targetHeight: Int

        if (width.toFloat() / height > 1.5f) {
            targetHeight = height
            targetWidth = (height * 1.5f).toInt()
        } else {
            targetWidth = width
            targetHeight = (width / 1.5f).toInt()
        }

        setMeasuredDimension(targetWidth, targetHeight)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.d("EmulatorView", "surfaceCreated")
        nativeRetro?.setSurface(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.d("EmulatorView", "surfaceChanged")
        nativeRetro?.setSurface(holder.surface)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.d("EmulatorView", "surfaceDestroyed")
        // Note: We don't necessarily want to set null if we're just rotating
        // but we'll let the next surfaceCreated handle the bind.
    }
}
