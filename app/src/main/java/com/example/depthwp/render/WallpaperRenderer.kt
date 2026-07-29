package com.example.depthwp.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import com.example.depthwp.data.BlurUtils
import com.example.depthwp.model.FontCatalog
import com.example.depthwp.model.TextStyle
import com.example.depthwp.model.WallpaperConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Draws the wallpaper layers (background, time, date, foreground object) onto a [Canvas].
 * Shared between the editor's live preview and [com.example.depthwp.wallpaper.DepthWallpaperService]
 * so both surfaces render identically. Also exposes the layout rects used for touch hit-testing
 * and drag handles in the editor preview.
 *
 * Time and date are separate, independently positioned/sized centered text blocks.
 */
object WallpaperRenderer {

    /**
     * Date formatters, one set per thread.
     *
     * [SimpleDateFormat] is not thread-safe, and this renderer is driven from several threads at
     * once — the wallpaper engine, the editor preview, and the dashboard rendering thumbnails on a
     * background dispatcher. Sharing one instance can return garbage or throw, and an exception
     * raised here used to take the wallpaper's redraw timer down with it.
     *
     * The instances are also rebuilt when the locale changes and re-stamped with the current time
     * zone on every use, because a SimpleDateFormat captures the zone when it is constructed and
     * would otherwise keep showing the old one after travelling or a DST switch.
     */
    private class Formatters(val locale: Locale) {
        val time: SimpleDateFormat = SimpleDateFormat("HH:mm", locale)
        val date: SimpleDateFormat = SimpleDateFormat("EEEE, d. MMMM", locale)
    }

    private val threadFormatters = ThreadLocal<Formatters>()

    private fun formatters(): Formatters {
        val locale = Locale.getDefault()
        var f = threadFormatters.get()
        if (f == null || f.locale != locale) {
            f = Formatters(locale)
            threadFormatters.set(f)
        }
        val zone = TimeZone.getDefault()
        f.time.timeZone = zone
        f.date.timeZone = zone
        return f
    }

    private val backgroundFallbackPaint = Paint().apply { color = Color.parseColor("#FF16161A") }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        textAlign = Paint.Align.CENTER
        strokeJoin = Paint.Join.ROUND
    }

    private val typefaceCache = HashMap<String, Typeface>()

    private fun timeText(): String = formatters().time.format(Date())
    private fun dateText(): String = formatters().date.format(Date())

    /**
     * What the clock and date currently read. The wallpaper engine compares this against the last
     * frame it drew, so it can poll often but only actually repaint when the display would change.
     */
    fun currentTextSignature(): String = "${timeText()}|${dateText()}"

    /** Builds a typeface for a font family + numeric weight (100..900), cached to avoid per-frame churn. */
    private fun typefaceFor(family: String, weight: Int): Typeface {
        val w = weight.coerceIn(WallpaperConfig.WEIGHT_MIN, WallpaperConfig.WEIGHT_MAX)
        return typefaceCache.getOrPut("$family:$w") {
            val base = Typeface.create(family, Typeface.NORMAL)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Typeface.create(base, w, false)
            } else {
                Typeface.create(base, if (w >= 600) Typeface.BOLD else Typeface.NORMAL)
            }
        }
    }

    /** Configures [paint] for a [TextStyle]'s font family (+ horizontal squeeze) and weight. */
    private fun applyFont(paint: Paint, style: TextStyle) {
        paint.typeface = typefaceFor(FontCatalog.familyFor(style.fontFamily), style.weight)
        paint.textScaleX = FontCatalog.scaleXFor(style.fontFamily)
    }

    private fun withAlpha(color: Int, alphaFraction: Float): Int {
        val a = (Color.alpha(color) * alphaFraction.coerceIn(0f, 1f)).toInt()
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        config: WallpaperConfig,
        backgroundBitmap: Bitmap?,
        foregroundBitmap: Bitmap?
    ) {
        if (width <= 0 || height <= 0) return
        applyFont(timePaint, config.timeStyle)
        applyFont(datePaint, config.dateStyle)
        drawBackground(canvas, width, height, config, backgroundBitmap)
        if (config.timeVisible) {
            drawText(canvas, timeText(), config.timeXFrac * width, config.timeYFrac * height,
                config.timeSizeFrac * width, config.timeScaleX, config.timeScaleY, config.timeStyle, timePaint)
        }
        if (config.dateVisible) {
            drawText(canvas, dateText(), config.dateXFrac * width, config.dateYFrac * height,
                config.dateSizeFrac * width, config.dateScaleX, config.dateScaleY, config.dateStyle, datePaint)
        }
        drawForeground(canvas, width, height, config, foregroundBitmap)
    }

    private fun drawBackground(canvas: Canvas, width: Int, height: Int, config: WallpaperConfig, source: Bitmap?) {
        if (source == null || source.width <= 0 || source.height <= 0) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundFallbackPaint)
            return
        }
        // The blurred copy is smaller than the source but keeps its aspect ratio, so the
        // center-crop maths below stays correct either way.
        val bitmap = BlurUtils.blurred(source, config.bgBlur)
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundFallbackPaint)
            return
        }
        // center-crop, then apply the user's zoom factor on top (anchored at the center)
        val cover = maxOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val scale = cover * config.bgScale.coerceAtLeast(1f)
        val dx = (width - bitmap.width * scale) / 2f
        val dy = (height - bitmap.height * scale) / 2f
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
        }
        canvas.drawBitmap(bitmap, matrix, bitmapPaint)
    }

    private fun drawText(
        canvas: Canvas,
        text: String,
        cx: Float,
        cy: Float,
        textSize: Float,
        scaleX: Float,
        scaleY: Float,
        style: TextStyle,
        paint: Paint
    ) {
        paint.textSize = textSize
        paint.style = Paint.Style.FILL
        val alpha = style.opacity.coerceIn(0f, 1f)
        val fm = paint.fontMetrics
        val baseline = cy - (fm.ascent + fm.descent) / 2f
        val top = baseline + fm.ascent
        val bottom = baseline + fm.descent

        // Fill: either a flat colour or a vertical gradient (top opaque -> bottom faded) for the glossy look.
        if (style.gradientEnabled) {
            paint.shader = LinearGradient(
                cx, top, cx, bottom,
                withAlpha(style.gradientTopColor, alpha),
                withAlpha(style.gradientBottomColor, alpha * style.gradientBottomAlpha),
                Shader.TileMode.CLAMP
            )
        } else {
            paint.shader = null
            paint.color = style.color
            paint.alpha = (alpha * 255).toInt()
        }

        canvas.save()
        canvas.scale(scaleX, scaleY, cx, cy)
        // Outline is drawn behind the fill.
        if (style.strokeEnabled && style.strokeWidthFrac > 0f) {
            strokePaint.typeface = paint.typeface
            strokePaint.textScaleX = paint.textScaleX
            strokePaint.textSize = textSize
            strokePaint.strokeWidth = style.strokeWidthFrac * textSize
            strokePaint.color = style.strokeColor
            strokePaint.alpha = (alpha * 255).toInt()
            canvas.drawText(text, cx, baseline, strokePaint)
        }
        canvas.drawText(text, cx, baseline, paint)
        canvas.restore()
        paint.shader = null
    }

    private fun drawForeground(canvas: Canvas, width: Int, height: Int, config: WallpaperConfig, source: Bitmap?) {
        // Geometry comes from the source; the blurred copy keeps its aspect ratio, so the object
        // stays put no matter how strong the blur is.
        val rect = foregroundRect(width, height, config, source) ?: return
        val bitmap = BlurUtils.blurred(source!!, config.bgBlur)
        if (bitmap.isRecycled) return
        canvas.drawBitmap(bitmap, null, rect, bitmapPaint)
    }

    /** Destination rect the foreground bitmap is drawn into, or null if not applicable. Used for hit-testing too. */
    fun foregroundRect(width: Int, height: Int, config: WallpaperConfig, bitmap: Bitmap?): RectF? {
        if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0 || width <= 0) return null
        val targetWidth = config.fgWidthFrac * width
        val targetHeight = targetWidth * (bitmap.height.toFloat() / bitmap.width.toFloat())
        val left = config.fgXFrac * width - targetWidth / 2f
        val top = config.fgYFrac * height - targetHeight / 2f
        return RectF(left, top, left + targetWidth, top + targetHeight)
    }

    // --- Text geometry (used for hit-testing and drag handles) --------------

    private const val RECT_PAD_FACTOR = 0.18f

    fun timeRect(width: Int, height: Int, config: WallpaperConfig): RectF {
        applyFont(timePaint, config.timeStyle)
        return textRect(timeText(), config.timeXFrac * width, config.timeYFrac * height,
            config.timeSizeFrac * width, config.timeScaleX, config.timeScaleY, timePaint)
    }

    fun dateRect(width: Int, height: Int, config: WallpaperConfig): RectF {
        applyFont(datePaint, config.dateStyle)
        return textRect(dateText(), config.dateXFrac * width, config.dateYFrac * height,
            config.dateSizeFrac * width, config.dateScaleX, config.dateScaleY, datePaint)
    }

    fun timeBaseHalfWidth(width: Int, config: WallpaperConfig): Float {
        applyFont(timePaint, config.timeStyle)
        return baseHalfWidth(timeText(), config.timeSizeFrac * width, timePaint)
    }

    fun timeBaseHalfHeight(width: Int, config: WallpaperConfig): Float {
        applyFont(timePaint, config.timeStyle)
        return baseHalfHeight(config.timeSizeFrac * width, timePaint)
    }

    fun dateBaseHalfWidth(width: Int, config: WallpaperConfig): Float {
        applyFont(datePaint, config.dateStyle)
        return baseHalfWidth(dateText(), config.dateSizeFrac * width, datePaint)
    }

    fun dateBaseHalfHeight(width: Int, config: WallpaperConfig): Float {
        applyFont(datePaint, config.dateStyle)
        return baseHalfHeight(config.dateSizeFrac * width, datePaint)
    }

    private fun textRect(
        text: String, cx: Float, cy: Float, textSize: Float, scaleX: Float, scaleY: Float, paint: Paint
    ): RectF {
        val halfW = baseHalfWidth(text, textSize, paint) * scaleX
        val halfH = baseHalfHeight(textSize, paint) * scaleY
        val padX = halfW * RECT_PAD_FACTOR
        val padY = halfH * RECT_PAD_FACTOR
        return RectF(cx - halfW - padX, cy - halfH - padY, cx + halfW + padX, cy + halfH + padY)
    }

    private fun baseHalfWidth(text: String, textSize: Float, paint: Paint): Float {
        paint.textSize = textSize
        return paint.measureText(text) / 2f
    }

    private fun baseHalfHeight(textSize: Float, paint: Paint): Float {
        paint.textSize = textSize
        val fm = paint.fontMetrics
        return (fm.descent - fm.ascent) / 2f
    }
}
