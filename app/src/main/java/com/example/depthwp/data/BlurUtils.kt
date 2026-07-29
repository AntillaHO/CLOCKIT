package com.example.depthwp.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect

/**
 * Blur for the wallpaper's photo layers (background and foreground object).
 *
 * Works by downscaling the source, running a few horizontal + vertical box-blur passes on the small
 * copy, and letting the renderer scale it back up with bilinear filtering. Three box passes
 * approximate a Gaussian closely enough to read as a proper glass blur.
 *
 * The strength curve matters as much as the algorithm here. The downscale is itself a blur, so a
 * fixed small working size makes even the lowest setting look heavily smeared. Instead the working
 * resolution *shrinks with the requested strength*: gentle settings blur a nearly full-resolution
 * copy by a tiny radius, strong settings blur a small copy by a large one. The strength is also
 * eased quadratically, which spreads the useful adjustments across the lower half of the slider
 * where people actually work.
 *
 * Results are cached because [com.example.depthwp.render.WallpaperRenderer] asks for one on every
 * frame. Cached bitmaps are never recycled explicitly — the editor preview, the dashboard's
 * thumbnail rendering and the wallpaper service all draw from this cache on different threads, so
 * freeing one out from under a draw in progress would crash. They are small and land on the normal
 * Java heap, so the GC reclaims evicted entries safely.
 */
object BlurUtils {

    /**
     * Working resolution (longest edge) at the gentlest and strongest settings. The low end stays
     * near display resolution so a small blur reads as "slightly soft" rather than "downscaled";
     * pushing it much higher would cost several MB per pass in a wallpaper service's modest heap.
     */
    private const val WORK_DIMENSION_MIN_BLUR = 720
    private const val WORK_DIMENSION_MAX_BLUR = 200

    /** Box-blur radius on the working copy at full strength. */
    private const val MAX_RADIUS = 8f

    private const val PASSES = 3

    /**
     * Only the background and foreground of the config being drawn are ever hot, so this is
     * deliberately small — a large blurred bitmap costs a few MB and the wallpaper service is the
     * memory-tightest consumer of this cache.
     */
    private const val MAX_ENTRIES = 6

    private val cache = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean =
            size > MAX_ENTRIES
    }

    /**
     * Blur amount is quantised so tiny slider movements reuse the cached bitmap. Dimensions and
     * generation id are part of the key because an identity hash can be reused once an earlier
     * bitmap is collected — without them a new image could pick up the previous one's blur.
     */
    private fun cacheKey(source: Bitmap, amount: Float): String =
        "${System.identityHashCode(source)}:${source.width}x${source.height}" +
            ":${source.generationId}:${(amount.coerceIn(0f, 1f) * 40f).toInt()}"

    /**
     * Returns a blurred copy of [source] for a blur strength of [amount] (0..1), or [source] itself
     * when the amount is negligible or blurring fails. The returned bitmap belongs to this cache —
     * callers must not recycle it.
     */
    @Synchronized
    fun blurred(source: Bitmap, amount: Float): Bitmap {
        if (amount <= 0.02f || source.isRecycled) return source

        val key = cacheKey(source, amount)
        cache[key]?.let { if (!it.isRecycled) return it else cache.remove(key) }

        val result = try {
            renderBlur(source, amount)
        } catch (e: OutOfMemoryError) {
            null
        } catch (e: Exception) {
            null
        } ?: return source

        cache[key] = result
        return result
    }

    /** Drops every cached bitmap. Called when a wallpaper surface goes away. */
    @Synchronized
    fun clear() {
        cache.clear()
    }

    private fun renderBlur(source: Bitmap, amount: Float): Bitmap? {
        if (source.width <= 0 || source.height <= 0) return null

        // Quadratic easing: the first half of the slider stays subtle instead of jumping
        // straight to an unrecognisable smear.
        val a = amount.coerceIn(0f, 1f)
        val eased = a * a

        val workDimension =
            WORK_DIMENSION_MIN_BLUR - (WORK_DIMENSION_MIN_BLUR - WORK_DIMENSION_MAX_BLUR) * eased
        val longest = maxOf(source.width, source.height)
        // Never upscale: a small source stays at its own resolution.
        val scale = (workDimension / longest).coerceAtMost(1f)

        val w = (source.width * scale).toInt().coerceAtLeast(8)
        val h = (source.height * scale).toInt().coerceAtLeast(8)

        val small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(small).drawBitmap(
            source,
            Rect(0, 0, source.width, source.height),
            Rect(0, 0, w, h),
            Paint(Paint.FILTER_BITMAP_FLAG)
        )

        val radius = (1f + eased * (MAX_RADIUS - 1f)).toInt().coerceIn(1, MAX_RADIUS.toInt())
        boxBlur(small, radius, source.hasAlpha())
        return small
    }

    /**
     * In-place separable box blur: [PASSES] horizontal + vertical passes over the pixel buffer.
     *
     * When [withAlpha] is set the colour channels are premultiplied by alpha before blurring and
     * divided back out afterwards. Blurring straight (non-premultiplied) colour would drag the
     * black of fully transparent pixels into the visible edge, ringing a dark halo around a cut-out
     * object — exactly the layer this is used for.
     */
    private fun boxBlur(bitmap: Bitmap, radius: Int, withAlpha: Boolean) {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 1 || h <= 1) return

        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        if (withAlpha) premultiply(pixels)

        val scratch = IntArray(w * h)
        repeat(PASSES) {
            blurRows(pixels, scratch, w, h, radius, withAlpha)  // rows -> scratch
            blurCols(scratch, pixels, w, h, radius, withAlpha)  // cols -> back into pixels
        }

        if (withAlpha) unpremultiply(pixels)

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    private fun premultiply(pixels: IntArray) {
        for (i in pixels.indices) {
            val c = pixels[i]
            val a = c ushr 24
            if (a == 255) continue
            val r = (((c shr 16) and 0xFF) * a) / 255
            val g = (((c shr 8) and 0xFF) * a) / 255
            val b = ((c and 0xFF) * a) / 255
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private fun unpremultiply(pixels: IntArray) {
        for (i in pixels.indices) {
            val c = pixels[i]
            val a = c ushr 24
            if (a == 255) continue
            if (a == 0) {
                pixels[i] = 0
                continue
            }
            val r = ((((c shr 16) and 0xFF) * 255) / a).coerceAtMost(255)
            val g = ((((c shr 8) and 0xFF) * 255) / a).coerceAtMost(255)
            val b = (((c and 0xFF) * 255) / a).coerceAtMost(255)
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private fun blurRows(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Int, withAlpha: Boolean) {
        val window = radius * 2 + 1
        for (y in 0 until h) {
            val row = y * w
            var a = 0; var r = 0; var g = 0; var b = 0

            // Prime the running sum for x = 0, clamping at the edges.
            for (i in -radius..radius) {
                val c = src[row + i.coerceIn(0, w - 1)]
                if (withAlpha) a += c ushr 24
                r += (c shr 16) and 0xFF
                g += (c shr 8) and 0xFF
                b += c and 0xFF
            }
            for (x in 0 until w) {
                val outA = if (withAlpha) a / window else 255
                dst[row + x] = (outA shl 24) or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)

                val outC = src[row + (x - radius).coerceIn(0, w - 1)]
                val inC = src[row + (x + radius + 1).coerceIn(0, w - 1)]
                if (withAlpha) a += (inC ushr 24) - (outC ushr 24)
                r += ((inC shr 16) and 0xFF) - ((outC shr 16) and 0xFF)
                g += ((inC shr 8) and 0xFF) - ((outC shr 8) and 0xFF)
                b += (inC and 0xFF) - (outC and 0xFF)
            }
        }
    }

    private fun blurCols(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Int, withAlpha: Boolean) {
        val window = radius * 2 + 1
        for (x in 0 until w) {
            var a = 0; var r = 0; var g = 0; var b = 0

            for (i in -radius..radius) {
                val c = src[i.coerceIn(0, h - 1) * w + x]
                if (withAlpha) a += c ushr 24
                r += (c shr 16) and 0xFF
                g += (c shr 8) and 0xFF
                b += c and 0xFF
            }
            for (y in 0 until h) {
                val outA = if (withAlpha) a / window else 255
                dst[y * w + x] = (outA shl 24) or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)

                val outC = src[(y - radius).coerceIn(0, h - 1) * w + x]
                val inC = src[(y + radius + 1).coerceIn(0, h - 1) * w + x]
                if (withAlpha) a += (inC ushr 24) - (outC ushr 24)
                r += ((inC shr 16) and 0xFF) - ((outC shr 16) and 0xFF)
                g += ((inC shr 8) and 0xFF) - ((outC shr 8) and 0xFF)
                b += (inC and 0xFF) - (outC and 0xFF)
            }
        }
    }
}
