package com.example.depthwp.editor

import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.depthwp.R
import com.example.depthwp.data.BitmapUtils
import com.example.depthwp.data.ImageStore
import com.example.depthwp.data.PresetRepository
import com.example.depthwp.databinding.ActivityEditorBinding
import com.example.depthwp.model.FontCatalog
import com.example.depthwp.model.TextStyle
import com.example.depthwp.model.WallpaperConfig
import com.example.depthwp.render.WallpaperRenderer
import com.example.depthwp.view.WallpaperPreviewView.Layer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class EditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PRESET_ID = "preset_id"

        private const val SCREEN_LOCK = "lock"
        private const val SCREEN_HOME = "home"
    }

    private lateinit var binding: ActivityEditorBinding
    private var config: WallpaperConfig = WallpaperConfig.DEFAULT
    private var target: Layer = Layer.TIME
    private var isBinding = false
    private var bodyCollapsed = false
    private var programmaticSelection = false

    /**
     * True while a tab change is being driven by a tap in the preview rather than by the user
     * pressing the tab itself. Pressing a tab is a request to see its controls, so the sheet opens;
     * tapping an element on the canvas is not, and the sheet must stay exactly as the user left it.
     */
    private var tabChangeFromPreview = false

    private lateinit var presetId: String
    private var screenType: String = SCREEN_HOME

    private val pickBackgroundLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri?.let { handleBackgroundPicked(it) }
        }
    private val pickForegroundLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri?.let { handleForegroundPicked(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        presetId = intent.getStringExtra(EXTRA_PRESET_ID) ?: run {
            finish()
            return
        }

        binding.presetTitle.text = PresetRepository.getPreset(this, presetId)?.name.orEmpty()

        loadCurrentConfig()
        binding.previewView.config = config

        setupPreview()
        setupScreenToggle()
        setupTargetTabs()
        setupSliders()
        setupEffectsToggle()
        setupButtons()

        binding.targetGroup.check(binding.tabTime.id)
        selectTarget(Layer.TIME, syncPreview = true)
        loadBitmaps()
    }

    private fun loadCurrentConfig() {
        config = PresetRepository.loadConfig(this, presetId, screenType)
    }

    private fun imageSubDir(): String = PresetRepository.imageDirFor(presetId, screenType)

    // ---- screen toggle (lock / home) ------------------------------------------

    private fun setupScreenToggle() {
        binding.screenGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val newScreen = if (checkedId == binding.tabLock.id) SCREEN_LOCK else SCREEN_HOME
            if (newScreen == screenType) return@addOnButtonCheckedListener

            persistConfig()
            screenType = newScreen
            loadCurrentConfig()
            binding.previewView.config = config
            loadBitmaps()
            refreshActiveSection()
            updateApplyUi()
        }
        binding.screenGroup.check(binding.tabHome.id)
        updateApplyUi()
    }

    /** Re-binds whichever control section is currently on screen to the current config. */
    private fun refreshActiveSection() {
        when {
            isTextTarget() -> {
                buildFontChips()
                bindActiveControls()
                rebuildColorRows()
            }
            target == Layer.BACKGROUND -> bindBackgroundControls()
            target == Layer.FOREGROUND -> bindForegroundControls()
        }
    }

    // ---- preview wiring -------------------------------------------------------

    private fun setupPreview() {
        binding.previewView.onConfigChanged = { updated ->
            config = updated
            persistConfig()
            if (!isBinding) bindActiveControls()
        }
        binding.previewView.onSelectionChanged = { layer ->
            binding.textHint.setText(hintFor(layer))
            // Selecting in the preview switches the tab but deliberately leaves the sheet's
            // open/closed state alone. Having it spring open or shut mid-drag was disorienting;
            // the sheet now only moves when the handle or a tab is pressed.
            if (!programmaticSelection && layer != Layer.NONE) {
                tabChangeFromPreview = true
                checkTab(layer)
                selectTarget(layer, syncPreview = false)
                tabChangeFromPreview = false
            }
        }
    }

    private fun hintFor(layer: Layer): Int = when (layer) {
        Layer.TIME -> R.string.hint_time_selected
        Layer.DATE -> R.string.hint_date_selected
        Layer.FOREGROUND -> R.string.hint_object_selected
        Layer.BACKGROUND -> R.string.hint_background_selected
        Layer.NONE -> R.string.hint_tap_to_edit
    }

    // ---- target tabs ----------------------------------------------------------

    private fun setupTargetTabs() {
        binding.targetGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val newTarget = when (checkedId) {
                binding.tabTime.id -> Layer.TIME
                binding.tabDate.id -> Layer.DATE
                binding.tabObject.id -> Layer.FOREGROUND
                else -> Layer.BACKGROUND
            }
            selectTarget(newTarget, syncPreview = true)
            if (!tabChangeFromPreview) setBodyCollapsed(false)
        }
        binding.panelHandle.setOnClickListener { setBodyCollapsed(!bodyCollapsed) }
    }

    private fun checkTab(layer: Layer) {
        val id = when (layer) {
            Layer.TIME -> binding.tabTime.id
            Layer.DATE -> binding.tabDate.id
            Layer.FOREGROUND -> binding.tabObject.id
            else -> binding.tabBackground.id
        }
        if (binding.targetGroup.checkedButtonId != id) binding.targetGroup.check(id)
    }

    private fun selectTarget(newTarget: Layer, syncPreview: Boolean) {
        target = newTarget
        binding.contentText.visibility = if (isTextTarget()) View.VISIBLE else View.GONE
        binding.contentObject.visibility = if (newTarget == Layer.FOREGROUND) View.VISIBLE else View.GONE
        binding.contentBackground.visibility = if (newTarget == Layer.BACKGROUND) View.VISIBLE else View.GONE
        refreshActiveSection()
        if (syncPreview) {
            programmaticSelection = true
            binding.previewView.setSelection(newTarget)
            programmaticSelection = false
        }
    }

    private fun isTextTarget() = target == Layer.TIME || target == Layer.DATE

    private fun setBodyCollapsed(collapsed: Boolean) {
        if (bodyCollapsed == collapsed) return
        bodyCollapsed = collapsed
        binding.panelScroll.visibility = if (collapsed) View.GONE else View.VISIBLE
    }

    // ---- active text style access ---------------------------------------------

    private fun activeStyle(): TextStyle = if (target == Layer.DATE) config.dateStyle else config.timeStyle

    private fun updateActiveStyle(transform: (TextStyle) -> TextStyle) {
        config = if (target == Layer.DATE) config.copy(dateStyle = transform(config.dateStyle))
        else config.copy(timeStyle = transform(config.timeStyle))
        binding.previewView.config = config
        persistConfig()
    }

    private fun sizeBounds(): Pair<Float, Float> = if (target == Layer.DATE)
        WallpaperConfig.DATE_SIZE_MIN to WallpaperConfig.DATE_SIZE_MAX
    else WallpaperConfig.TIME_SIZE_MIN to WallpaperConfig.TIME_SIZE_MAX

    private fun currentSizeFrac(): Float = if (target == Layer.DATE) config.dateSizeFrac else config.timeSizeFrac

    private fun setSizeFrac(frac: Float) {
        applyToTarget(
            onTime = { config.copy(timeSizeFrac = frac) },
            onDate = { config.copy(dateSizeFrac = frac) }
        )
    }

    /** Applies a config change to whichever text layer is currently selected. */
    private fun applyToTarget(onTime: () -> WallpaperConfig, onDate: () -> WallpaperConfig) {
        config = if (target == Layer.DATE) onDate() else onTime()
        binding.previewView.config = config
        persistConfig()
    }

    // Slider positions are 0..100; these map that range onto the model's actual bounds.
    private fun sliderToRange(value: Float, min: Float, max: Float): Float =
        min + value / 100f * (max - min)

    private fun rangeToSlider(v: Float, min: Float, max: Float): Float =
        ((v - min) / (max - min) * 100f).coerceIn(0f, 100f)

    private fun currentScaleX(): Float = if (target == Layer.DATE) config.dateScaleX else config.timeScaleX
    private fun currentScaleY(): Float = if (target == Layer.DATE) config.dateScaleY else config.timeScaleY
    private fun currentPosX(): Float = if (target == Layer.DATE) config.dateXFrac else config.timeXFrac
    private fun currentPosY(): Float = if (target == Layer.DATE) config.dateYFrac else config.timeYFrac
    private fun currentVisible(): Boolean = if (target == Layer.DATE) config.dateVisible else config.timeVisible

    // ---- sliders --------------------------------------------------------------

    private fun setupSliders() {
        binding.sliderSize.addOnChangeListener { _, value, fromUser ->
            binding.valueSize.text = percent(value)
            if (fromUser && isTextTarget()) {
                val (lo, hi) = sizeBounds()
                setSizeFrac(lo + value / 100f * (hi - lo))
            }
        }
        binding.sliderScaleX.addOnChangeListener { _, value, fromUser ->
            val scale = sliderToRange(value, WallpaperConfig.TEXT_SCALE_MIN, WallpaperConfig.TEXT_SCALE_MAX)
            binding.valueScaleX.text = percent(scale * 100f)
            if (fromUser && isTextTarget()) applyToTarget(
                onTime = { config.copy(timeScaleX = scale) },
                onDate = { config.copy(dateScaleX = scale) }
            )
        }
        binding.sliderScaleY.addOnChangeListener { _, value, fromUser ->
            val scale = sliderToRange(value, WallpaperConfig.TEXT_SCALE_MIN, WallpaperConfig.TEXT_SCALE_MAX)
            binding.valueScaleY.text = percent(scale * 100f)
            if (fromUser && isTextTarget()) applyToTarget(
                onTime = { config.copy(timeScaleY = scale) },
                onDate = { config.copy(dateScaleY = scale) }
            )
        }
        binding.sliderPosX.addOnChangeListener { _, value, fromUser ->
            val pos = sliderToRange(value, WallpaperConfig.POSITION_MIN, WallpaperConfig.POSITION_MAX)
            binding.valuePosX.text = percent(pos * 100f)
            if (fromUser && isTextTarget()) applyToTarget(
                onTime = { config.copy(timeXFrac = pos) },
                onDate = { config.copy(dateXFrac = pos) }
            )
        }
        binding.sliderPosY.addOnChangeListener { _, value, fromUser ->
            val pos = sliderToRange(value, WallpaperConfig.POSITION_MIN, WallpaperConfig.POSITION_MAX)
            binding.valuePosY.text = percent(pos * 100f)
            if (fromUser && isTextTarget()) applyToTarget(
                onTime = { config.copy(timeYFrac = pos) },
                onDate = { config.copy(dateYFrac = pos) }
            )
        }
        binding.switchVisible.setOnCheckedChangeListener { _, checked ->
            if (isBinding) return@setOnCheckedChangeListener
            applyToTarget(
                onTime = { config.copy(timeVisible = checked) },
                onDate = { config.copy(dateVisible = checked) }
            )
            setTextControlsEnabled(checked)
        }
        binding.sliderWeight.addOnChangeListener { _, value, fromUser ->
            binding.valueWeight.text = value.toInt().toString()
            if (fromUser) updateActiveStyle { it.copy(weight = value.toInt()) }
        }
        binding.sliderOpacity.addOnChangeListener { _, value, fromUser ->
            binding.valueOpacity.text = percent(value)
            if (fromUser) updateActiveStyle { it.copy(opacity = value / 100f) }
        }
        binding.sliderStrokeWidth.addOnChangeListener { _, value, fromUser ->
            binding.valueStrokeWidth.text = percent(value)
            if (fromUser) updateActiveStyle { it.copy(strokeWidthFrac = value / 100f * WallpaperConfig.STROKE_WIDTH_MAX) }
        }
        binding.sliderGradientBottomAlpha.addOnChangeListener { _, value, fromUser ->
            binding.valueGradientAlpha.text = percent(value)
            if (fromUser) updateActiveStyle { it.copy(gradientBottomAlpha = value / 100f) }
        }
        binding.switchStroke.setOnCheckedChangeListener { _, checked ->
            if (!isBinding) updateActiveStyle { it.copy(strokeEnabled = checked) }
        }
        binding.switchGradient.setOnCheckedChangeListener { _, checked ->
            if (!isBinding) updateActiveStyle { it.copy(gradientEnabled = checked) }
        }
        binding.sliderBgScale.addOnChangeListener { _, value, fromUser ->
            binding.valueBgScale.text = percent(value)
            if (fromUser) {
                val s = WallpaperConfig.BG_SCALE_MIN +
                    value / 100f * (WallpaperConfig.BG_SCALE_MAX - WallpaperConfig.BG_SCALE_MIN)
                config = config.copy(bgScale = s)
                binding.previewView.config = config
                persistConfig()
            }
        }
        binding.sliderBgBlur.addOnChangeListener { _, value, fromUser ->
            binding.valueBgBlur.text = percent(value)
            if (fromUser) {
                config = config.copy(bgBlur = value / 100f)
                binding.previewView.config = config
                persistConfig()
            }
        }
    }

    private fun percent(value: Float): String = "${value.roundToInt()}%"

    private fun bindActiveControls() {
        if (!isTextTarget()) return
        isBinding = true
        val s = activeStyle()
        val (lo, hi) = sizeBounds()

        binding.switchVisible.setText(if (target == Layer.DATE) R.string.show_date else R.string.show_time)
        binding.switchVisible.isChecked = currentVisible()

        binding.sliderSize.value = ((currentSizeFrac() - lo) / (hi - lo) * 100f).coerceIn(0f, 100f)
        binding.sliderScaleX.value =
            rangeToSlider(currentScaleX(), WallpaperConfig.TEXT_SCALE_MIN, WallpaperConfig.TEXT_SCALE_MAX)
        binding.sliderScaleY.value =
            rangeToSlider(currentScaleY(), WallpaperConfig.TEXT_SCALE_MIN, WallpaperConfig.TEXT_SCALE_MAX)
        binding.sliderPosX.value =
            rangeToSlider(currentPosX(), WallpaperConfig.POSITION_MIN, WallpaperConfig.POSITION_MAX)
        binding.sliderPosY.value =
            rangeToSlider(currentPosY(), WallpaperConfig.POSITION_MIN, WallpaperConfig.POSITION_MAX)
        binding.sliderWeight.value = s.weight.coerceIn(WallpaperConfig.WEIGHT_MIN, WallpaperConfig.WEIGHT_MAX).toFloat()
        binding.sliderOpacity.value = (s.opacity * 100f).coerceIn(10f, 100f)
        binding.switchStroke.isChecked = s.strokeEnabled
        binding.sliderStrokeWidth.value = (s.strokeWidthFrac / WallpaperConfig.STROKE_WIDTH_MAX * 100f).coerceIn(0f, 100f)
        binding.switchGradient.isChecked = s.gradientEnabled
        binding.sliderGradientBottomAlpha.value = (s.gradientBottomAlpha * 100f).coerceIn(0f, 100f)
        // Assigning a slider the value it already holds fires no change event, so the badges are
        // written here rather than left to the listeners.
        binding.valueSize.text = percent(binding.sliderSize.value)
        binding.valueScaleX.text = percent(currentScaleX() * 100f)
        binding.valueScaleY.text = percent(currentScaleY() * 100f)
        binding.valuePosX.text = percent(currentPosX() * 100f)
        binding.valuePosY.text = percent(currentPosY() * 100f)
        binding.valueWeight.text = binding.sliderWeight.value.toInt().toString()
        binding.valueOpacity.text = percent(binding.sliderOpacity.value)
        binding.valueStrokeWidth.text = percent(binding.sliderStrokeWidth.value)
        binding.valueGradientAlpha.text = percent(binding.sliderGradientBottomAlpha.value)
        setTextControlsEnabled(currentVisible())
        isBinding = false
    }

    /**
     * Greys out and deactivates everything below the visibility switch when the layer is off.
     * Has to walk the whole subtree: disabling a container still lets its children take taps, which
     * would leave the font chips live under a greyed-out panel.
     */
    private fun setTextControlsEnabled(enabled: Boolean) {
        val alpha = if (enabled) 1f else 0.4f
        for (i in 1 until binding.contentText.childCount) {
            val child = binding.contentText.getChildAt(i)
            child.alpha = alpha
            setEnabledRecursive(child, enabled)
        }
    }

    private fun setEnabledRecursive(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) setEnabledRecursive(view.getChildAt(i), enabled)
        }
    }

    private fun bindBackgroundControls() {
        isBinding = true
        val range = WallpaperConfig.BG_SCALE_MAX - WallpaperConfig.BG_SCALE_MIN
        binding.sliderBgScale.value = ((config.bgScale - WallpaperConfig.BG_SCALE_MIN) / range * 100f).coerceIn(0f, 100f)
        binding.sliderBgBlur.value = (config.bgBlur * 100f).coerceIn(0f, 100f)
        binding.valueBgScale.text = percent(binding.sliderBgScale.value)
        binding.valueBgBlur.text = percent(binding.sliderBgBlur.value)
        binding.btnPickBackground.setText(
            if (config.backgroundImagePath == null) R.string.action_pick_background else R.string.action_change_image
        )
        binding.hintBackground.setText(
            if (config.backgroundImagePath == null) R.string.hint_no_background else R.string.hint_background_controls
        )
        isBinding = false
    }

    private fun bindForegroundControls() {
        binding.btnPickForeground.setText(
            if (config.foregroundImagePath == null) R.string.action_pick_foreground else R.string.action_change_image
        )
        binding.hintForeground.setText(
            if (config.foregroundImagePath == null) R.string.hint_no_foreground else R.string.hint_object_controls
        )
    }

    // ---- font chips -----------------------------------------------------------

    private fun buildFontChips() {
        val container = binding.fontContainer
        container.removeAllViews()
        val padH = dp(16); val padV = dp(9); val marginPx = dp(8)
        for (option in FontCatalog.OPTIONS) {
            val selected = option.key == activeStyle().fontFamily
            val chip = TextView(this).apply {
                text = option.label
                typeface = Typeface.create(option.family, Typeface.NORMAL)
                textScaleX = option.scaleX
                textSize = 14f
                setPadding(padH, padV, padH, padV)
                setTextColor(ContextCompat.getColor(this@EditorActivity,
                    if (selected) R.color.accent_on_primary else R.color.text_secondary))
                background = chipBg(selected)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = marginPx }
                setOnClickListener {
                    updateActiveStyle { it.copy(fontFamily = option.key) }
                    buildFontChips()
                }
            }
            container.addView(chip)
        }
    }

    private fun chipBg(selected: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(22).toFloat()
        setColor(ContextCompat.getColor(this@EditorActivity,
            if (selected) R.color.accent_primary else R.color.surface_dark_variant))
        if (!selected) setStroke(dp(1), ContextCompat.getColor(this@EditorActivity, R.color.outline_subtle))
    }

    // ---- colour rows ----------------------------------------------------------

    private fun rebuildColorRows() {
        val s = activeStyle()
        populateColors(binding.colorContainer, s.color) { c ->
            updateActiveStyle { it.copy(color = c) }; rebuildColorRows()
        }
        populateColors(binding.strokeColorContainer, s.strokeColor) { c ->
            updateActiveStyle { it.copy(strokeColor = c) }; rebuildColorRows()
        }
        populateColors(binding.gradientTopContainer, s.gradientTopColor) { c ->
            updateActiveStyle { it.copy(gradientTopColor = c) }; rebuildColorRows()
        }
        populateColors(binding.gradientBottomContainer, s.gradientBottomColor) { c ->
            updateActiveStyle { it.copy(gradientBottomColor = c) }; rebuildColorRows()
        }
    }

    private fun populateColors(container: LinearLayout, selected: Int, onPick: (Int) -> Unit) {
        container.removeAllViews()
        val size = dp(34); val marginPx = dp(10)
        for (color in WallpaperConfig.PRESET_COLORS) {
            val v = View(this)
            v.layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = marginPx }
            v.background = swatchBg(color, color == selected)
            v.setOnClickListener { onPick(color) }
            container.addView(v)
        }
    }

    private fun swatchBg(color: Int, selected: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(
            dp(if (selected) 3 else 1),
            ContextCompat.getColor(this@EditorActivity,
                if (selected) R.color.accent_primary else R.color.outline_strong)
        )
    }

    // ---- effects toggle -------------------------------------------------------

    private fun setupEffectsToggle() {
        binding.btnToggleEffects.setOnClickListener {
            val show = binding.contentEffects.visibility != View.VISIBLE
            binding.contentEffects.visibility = if (show) View.VISIBLE else View.GONE
            binding.btnToggleEffects.setIconResource(
                if (show) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down
            )
        }
    }

    // ---- buttons --------------------------------------------------------------

    private fun setupButtons() {
        val imageOnly = ActivityResultContracts.PickVisualMedia.ImageOnly
        binding.btnBack.setOnClickListener { finish() }
        binding.btnLockGestures.setOnClickListener { toggleGestureLock() }
        binding.btnResetLayout.setOnClickListener { resetLayoutOfTarget() }
        binding.btnPickBackground.setOnClickListener {
            pickBackgroundLauncher.launch(PickVisualMediaRequest(imageOnly))
        }
        binding.btnPickForeground.setOnClickListener {
            pickForegroundLauncher.launch(PickVisualMediaRequest(imageOnly))
        }
        binding.btnSetWallpaper.setOnClickListener { applyCurrentScreen() }
    }

    /**
     * Assigns this preset to the screen currently being edited. Lock and home are set separately so
     * one preset can serve the lock screen while another serves the home screen.
     */
    private fun applyCurrentScreen() {
        persistConfig()
        PresetRepository.setActiveId(this, screenType, presetId)

        // Older builds published the lock screen as a still image, which is why its clock could
        // never advance. That image outranks the live wallpaper, so it has to go.
        try {
            WallpaperManager.getInstance(this).clear(WallpaperManager.FLAG_LOCK)
        } catch (e: Exception) {
            // Not every device permits clearing it; the live wallpaper simply stays hidden there.
        }

        updateApplyUi()

        if (isLiveWallpaperActive()) {
            // Already running — the change takes effect immediately, no system picker needed.
            Toast.makeText(
                this,
                if (screenType == SCREEN_LOCK) R.string.toast_set_lock else R.string.toast_set_home,
                Toast.LENGTH_SHORT
            ).show()
        } else {
            openLiveWallpaperPicker()
        }
    }

    private fun isLiveWallpaperActive(): Boolean =
        WallpaperManager.getInstance(this).wallpaperInfo?.component ==
            ComponentName(this, com.example.depthwp.wallpaper.DepthWallpaperService::class.java)

    private fun openLiveWallpaperPicker() {
        try {
            startActivity(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(
                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(this@EditorActivity, com.example.depthwp.wallpaper.DepthWallpaperService::class.java)
                )
            })
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.error_wallpaper_intent, Toast.LENGTH_SHORT).show()
        }
    }

    /** Keeps the apply button's wording and the status line in step with the selected screen. */
    private fun updateApplyUi() {
        binding.btnSetWallpaper.setText(
            if (screenType == SCREEN_LOCK) R.string.action_set_lock else R.string.action_set_home
        )
        val isLock = PresetRepository.getActiveId(this, SCREEN_LOCK) == presetId
        val isHome = PresetRepository.getActiveId(this, SCREEN_HOME) == presetId
        binding.textActiveStatus.setText(
            when {
                isLock && isHome -> R.string.status_active_both
                isLock -> R.string.status_active_lock
                isHome -> R.string.status_active_home
                else -> R.string.status_active_none
            }
        )
    }

    private fun toggleGestureLock() {
        val locked = binding.previewView.gesturesEnabled
        binding.previewView.gesturesEnabled = !locked
        binding.btnLockGestures.setImageResource(
            if (locked) R.drawable.ic_lock else R.drawable.ic_lock_open
        )
        Toast.makeText(
            this,
            if (locked) R.string.toast_gestures_locked else R.string.toast_gestures_unlocked,
            Toast.LENGTH_SHORT
        ).show()
    }

    /** Puts the selected text layer back to its default size, stretch and position. */
    private fun resetLayoutOfTarget() {
        val d = WallpaperConfig.DEFAULT
        applyToTarget(
            onTime = {
                config.copy(
                    timeSizeFrac = d.timeSizeFrac, timeScaleX = d.timeScaleX, timeScaleY = d.timeScaleY,
                    timeXFrac = d.timeXFrac, timeYFrac = d.timeYFrac
                )
            },
            onDate = {
                config.copy(
                    dateSizeFrac = d.dateSizeFrac, dateScaleX = d.dateScaleX, dateScaleY = d.dateScaleY,
                    dateXFrac = d.dateXFrac, dateYFrac = d.dateYFrac
                )
            }
        )
        bindActiveControls()
    }

    private fun handleBackgroundPicked(uri: android.net.Uri) {
        lifecycleScope.launch {
            val path = withContext(Dispatchers.IO) {
                ImageStore.saveBackgroundImage(applicationContext, uri, imageSubDir())
            }
            if (path == null) {
                Toast.makeText(this@EditorActivity, R.string.error_image_load, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val bitmap = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path) }
            config = config.copy(backgroundImagePath = path)
            binding.previewView.config = config
            binding.previewView.backgroundBitmap = bitmap
            persistConfig()
            bindBackgroundControls()
        }
    }

    private fun handleForegroundPicked(uri: android.net.Uri) {
        lifecycleScope.launch {
            val path = withContext(Dispatchers.IO) {
                ImageStore.saveForegroundImage(applicationContext, uri, imageSubDir())
            }
            if (path == null) {
                Toast.makeText(this@EditorActivity, R.string.error_image_load, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val bitmap = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path) }
            config = config.copy(foregroundImagePath = path)
            binding.previewView.config = config
            binding.previewView.foregroundBitmap = bitmap
            persistConfig()
            binding.targetGroup.check(binding.tabObject.id)
            bindForegroundControls()
        }
    }

    private fun loadBitmaps() {
        lifecycleScope.launch {
            val bgBmp = withContext(Dispatchers.IO) {
                config.backgroundImagePath?.let { BitmapFactory.decodeFile(it) }
            }
            val fgBmp = withContext(Dispatchers.IO) {
                config.foregroundImagePath?.let { BitmapFactory.decodeFile(it) }
            }
            binding.previewView.backgroundBitmap = bgBmp
            binding.previewView.foregroundBitmap = fgBmp
        }
    }

    private fun persistConfig() {
        PresetRepository.saveConfig(this, presetId, screenType, config)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
