package com.sharescreen.console

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display

class SplitDisplayManager(private val context: Context) {
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    enum class DisplayMode {
        LOCAL_ONLY,
        EXTERNAL_PRESENTATION,
        WIRELESS_STREAMING
    }

    fun getRecommendedMode(): DisplayMode {
        val displays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        return if (displays.isNotEmpty()) {
            DisplayMode.EXTERNAL_PRESENTATION
        } else {
            // Check if user has enabled wireless streaming (to be implemented)
            DisplayMode.LOCAL_ONLY
        }
    }

    fun getExternalDisplay(): Display? {
        val displays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        return if (displays.isNotEmpty()) displays[0] else null
    }
}
