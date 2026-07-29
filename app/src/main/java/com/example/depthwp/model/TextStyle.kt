package com.example.depthwp.model

import android.graphics.Color

/**
 * Everything about how one text layer (time or date) *looks* — font, colour, opacity and effects.
 * Time and date each own an independent [TextStyle], so they can be styled completely separately
 * (e.g. plain white date next to a glossy gradient clock). Layout (position/size/stretch) lives
 * on [WallpaperConfig] itself, not here.
 */
data class TextStyle(
    val fontFamily: String = FontCatalog.DEFAULT_FAMILY,
    val weight: Int = 300,
    val color: Int = Color.WHITE,
    val opacity: Float = 1.0f,
    val strokeEnabled: Boolean = false,
    val strokeWidthFrac: Float = 0.03f,
    val strokeColor: Int = Color.BLACK,
    val gradientEnabled: Boolean = false,
    val gradientTopColor: Int = Color.WHITE,
    val gradientBottomColor: Int = Color.WHITE,
    val gradientBottomAlpha: Float = 0.25f
)
