package com.example.depthwp.dashboard

import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.depthwp.R
import com.example.depthwp.data.BitmapUtils
import com.example.depthwp.data.PresetRepository
import com.example.depthwp.editor.EditorActivity
import com.example.depthwp.model.Preset
import com.example.depthwp.model.WallpaperConfig
import com.example.depthwp.render.WallpaperRenderer
import com.example.depthwp.update.UpdateChecker
import com.example.depthwp.update.UpdateSettings
import com.example.depthwp.wallpaper.DepthWallpaperService
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DashboardActivity : AppCompatActivity() {

    private companion object {
        const val SCREEN_LOCK = "lock"
        const val SCREEN_HOME = "home"
        const val THUMB_WIDTH = 360
        const val THUMB_HEIGHT = 640
        const val GRID_COLUMNS = 2
    }

    private lateinit var grid: RecyclerView
    private lateinit var emptyState: View
    private lateinit var subtitle: TextView
    private lateinit var fab: ExtendedFloatingActionButton

    private val adapter = PresetAdapter()
    private val thumbnails = HashMap<String, Bitmap>()

    /** Id of the card whose action overlay is currently open, if any. */
    private var expandedId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        PresetRepository.migrateIfNeeded(this)

        grid = findViewById(R.id.preset_grid)
        emptyState = findViewById(R.id.empty_state)
        subtitle = findViewById(R.id.header_subtitle)
        fab = findViewById(R.id.fab_add)

        grid.layoutManager = GridLayoutManager(this, GRID_COLUMNS)
        grid.adapter = adapter
        grid.itemAnimator = DefaultItemAnimator()

        fab.setOnClickListener { createPreset() }
        findViewById<View>(R.id.btn_create_first).setOnClickListener { createPreset() }
        findViewById<View>(R.id.btn_settings).setOnClickListener { showUpdateSettings() }

        val updateSettings = UpdateSettings.load(this)
        if (updateSettings.isConfigured && updateSettings.autoCheck) {
            checkForUpdate(updateSettings, announceResult = false)
        }

        // The FAB gets out of the way while scrolling down so it never hides the last row.
        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy > 6) fab.shrink() else if (dy < -6) fab.extend()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val presets = PresetRepository.listPresets(this)
        val activeLockId = PresetRepository.getActiveId(this, PresetRepository.SCREEN_LOCK)
        val activeHomeId = PresetRepository.getActiveId(this, PresetRepository.SCREEN_HOME)

        expandedId = null
        adapter.submit(presets, activeLockId, activeHomeId)

        val isEmpty = presets.isEmpty()
        emptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
        grid.visibility = if (isEmpty) View.GONE else View.VISIBLE
        fab.visibility = if (isEmpty) View.GONE else View.VISIBLE
        // The empty state carries its own copy, so the header subtitle steps aside there.
        subtitle.visibility = if (isEmpty) View.GONE else View.VISIBLE
        subtitle.text = resources.getQuantityString(
            R.plurals.dashboard_count, presets.size, presets.size
        )

        thumbnails.clear()
        presets.forEach { renderThumbnail(it) }
    }

    private fun createPreset() {
        val count = PresetRepository.listPresets(this).size + 1
        val preset = PresetRepository.createPreset(this, getString(R.string.default_preset_name, count))
        openEditor(preset.id)
    }

    private fun openEditor(presetId: String) {
        startActivity(Intent(this, EditorActivity::class.java).apply {
            putExtra(EditorActivity.EXTRA_PRESET_ID, presetId)
        })
    }

    /** The card's check button assigns the preset to both screens; the editor sets them apart. */
    private fun applyPreset(preset: Preset) {
        PresetRepository.setActiveForAllScreens(this, preset.id)

        // Any still image previously published for the lock screen would outrank the live
        // wallpaper — and its clock could never advance.
        try {
            WallpaperManager.getInstance(this).clear(WallpaperManager.FLAG_LOCK)
        } catch (e: Exception) {
            // Some devices refuse; the live wallpaper then just doesn't reach the lock screen.
        }

        val alreadyRunning = WallpaperManager.getInstance(this).wallpaperInfo?.component ==
            ComponentName(this, DepthWallpaperService::class.java)

        if (alreadyRunning) {
            Toast.makeText(this, R.string.toast_applied, Toast.LENGTH_SHORT).show()
        } else {
            try {
                startActivity(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                    putExtra(
                        WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                        ComponentName(this@DashboardActivity, DepthWallpaperService::class.java)
                    )
                })
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, R.string.error_wallpaper_intent, Toast.LENGTH_SHORT).show()
            }
        }
        reload()
    }

    private fun confirmDelete(preset: Preset) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm_delete_title)
            .setMessage(R.string.confirm_delete_message)
            .setPositiveButton(R.string.confirm_delete_yes) { _, _ ->
                PresetRepository.deletePreset(this, preset.id)
                Toast.makeText(this, R.string.toast_deleted, Toast.LENGTH_SHORT).show()
                reload()
            }
            .setNegativeButton(R.string.confirm_delete_no, null)
            .show()
    }

    private fun promptRename(preset: Preset) {
        val input = EditText(this).apply {
            setText(preset.name)
            setSelection(text.length)
            setTextColor(getColor(R.color.text_primary))
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val wrapper = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename_title)
            .setView(wrapper)
            .setPositiveButton(R.string.rename_save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    PresetRepository.renamePreset(this, preset.id, name)
                    reload()
                }
            }
            .setNegativeButton(R.string.confirm_delete_no, null)
            .show()
    }

    private fun renderThumbnail(preset: Preset) {
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                try {
                    val config = PresetRepository.loadConfig(this@DashboardActivity, preset.id, SCREEN_HOME)
                    val bitmap = Bitmap.createBitmap(THUMB_WIDTH, THUMB_HEIGHT, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    val bgBmp = config.backgroundImagePath?.let {
                        BitmapUtils.decodeFileForDisplay(it, THUMB_WIDTH, THUMB_HEIGHT, allowRgb565 = true)
                    }
                    val fgBmp = config.foregroundImagePath?.let {
                        BitmapUtils.decodeFileForDisplay(it, THUMB_WIDTH, THUMB_HEIGHT, allowRgb565 = false)
                    }
                    WallpaperRenderer.draw(canvas, THUMB_WIDTH, THUMB_HEIGHT, config, bgBmp, fgBmp)
                    bgBmp?.recycle()
                    fgBmp?.recycle()
                    bitmap
                } catch (_: Exception) {
                    null
                }
            } ?: return@launch

            thumbnails[preset.id] = bmp
            val index = adapter.indexOf(preset.id)
            if (index >= 0) adapter.notifyItemChanged(index)
        }
    }

    // ---- self-update -------------------------------------------------------

    private fun showUpdateSettings() {
        val view = layoutInflater.inflate(R.layout.dialog_update_settings, null)
        val inputUrl = view.findViewById<EditText>(R.id.input_url)
        val inputUser = view.findViewById<EditText>(R.id.input_user)
        val inputPassword = view.findViewById<EditText>(R.id.input_password)
        val switchAuto = view.findViewById<MaterialSwitch>(R.id.switch_auto_check)
        val textVersion = view.findViewById<TextView>(R.id.text_version)
        val btnCheck = view.findViewById<View>(R.id.btn_check_now)

        val current = UpdateSettings.load(this)
        inputUrl.setText(current.manifestUrl)
        inputUser.setText(current.username)
        inputPassword.setText(current.password)
        switchAuto.isChecked = current.autoCheck
        textVersion.text = getString(
            R.string.settings_current_version,
            UpdateChecker.currentVersionName(this),
            UpdateChecker.currentVersionCode(this)
        )

        fun readInputs() = UpdateSettings.Values(
            manifestUrl = inputUrl.text.toString(),
            username = inputUser.text.toString(),
            password = inputPassword.text.toString(),
            autoCheck = switchAuto.isChecked
        )

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_title)
            .setView(view)
            .setPositiveButton(R.string.settings_save) { _, _ -> UpdateSettings.save(this, readInputs()) }
            .setNegativeButton(R.string.confirm_delete_no, null)
            .create()

        // Checking now implies saving, otherwise it would test an address that isn't stored yet.
        btnCheck.setOnClickListener {
            val values = readInputs()
            UpdateSettings.save(this, values)
            if (!values.isConfigured) {
                Toast.makeText(this, R.string.update_no_url, Toast.LENGTH_SHORT).show()
            } else {
                dialog.dismiss()
                checkForUpdate(values, announceResult = true)
            }
        }
        dialog.show()
    }

    /**
     * Looks for a newer version. [announceResult] separates the two entry points: the automatic
     * check at startup stays silent unless there is something to install, while an explicit "check
     * now" always reports back — including "you're up to date" and failures.
     */
    private fun checkForUpdate(settings: UpdateSettings.Values, announceResult: Boolean) {
        lifecycleScope.launch {
            when (val result = UpdateChecker.fetchManifest(settings)) {
                is UpdateChecker.FetchResult.Success -> {
                    val info = result.info
                    if (info.versionCode > UpdateChecker.currentVersionCode(this@DashboardActivity)) {
                        promptInstall(info, settings)
                    } else if (announceResult) {
                        toast(R.string.update_none)
                    }
                }
                UpdateChecker.FetchResult.GotWebPage -> if (announceResult) {
                    // Worth a dialog rather than a toast: the fix isn't obvious from the symptom.
                    MaterialAlertDialogBuilder(this@DashboardActivity)
                        .setTitle(R.string.update_webpage_title)
                        .setMessage(R.string.update_webpage_message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
                UpdateChecker.FetchResult.BadJson -> if (announceResult) toast(R.string.update_bad_json)
                UpdateChecker.FetchResult.NotReachable -> if (announceResult) toast(R.string.update_failed)
            }
        }
    }

    private fun promptInstall(info: UpdateChecker.UpdateInfo, settings: UpdateSettings.Values) {
        val message = if (info.changelog.isBlank()) {
            getString(R.string.update_available_message_plain, info.versionName)
        } else {
            getString(R.string.update_available_message, info.versionName, info.changelog)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_available_title)
            .setMessage(message)
            .setPositiveButton(R.string.update_install) { _, _ -> startDownload(info, settings) }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    private fun startDownload(info: UpdateChecker.UpdateInfo, settings: UpdateSettings.Values) {
        if (!UpdateChecker.canInstall(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.update_permission_title)
                .setMessage(R.string.update_permission_message)
                .setPositiveButton(R.string.update_permission_open) { _, _ ->
                    startActivity(UpdateChecker.installPermissionIntent(this))
                }
                .setNegativeButton(R.string.confirm_delete_no, null)
                .show()
            return
        }

        val progress = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_available_title)
            .setMessage(getString(R.string.update_downloading, 0))
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            val apk = UpdateChecker.downloadApk(this@DashboardActivity, info, settings) { percent ->
                runOnUiThread { progress.setMessage(getString(R.string.update_downloading, percent)) }
            }
            progress.dismiss()
            if (apk == null) {
                Toast.makeText(this@DashboardActivity, R.string.update_download_failed, Toast.LENGTH_LONG).show()
            } else {
                UpdateChecker.installApk(this@DashboardActivity, apk)
            }
        }
    }

    // -----------------------------------------------------------------------

    private inner class PresetAdapter : RecyclerView.Adapter<PresetAdapter.VH>() {

        private var presets: List<Preset> = emptyList()
        private var activeLockId: String? = null
        private var activeHomeId: String? = null

        fun submit(newPresets: List<Preset>, lockId: String?, homeId: String?) {
            presets = newPresets
            activeLockId = lockId
            activeHomeId = homeId
            notifyDataSetChanged()
        }

        fun indexOf(id: String): Int = presets.indexOfFirst { it.id == id }

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val card: MaterialCardView = view.findViewById(R.id.card_root)
            val thumbnail: ImageView = view.findViewById(R.id.thumbnail)
            val name: TextView = view.findViewById(R.id.preset_name)
            val badge: TextView = view.findViewById(R.id.badge_active)
            val overlay: View = view.findViewById(R.id.action_overlay)
            val btnEdit: View = view.findViewById(R.id.btn_edit)
            val btnDelete: View = view.findViewById(R.id.btn_delete)
            val btnApply: View = view.findViewById(R.id.btn_apply)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_preset, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val preset = presets[position]
            val isLock = preset.id == activeLockId
            val isHome = preset.id == activeHomeId
            val isActive = isLock || isHome
            val isExpanded = preset.id == expandedId

            holder.name.text = preset.name.ifEmpty { getString(R.string.default_preset_name, position + 1) }
            // The badge says *where* it's in use, since the two screens can differ.
            holder.badge.visibility = if (isActive) View.VISIBLE else View.GONE
            holder.badge.setText(
                when {
                    isLock && isHome -> R.string.badge_active
                    isLock -> R.string.badge_active_lock
                    else -> R.string.badge_active_home
                }
            )
            holder.card.strokeColor = getColor(if (isActive) R.color.accent_primary else R.color.outline_subtle)
            holder.card.strokeWidth = dp(if (isActive) 2 else 1)

            // Thumbnails are 9:16, matched to the phone screen they represent. Derived from the
            // screen width rather than grid.width, which is still 0 on the first bind pass.
            // 28dp = the grid's horizontal padding, 12dp = the item's own padding.
            val columnWidth = (resources.displayMetrics.widthPixels - dp(28)) / GRID_COLUMNS
            val thumbWidth = columnWidth - dp(12)
            holder.thumbnail.layoutParams = holder.thumbnail.layoutParams.apply {
                height = (thumbWidth * THUMB_HEIGHT.toFloat() / THUMB_WIDTH).toInt()
            }
            holder.thumbnail.setImageBitmap(thumbnails[preset.id])

            holder.overlay.visibility = if (isExpanded) View.VISIBLE else View.GONE
            holder.overlay.alpha = if (isExpanded) 1f else 0f

            holder.card.setOnClickListener {
                val previous = expandedId
                expandedId = if (isExpanded) null else preset.id
                // Repaint the card that closed and the one that opened, nothing else.
                listOfNotNull(previous, expandedId)
                    .map { id -> indexOf(id) }
                    .filter { it >= 0 }
                    .distinct()
                    .forEach { notifyItemChanged(it) }
            }
            holder.card.setOnLongClickListener {
                promptRename(preset)
                true
            }

            holder.btnEdit.setOnClickListener { expandedId = null; openEditor(preset.id) }
            holder.btnDelete.setOnClickListener { expandedId = null; confirmDelete(preset) }
            holder.btnApply.setOnClickListener { expandedId = null; applyPreset(preset) }
        }

        override fun getItemCount() = presets.size
    }

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_LONG).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
