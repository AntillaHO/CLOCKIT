package com.example.depthwp.wallpaper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import androidx.core.content.ContextCompat
import com.example.depthwp.data.BitmapUtils
import com.example.depthwp.data.PresetRepository
import com.example.depthwp.model.WallpaperConfig
import com.example.depthwp.render.WallpaperRenderer

class DepthWallpaperService : WallpaperService() {

    private companion object {
        /**
         * Backup poll interval while the wallpaper is on screen.
         *
         * ACTION_TIME_TICK is the real clock source, but a broadcast can be missed or delayed, and
         * a wallpaper showing the wrong time is very visible. Polling costs almost nothing because
         * a tick only repaints when the displayed text actually differs from the last frame.
         */
        const val WATCHDOG_INTERVAL_MS = 15_000L

        const val META_PREFS = "preset_meta"
    }

    override fun onCreateEngine(): Engine = DepthEngine()

    private inner class DepthEngine : Engine() {

        private val mainHandler = Handler(Looper.getMainLooper())
        private val keyguardManager =
            getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        private var visible = false
        private var receiverRegistered = false

        /** Text of the last frame actually painted; null forces the next draw. */
        private var lastDrawnText: String? = null

        /** Which screen's config is currently loaded — see [currentScreen]. */
        private var loadedScreen: String? = null

        private var config: WallpaperConfig = WallpaperConfig.DEFAULT
        private var backgroundBitmap: Bitmap? = null
        private var foregroundBitmap: Bitmap? = null
        private var loadedBackgroundSig: String? = null
        private var loadedForegroundSig: String? = null
        private var surfaceWidth = resources.displayMetrics.widthPixels
        private var surfaceHeight = resources.displayMetrics.heightPixels

        private val metaPrefsListener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                mainHandler.post {
                    reloadConfig()
                    drawIfNeeded(force = true)
                }
            }

        /**
         * ACTION_TIME_TICK arrives on every minute boundary while the device is awake — the same
         * signal the system clock uses, and far more dependable than counting down to the next
         * minute by hand. The other actions cover the times the clock jumps rather than advances.
         */
        private val clockReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                drawIfNeeded(force = intent.action != Intent.ACTION_TIME_TICK)
                // The keyguard flag can still be settling right at SCREEN_ON / USER_PRESENT, so
                // look again shortly after instead of trusting the very first reading.
                if (intent.action != Intent.ACTION_TIME_TICK) {
                    mainHandler.postDelayed({ drawIfNeeded(force = false) }, 600L)
                    mainHandler.postDelayed({ drawIfNeeded(force = false) }, 1_800L)
                }
            }
        }

        private val watchdog = object : Runnable {
            override fun run() {
                // Rescheduling lives in finally: a failed frame must never break the chain, which
                // is exactly how the clock used to freeze until something toggled visibility.
                try {
                    drawIfNeeded(force = false)
                } finally {
                    if (visible) mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
                }
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            reloadConfig()
            getSharedPreferences(META_PREFS, MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(metaPrefsListener)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) {
                registerClockReceiver()
                reloadConfig()
                // Time has very likely moved on while we were hidden.
                drawIfNeeded(force = true)
                mainHandler.removeCallbacks(watchdog)
                mainHandler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
            } else {
                mainHandler.removeCallbacks(watchdog)
                unregisterClockReceiver()
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            if (width > 0 && height > 0 && (width != surfaceWidth || height != surfaceHeight)) {
                surfaceWidth = width
                surfaceHeight = height
                loadedBackgroundSig = null
                loadedForegroundSig = null
                reloadConfig()
            }
            drawIfNeeded(force = true)
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            drawIfNeeded(force = true)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            mainHandler.removeCallbacks(watchdog)
            lastDrawnText = null
        }

        override fun onDestroy() {
            super.onDestroy()
            mainHandler.removeCallbacks(watchdog)
            unregisterClockReceiver()
            getSharedPreferences(META_PREFS, MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(metaPrefsListener)
            backgroundBitmap?.recycle()
            foregroundBitmap?.recycle()
        }

        private fun registerClockReceiver() {
            if (receiverRegistered) return
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_LOCALE_CHANGED)
                // Lock/unlock transitions decide which config is shown, so they force a repaint.
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            ContextCompat.registerReceiver(
                this@DepthWallpaperService,
                clockReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }

        private fun unregisterClockReceiver() {
            if (!receiverRegistered) return
            try {
                this@DepthWallpaperService.unregisterReceiver(clockReceiver)
            } catch (e: IllegalArgumentException) {
                // already gone; nothing to undo
            }
            receiverRegistered = false
        }

        /**
         * Which set of settings to render right now.
         *
         * Android has no way to give a live wallpaper different content for the lock screen, and a
         * still image there cannot show a running clock — that is exactly why the lock screen's
         * time used to be frozen. Asking the keyguard whether the device is locked lets the one
         * wallpaper serve both, each with its own layout and a clock that keeps ticking.
         */
        private fun currentScreen(): String =
            if (keyguardManager?.isKeyguardLocked == true) PresetRepository.SCREEN_LOCK
            else PresetRepository.SCREEN_HOME

        private fun reloadConfig() {
            val screen = currentScreen()
            loadedScreen = screen
            config = PresetRepository.loadActiveConfig(this@DepthWallpaperService, screen)

            val bgSig = fileSignature(config.backgroundImagePath)
            if (bgSig != loadedBackgroundSig) {
                backgroundBitmap?.recycle()
                backgroundBitmap = config.backgroundImagePath?.let {
                    BitmapUtils.decodeFileForDisplay(it, surfaceWidth, surfaceHeight, allowRgb565 = true)
                }
                loadedBackgroundSig = bgSig
            }
            val fgSig = fileSignature(config.foregroundImagePath)
            if (fgSig != loadedForegroundSig) {
                foregroundBitmap?.recycle()
                foregroundBitmap = config.foregroundImagePath?.let {
                    BitmapUtils.decodeFileForDisplay(it, (surfaceWidth * 1.6f).toInt(), surfaceHeight, allowRgb565 = false)
                }
                loadedForegroundSig = fgSig
            }
        }

        private fun fileSignature(path: String?): String? {
            if (path == null) return null
            val file = java.io.File(path)
            if (!file.exists()) return null
            return "$path:${file.length()}:${file.lastModified()}"
        }

        /**
         * Repaints when the clock text changed, or when [force] says the frame is stale for another
         * reason (new config, new surface, returning from hidden).
         */
        private fun drawIfNeeded(force: Boolean) {
            if (!visible) return

            // Locking or unlocking swaps the whole config, including its images.
            var repaint = force
            if (currentScreen() != loadedScreen) {
                reloadConfig()
                repaint = true
            }

            val text = try {
                WallpaperRenderer.currentTextSignature()
            } catch (e: Exception) {
                null
            }
            if (!repaint && text != null && text == lastDrawnText) return
            if (drawFrame()) lastDrawnText = text
        }

        /**
         * Paints one frame. Returns whether it actually reached the screen.
         *
         * Everything is caught: lockCanvas throws when the surface is being torn down or is already
         * locked, and letting that escape would previously kill the redraw loop for good.
         */
        private fun drawFrame(): Boolean {
            val holder = surfaceHolder
            var canvas: Canvas? = null
            var painted = false
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) {
                    WallpaperRenderer.draw(
                        canvas, canvas.width, canvas.height, config, backgroundBitmap, foregroundBitmap
                    )
                    painted = true
                }
            } catch (e: Exception) {
                painted = false
            } catch (e: OutOfMemoryError) {
                painted = false
            } finally {
                if (canvas != null) {
                    try {
                        holder.unlockCanvasAndPost(canvas)
                    } catch (e: Exception) {
                        painted = false
                    }
                }
            }
            return painted
        }
    }
}
