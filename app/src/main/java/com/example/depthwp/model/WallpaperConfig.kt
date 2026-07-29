package com.example.depthwp.model

import android.graphics.Color

/**
 * Immutable snapshot of everything the editor and the live wallpaper renderer need.
 * Positions/sizes are stored as fractions of the canvas so the same config renders
 * consistently on the editor preview and on the actual device screen, even though
 * both surfaces have different pixel dimensions.
 *
 * Time and date are independent text layers: each has its own position, size, stretch AND its own
 * [TextStyle] (font, colour, opacity, effects), so they can be styled completely separately.
 */
data class WallpaperConfig(
    val backgroundImagePath: String? = null,
    val foregroundImagePath: String? = null,

    // Time ("14:35")
    val timeXFrac: Float = 0.5f,
    val timeYFrac: Float = 0.34f,
    val timeSizeFrac: Float = 0.16f,
    val timeScaleX: Float = 1.0f,
    val timeScaleY: Float = 1.0f,
    val timeVisible: Boolean = true,
    val timeStyle: TextStyle = TextStyle(weight = 300),

    // Date ("Mittwoch, 23. Juli")
    val dateXFrac: Float = 0.5f,
    val dateYFrac: Float = 0.42f,
    val dateSizeFrac: Float = 0.052f,
    val dateScaleX: Float = 1.0f,
    val dateScaleY: Float = 1.0f,
    val dateVisible: Boolean = true,
    val dateStyle: TextStyle = TextStyle(weight = 500),

    val fgXFrac: Float = 0.5f,
    val fgYFrac: Float = 0.62f,
    val fgWidthFrac: Float = 0.55f,
    val bgScale: Float = 1.0f,
    /**
     * Blur strength for the photo layers, 0 = sharp .. 1 = maximum. Applies to the background and
     * the foreground object alike so the whole image stays visually consistent. Independent per
     * screen, since lock and home each carry their own config.
     */
    val bgBlur: Float = 0f
) {
    companion object {
        val DEFAULT = WallpaperConfig()

        const val TIME_SIZE_MIN = 0.06f
        const val TIME_SIZE_MAX = 0.30f
        const val DATE_SIZE_MIN = 0.02f
        const val DATE_SIZE_MAX = 0.14f
        const val TEXT_SCALE_MIN = 0.4f
        const val TEXT_SCALE_MAX = 4.0f
        const val OPACITY_MIN_PERCENT = 10f
        const val OPACITY_MAX_PERCENT = 100f
        const val FG_WIDTH_MIN = 0.10f
        const val FG_WIDTH_MAX = 1.6f
        const val POSITION_MIN = 0.02f
        const val POSITION_MAX = 0.98f
        const val BG_SCALE_MIN = 1.0f
        const val BG_SCALE_MAX = 3.0f
        const val BG_BLUR_MIN = 0f
        const val BG_BLUR_MAX = 1.0f
        const val WEIGHT_MIN = 100
        const val WEIGHT_MAX = 900
        const val STROKE_WIDTH_MIN = 0.0f
        const val STROKE_WIDTH_MAX = 0.12f

        val PRESET_COLORS = listOf(
            Color.WHITE,
            Color.BLACK,
            Color.parseColor("#FF6B6B"),
            Color.parseColor("#FFA94D"),
            Color.parseColor("#FFD43B"),
            Color.parseColor("#51CF66"),
            Color.parseColor("#339AF0"),
            Color.parseColor("#CC5DE8")
        )
    }
}
