package com.example.depthwp.data

import android.content.Context
import android.content.SharedPreferences
import com.example.depthwp.model.TextStyle
import com.example.depthwp.model.WallpaperConfig

object ConfigRepository {

    const val PREFS_NAME = "depth_wallpaper_prefs"

    private const val KEY_BACKGROUND_PATH = "background_image_path"
    private const val KEY_FOREGROUND_PATH = "foreground_image_path"

    private const val KEY_TIME_X = "time_x_frac"
    private const val KEY_TIME_Y = "time_y_frac"
    private const val KEY_TIME_SIZE = "time_size_frac"
    private const val KEY_TIME_SCALE_X = "time_scale_x"
    private const val KEY_TIME_SCALE_Y = "time_scale_y"
    private const val KEY_TIME_VISIBLE = "time_visible"

    private const val KEY_DATE_X = "date_x_frac"
    private const val KEY_DATE_Y = "date_y_frac"
    private const val KEY_DATE_SIZE = "date_size_frac"
    private const val KEY_DATE_SCALE_X = "date_scale_x"
    private const val KEY_DATE_SCALE_Y = "date_scale_y"
    private const val KEY_DATE_VISIBLE = "date_visible"

    private const val KEY_FG_X = "fg_x_frac"
    private const val KEY_FG_Y = "fg_y_frac"
    private const val KEY_FG_WIDTH = "fg_width_frac"
    private const val KEY_BG_SCALE = "bg_scale"
    private const val KEY_BG_BLUR = "bg_blur"

    fun prefs(context: Context, prefsName: String = PREFS_NAME): SharedPreferences =
        context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    fun load(context: Context, prefsName: String = PREFS_NAME): WallpaperConfig {
        val p = prefs(context, prefsName)
        val d = WallpaperConfig.DEFAULT
        return WallpaperConfig(
            backgroundImagePath = p.getString(KEY_BACKGROUND_PATH, null),
            foregroundImagePath = p.getString(KEY_FOREGROUND_PATH, null),
            timeXFrac = p.getFloat(KEY_TIME_X, d.timeXFrac),
            timeYFrac = p.getFloat(KEY_TIME_Y, d.timeYFrac),
            timeSizeFrac = p.getFloat(KEY_TIME_SIZE, d.timeSizeFrac),
            timeScaleX = p.getFloat(KEY_TIME_SCALE_X, d.timeScaleX),
            timeScaleY = p.getFloat(KEY_TIME_SCALE_Y, d.timeScaleY),
            timeVisible = p.getBoolean(KEY_TIME_VISIBLE, d.timeVisible),
            timeStyle = loadStyle(p, "time_", d.timeStyle),
            dateXFrac = p.getFloat(KEY_DATE_X, d.dateXFrac),
            dateYFrac = p.getFloat(KEY_DATE_Y, d.dateYFrac),
            dateSizeFrac = p.getFloat(KEY_DATE_SIZE, d.dateSizeFrac),
            dateScaleX = p.getFloat(KEY_DATE_SCALE_X, d.dateScaleX),
            dateScaleY = p.getFloat(KEY_DATE_SCALE_Y, d.dateScaleY),
            dateVisible = p.getBoolean(KEY_DATE_VISIBLE, d.dateVisible),
            dateStyle = loadStyle(p, "date_", d.dateStyle),
            fgXFrac = p.getFloat(KEY_FG_X, d.fgXFrac),
            fgYFrac = p.getFloat(KEY_FG_Y, d.fgYFrac),
            fgWidthFrac = p.getFloat(KEY_FG_WIDTH, d.fgWidthFrac),
            bgScale = p.getFloat(KEY_BG_SCALE, d.bgScale),
            bgBlur = p.getFloat(KEY_BG_BLUR, d.bgBlur)
        )
    }

    fun save(context: Context, config: WallpaperConfig, prefsName: String = PREFS_NAME) {
        val e = prefs(context, prefsName).edit()
            .putString(KEY_BACKGROUND_PATH, config.backgroundImagePath)
            .putString(KEY_FOREGROUND_PATH, config.foregroundImagePath)
            .putFloat(KEY_TIME_X, config.timeXFrac)
            .putFloat(KEY_TIME_Y, config.timeYFrac)
            .putFloat(KEY_TIME_SIZE, config.timeSizeFrac)
            .putFloat(KEY_TIME_SCALE_X, config.timeScaleX)
            .putFloat(KEY_TIME_SCALE_Y, config.timeScaleY)
            .putBoolean(KEY_TIME_VISIBLE, config.timeVisible)
            .putFloat(KEY_DATE_X, config.dateXFrac)
            .putFloat(KEY_DATE_Y, config.dateYFrac)
            .putFloat(KEY_DATE_SIZE, config.dateSizeFrac)
            .putFloat(KEY_DATE_SCALE_X, config.dateScaleX)
            .putFloat(KEY_DATE_SCALE_Y, config.dateScaleY)
            .putBoolean(KEY_DATE_VISIBLE, config.dateVisible)
            .putFloat(KEY_FG_X, config.fgXFrac)
            .putFloat(KEY_FG_Y, config.fgYFrac)
            .putFloat(KEY_FG_WIDTH, config.fgWidthFrac)
            .putFloat(KEY_BG_SCALE, config.bgScale)
            .putFloat(KEY_BG_BLUR, config.bgBlur)
        saveStyle(e, "time_", config.timeStyle)
        saveStyle(e, "date_", config.dateStyle)
        e.apply()
    }

    private fun loadStyle(p: SharedPreferences, prefix: String, d: TextStyle): TextStyle = TextStyle(
        fontFamily = p.getString("${prefix}font_family", d.fontFamily) ?: d.fontFamily,
        weight = p.getInt("${prefix}weight", d.weight),
        color = p.getInt("${prefix}color", d.color),
        opacity = p.getFloat("${prefix}opacity", d.opacity),
        strokeEnabled = p.getBoolean("${prefix}stroke_enabled", d.strokeEnabled),
        strokeWidthFrac = p.getFloat("${prefix}stroke_width", d.strokeWidthFrac),
        strokeColor = p.getInt("${prefix}stroke_color", d.strokeColor),
        gradientEnabled = p.getBoolean("${prefix}gradient_enabled", d.gradientEnabled),
        gradientTopColor = p.getInt("${prefix}gradient_top", d.gradientTopColor),
        gradientBottomColor = p.getInt("${prefix}gradient_bottom", d.gradientBottomColor),
        gradientBottomAlpha = p.getFloat("${prefix}gradient_bottom_alpha", d.gradientBottomAlpha)
    )

    private fun saveStyle(e: SharedPreferences.Editor, prefix: String, s: TextStyle) {
        e.putString("${prefix}font_family", s.fontFamily)
            .putInt("${prefix}weight", s.weight)
            .putInt("${prefix}color", s.color)
            .putFloat("${prefix}opacity", s.opacity)
            .putBoolean("${prefix}stroke_enabled", s.strokeEnabled)
            .putFloat("${prefix}stroke_width", s.strokeWidthFrac)
            .putInt("${prefix}stroke_color", s.strokeColor)
            .putBoolean("${prefix}gradient_enabled", s.gradientEnabled)
            .putInt("${prefix}gradient_top", s.gradientTopColor)
            .putInt("${prefix}gradient_bottom", s.gradientBottomColor)
            .putFloat("${prefix}gradient_bottom_alpha", s.gradientBottomAlpha)
    }
}
