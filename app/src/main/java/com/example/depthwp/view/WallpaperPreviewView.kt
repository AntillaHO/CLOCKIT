package com.example.depthwp.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import com.example.depthwp.model.WallpaperConfig
import com.example.depthwp.render.WallpaperRenderer
import kotlin.math.abs

/**
 * Renders the same layers as [com.example.depthwp.wallpaper.DepthWallpaperService] and turns the
 * editor into a direct-manipulation surface:
 *
 *  - Tap the time, the date or the object once to select it -> corner drag handles appear.
 *  - Drag a corner handle to stretch/resize (text stretches independently in X and Y).
 *  - Drag the body to move it; center snap guidelines appear when it lines up with the middle.
 *  - Pinch to scale the current selection (time, date, object, or the background zoom).
 */
class WallpaperPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Layer { NONE, TIME, DATE, FOREGROUND, BACKGROUND }

    var config: WallpaperConfig = WallpaperConfig.DEFAULT
        set(value) {
            field = value
            invalidate()
        }

    var backgroundBitmap: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }

    var foregroundBitmap: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }

    var onConfigChanged: ((WallpaperConfig) -> Unit)? = null
    var onSelectionChanged: ((Layer) -> Unit)? = null
    var editable: Boolean = true

    /**
     * When false the preview only reacts to taps (selecting a layer) and refuses to move, resize or
     * pinch anything. The editor exposes this as a lock so a stray swipe can't wreck a layout that
     * is already dialled in — every property is reachable from the sliders regardless.
     */
    var gesturesEnabled: Boolean = true
        set(value) {
            field = value
            if (!value) endGesture()
        }

    var selection: Layer = Layer.NONE
        private set

    private enum class Mode { NONE, MOVE, RESIZE }

    private var mode = Mode.NONE

    /** Set on touch-down when the finger landed inside the current selection. */
    private var canDragSelection = false
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var movedBeyondSlop = false

    private var showVGuide = false
    private var showHGuide = false

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val density = resources.displayMetrics.density
    private val handleRadius = 7f * density
    private val handleTouchRadius = 26f * density
    private val snapPx = 14f * density

    private val selectionStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.parseColor("#FF8C6DFF")
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FF8C6DFF")
    }
    private val handleGlyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = Color.parseColor("#FFFFD43B")
    }

    private val scaleGestureDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (!gesturesEnabled) return false
                val f = detector.scaleFactor
                when (selection) {
                    Layer.TIME -> updateConfig(config.copy(
                        timeSizeFrac = (config.timeSizeFrac * f)
                            .coerceIn(WallpaperConfig.TIME_SIZE_MIN, WallpaperConfig.TIME_SIZE_MAX)
                    ))
                    Layer.DATE -> updateConfig(config.copy(
                        dateSizeFrac = (config.dateSizeFrac * f)
                            .coerceIn(WallpaperConfig.DATE_SIZE_MIN, WallpaperConfig.DATE_SIZE_MAX)
                    ))
                    Layer.FOREGROUND -> if (foregroundBitmap != null) updateConfig(config.copy(
                        fgWidthFrac = (config.fgWidthFrac * f)
                            .coerceIn(WallpaperConfig.FG_WIDTH_MIN, WallpaperConfig.FG_WIDTH_MAX)
                    ))
                    else -> if (backgroundBitmap != null) updateConfig(config.copy(
                        bgScale = (config.bgScale * f)
                            .coerceIn(WallpaperConfig.BG_SCALE_MIN, WallpaperConfig.BG_SCALE_MAX)
                    ))
                }
                return true
            }
        }
    )

    fun setSelection(layer: Layer) {
        if (selection != layer) {
            selection = layer
            onSelectionChanged?.invoke(layer)
            invalidate()
        }
    }

    private fun updateConfig(newConfig: WallpaperConfig) {
        config = newConfig
        onConfigChanged?.invoke(newConfig)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        WallpaperRenderer.draw(canvas, width, height, config, backgroundBitmap, foregroundBitmap)
        if (!editable) return

        if (showVGuide) canvas.drawLine(width / 2f, 0f, width / 2f, height.toFloat(), guidePaint)
        if (showHGuide) canvas.drawLine(0f, height / 2f, width.toFloat(), height / 2f, guidePaint)

        val rect = selectedRect() ?: return
        canvas.drawRoundRect(rect, 8f * density, 8f * density, selectionStroke)
        for (corner in cornersOf(rect)) {
            canvas.drawCircle(corner[0], corner[1], handleRadius, handleFill)
            val g = handleRadius * 0.55f
            canvas.drawLine(corner[0] - g, corner[1] - g, corner[0] + g, corner[1] + g, handleGlyph)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!editable) return false
        scaleGestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y
                lastX = event.x; lastY = event.y
                movedBeyondSlop = false
                mode = Mode.NONE
                canDragSelection = false

                // A drag only ever affects the layer that is *already* selected, and only when it
                // starts on that layer. Previously a drag grabbed whatever happened to be under the
                // finger, which meant overlapping elements stole each other's gestures and a swipe
                // meant to pan could silently move the clock.
                val rect = selectedRect()
                if (gesturesEnabled && rect != null) {
                    if (nearAnyCorner(rect, event.x, event.y)) {
                        mode = Mode.RESIZE
                    } else if (rect.contains(event.x, event.y)) {
                        canDragSelection = true
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (scaleGestureDetector.isInProgress || event.pointerCount > 1) {
                    lastX = event.x; lastY = event.y
                    return true
                }
                if (!movedBeyondSlop &&
                    (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop)
                ) {
                    movedBeyondSlop = true
                    if (mode != Mode.RESIZE && canDragSelection) mode = Mode.MOVE
                }
                when (mode) {
                    Mode.RESIZE -> resizeTo(event.x, event.y)
                    Mode.MOVE -> moveBy(event.x - lastX, event.y - lastY)
                    Mode.NONE -> {}
                }
                lastX = event.x; lastY = event.y
            }

            MotionEvent.ACTION_UP -> {
                // A tap that didn't turn into a drag selects whatever is under the finger.
                if (!movedBeyondSlop && !scaleGestureDetector.isInProgress) {
                    setSelection(layerUnder(event.x, event.y))
                }
                endGesture()
            }

            MotionEvent.ACTION_CANCEL -> endGesture()
        }
        return true
    }

    private fun endGesture() {
        mode = Mode.NONE
        canDragSelection = false
        if (showVGuide || showHGuide) {
            showVGuide = false
            showHGuide = false
            invalidate()
        }
    }

    // --- geometry helpers ---------------------------------------------------

    // A hidden layer has no frame and no handles: there is nothing on screen to grab.
    private fun selectedRect(): RectF? = when (selection) {
        Layer.TIME -> if (config.timeVisible) WallpaperRenderer.timeRect(width, height, config) else null
        Layer.DATE -> if (config.dateVisible) WallpaperRenderer.dateRect(width, height, config) else null
        Layer.FOREGROUND -> WallpaperRenderer.foregroundRect(width, height, config, foregroundBitmap)
        else -> null
    }

    private fun cornersOf(rect: RectF): List<FloatArray> = listOf(
        floatArrayOf(rect.left, rect.top),
        floatArrayOf(rect.right, rect.top),
        floatArrayOf(rect.right, rect.bottom),
        floatArrayOf(rect.left, rect.bottom)
    )

    private fun nearAnyCorner(rect: RectF, x: Float, y: Float): Boolean =
        cornersOf(rect).any { abs(it[0] - x) <= handleTouchRadius && abs(it[1] - y) <= handleTouchRadius }

    private fun layerUnder(x: Float, y: Float): Layer {
        foregroundBitmap?.let {
            val rect = WallpaperRenderer.foregroundRect(width, height, config, it)
            if (rect != null && rect.contains(x, y)) return Layer.FOREGROUND
        }
        if (config.timeVisible &&
            WallpaperRenderer.timeRect(width, height, config).contains(x, y)
        ) return Layer.TIME
        if (config.dateVisible &&
            WallpaperRenderer.dateRect(width, height, config).contains(x, y)
        ) return Layer.DATE
        return Layer.BACKGROUND
    }

    private fun moveBy(dx: Float, dy: Float) {
        if (width == 0 || height == 0) return
        when (selection) {
            Layer.TIME -> {
                val (xf, yf) = snappedCenter(config.timeXFrac * width + dx, config.timeYFrac * height + dy)
                updateConfig(config.copy(timeXFrac = xf, timeYFrac = yf))
            }
            Layer.DATE -> {
                val (xf, yf) = snappedCenter(config.dateXFrac * width + dx, config.dateYFrac * height + dy)
                updateConfig(config.copy(dateXFrac = xf, dateYFrac = yf))
            }
            Layer.FOREGROUND -> {
                // Object moves completely freely (no center snapping).
                val xf = ((config.fgXFrac * width + dx) / width)
                    .coerceIn(WallpaperConfig.POSITION_MIN, WallpaperConfig.POSITION_MAX)
                val yf = ((config.fgYFrac * height + dy) / height)
                    .coerceIn(WallpaperConfig.POSITION_MIN, WallpaperConfig.POSITION_MAX)
                updateConfig(config.copy(fgXFrac = xf, fgYFrac = yf))
            }
            else -> {}
        }
    }

    /** Snaps a proposed center (in px) to the canvas mid-axes and toggles the guideline flags. */
    private fun snappedCenter(px: Float, py: Float): Pair<Float, Float> {
        var cxPx = px
        var cyPx = py
        showVGuide = abs(cxPx - width / 2f) < snapPx
        showHGuide = abs(cyPx - height / 2f) < snapPx
        if (showVGuide) cxPx = width / 2f
        if (showHGuide) cyPx = height / 2f
        invalidate()
        val xf = (cxPx / width).coerceIn(WallpaperConfig.POSITION_MIN, WallpaperConfig.POSITION_MAX)
        val yf = (cyPx / height).coerceIn(WallpaperConfig.POSITION_MIN, WallpaperConfig.POSITION_MAX)
        return xf to yf
    }

    private fun resizeTo(x: Float, y: Float) {
        if (width == 0 || height == 0) return
        when (selection) {
            Layer.TIME -> {
                val cx = config.timeXFrac * width
                val cy = config.timeYFrac * height
                val bw = WallpaperRenderer.timeBaseHalfWidth(width, config)
                val bh = WallpaperRenderer.timeBaseHalfHeight(width, config)
                if (bw <= 0f || bh <= 0f) return
                updateConfig(config.copy(
                    timeScaleX = (abs(x - cx) / bw).coerceIn(WallpaperConfig.TEXT_SCALE_MIN, WallpaperConfig.TEXT_SCALE_MAX),
                    timeScaleY = (abs(y - cy) / bh).coerceIn(WallpaperConfig.TEXT_SCALE_MIN, WallpaperConfig.TEXT_SCALE_MAX)
                ))
            }
            Layer.DATE -> {
                val cx = config.dateXFrac * width
                val cy = config.dateYFrac * height
                val bw = WallpaperRenderer.dateBaseHalfWidth(width, config)
                val bh = WallpaperRenderer.dateBaseHalfHeight(width, config)
                if (bw <= 0f || bh <= 0f) return
                updateConfig(config.copy(
                    dateScaleX = (abs(x - cx) / bw).coerceIn(WallpaperConfig.TEXT_SCALE_MIN, WallpaperConfig.TEXT_SCALE_MAX),
                    dateScaleY = (abs(y - cy) / bh).coerceIn(WallpaperConfig.TEXT_SCALE_MIN, WallpaperConfig.TEXT_SCALE_MAX)
                ))
            }
            Layer.FOREGROUND -> {
                val bmp = foregroundBitmap ?: return
                val cx = config.fgXFrac * width
                val cy = config.fgYFrac * height
                val aspect = bmp.width.toFloat() / bmp.height.toFloat()
                val fromX = 2f * abs(x - cx) / width
                val fromY = (2f * abs(y - cy) * aspect) / width
                updateConfig(config.copy(
                    fgWidthFrac = maxOf(fromX, fromY)
                        .coerceIn(WallpaperConfig.FG_WIDTH_MIN, WallpaperConfig.FG_WIDTH_MAX)
                ))
            }
            else -> {}
        }
    }
}
