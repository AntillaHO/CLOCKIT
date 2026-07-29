package com.example.depthwp.data

import android.content.Context
import com.example.depthwp.model.Preset
import com.example.depthwp.model.WallpaperConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

object PresetRepository {

    private const val META_PREFS = "preset_meta"
    private const val KEY_PRESETS = "presets_json"
    private const val KEY_ACTIVE_ID = "active_id"
    private const val KEY_ACTIVE_LOCK_ID = "active_lock_id"
    private const val KEY_ACTIVE_HOME_ID = "active_home_id"
    private const val KEY_CHANGED = "last_changed"

    const val SCREEN_LOCK = "lock"
    const val SCREEN_HOME = "home"

    fun prefsNameFor(presetId: String, screen: String): String = "preset_${presetId}_$screen"

    fun imageDirFor(presetId: String, screen: String): String = "presets/$presetId/$screen"

    private fun metaPrefs(context: Context) =
        context.applicationContext.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)

    /**
     * Lock screen and home screen each point at their own preset, so one preset can supply the lock
     * screen while another supplies the home screen. Falls back to the single id written by older
     * versions of the app.
     */
    fun getActiveId(context: Context, screen: String): String? {
        val p = metaPrefs(context)
        val key = if (screen == SCREEN_LOCK) KEY_ACTIVE_LOCK_ID else KEY_ACTIVE_HOME_ID
        return p.getString(key, null) ?: p.getString(KEY_ACTIVE_ID, null)
    }

    fun setActiveId(context: Context, screen: String, id: String) {
        val key = if (screen == SCREEN_LOCK) KEY_ACTIVE_LOCK_ID else KEY_ACTIVE_HOME_ID
        metaPrefs(context).edit()
            .putString(key, id)
            .putLong(KEY_CHANGED, System.currentTimeMillis())
            .apply()
    }

    /** Assigns a preset to both screens at once. */
    fun setActiveForAllScreens(context: Context, id: String) {
        metaPrefs(context).edit()
            .putString(KEY_ACTIVE_LOCK_ID, id)
            .putString(KEY_ACTIVE_HOME_ID, id)
            .putLong(KEY_CHANGED, System.currentTimeMillis())
            .apply()
    }

    fun isActiveForAnyScreen(context: Context, id: String): Boolean =
        getActiveId(context, SCREEN_LOCK) == id || getActiveId(context, SCREEN_HOME) == id

    fun notifyChanged(context: Context) {
        metaPrefs(context).edit()
            .putLong(KEY_CHANGED, System.currentTimeMillis())
            .apply()
    }

    fun listPresets(context: Context): List<Preset> {
        val json = metaPrefs(context).getString(KEY_PRESETS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                Preset(
                    id = obj.getString("id"),
                    name = obj.optString("name", ""),
                    createdAt = obj.optLong("createdAt", 0L)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getPreset(context: Context, id: String): Preset? =
        listPresets(context).firstOrNull { it.id == id }

    private fun savePresetList(context: Context, presets: List<Preset>) {
        val arr = JSONArray()
        for (p in presets) {
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("createdAt", p.createdAt)
            })
        }
        metaPrefs(context).edit().putString(KEY_PRESETS, arr.toString()).apply()
    }

    fun createPreset(context: Context, name: String = ""): Preset {
        val preset = Preset(
            id = UUID.randomUUID().toString().take(8),
            name = name,
            createdAt = System.currentTimeMillis()
        )
        val list = listPresets(context).toMutableList()
        list.add(preset)
        savePresetList(context, list)
        // Initialize both screen configs with defaults
        ConfigRepository.save(context, WallpaperConfig.DEFAULT, prefsNameFor(preset.id, "lock"))
        ConfigRepository.save(context, WallpaperConfig.DEFAULT, prefsNameFor(preset.id, "home"))
        return preset
    }

    fun deletePreset(context: Context, id: String) {
        val list = listPresets(context).toMutableList()
        list.removeAll { it.id == id }
        savePresetList(context, list)
        // Clear prefs files
        context.applicationContext.getSharedPreferences(prefsNameFor(id, "lock"), Context.MODE_PRIVATE)
            .edit().clear().apply()
        context.applicationContext.getSharedPreferences(prefsNameFor(id, "home"), Context.MODE_PRIVATE)
            .edit().clear().apply()
        ImageStore.deletePresetImages(context, id)
        val remaining = listPresets(context)
        val replacement = remaining.firstOrNull()?.id
        val editor = metaPrefs(context).edit()
        for (screen in listOf(SCREEN_LOCK, SCREEN_HOME)) {
            if (getActiveId(context, screen) == id) {
                val key = if (screen == SCREEN_LOCK) KEY_ACTIVE_LOCK_ID else KEY_ACTIVE_HOME_ID
                if (replacement != null) editor.putString(key, replacement) else editor.remove(key)
            }
        }
        if (replacement == null) editor.remove(KEY_ACTIVE_ID)
        editor.putLong(KEY_CHANGED, System.currentTimeMillis()).apply()
    }

    fun renamePreset(context: Context, id: String, newName: String) {
        val list = listPresets(context).map {
            if (it.id == id) it.copy(name = newName) else it
        }
        savePresetList(context, list)
    }

    fun loadConfig(context: Context, presetId: String, screen: String): WallpaperConfig =
        ConfigRepository.load(context, prefsNameFor(presetId, screen))

    fun saveConfig(context: Context, presetId: String, screen: String, config: WallpaperConfig) {
        ConfigRepository.save(context, config, prefsNameFor(presetId, screen))
        notifyChanged(context)
    }

    /** Config the wallpaper should render for [screen], or defaults if nothing is assigned yet. */
    fun loadActiveConfig(context: Context, screen: String): WallpaperConfig {
        val activeId = getActiveId(context, screen) ?: return WallpaperConfig.DEFAULT
        return loadConfig(context, activeId, screen)
    }

    fun migrateIfNeeded(context: Context) {
        if (listPresets(context).isNotEmpty()) return
        val oldPrefs = context.applicationContext.getSharedPreferences(ConfigRepository.PREFS_NAME, Context.MODE_PRIVATE)
        if (!oldPrefs.contains("time_x_frac") && !oldPrefs.contains("background_image_path")) return

        val oldConfig = ConfigRepository.load(context, ConfigRepository.PREFS_NAME)
        val preset = createPreset(context, "Mein Wallpaper")

        // Copy images to preset directories for both lock and home
        var lockConfig = oldConfig
        var homeConfig = oldConfig
        oldConfig.backgroundImagePath?.let { path ->
            val lockDir = File(context.filesDir, imageDirFor(preset.id, "lock"))
            val homeDir = File(context.filesDir, imageDirFor(preset.id, "home"))
            val lockPath = ImageStore.copyFile(path, lockDir, "background_image.jpg")
            val homePath = ImageStore.copyFile(path, homeDir, "background_image.jpg")
            lockConfig = lockConfig.copy(backgroundImagePath = lockPath ?: path)
            homeConfig = homeConfig.copy(backgroundImagePath = homePath ?: path)
        }
        oldConfig.foregroundImagePath?.let { path ->
            val lockDir = File(context.filesDir, imageDirFor(preset.id, "lock"))
            val homeDir = File(context.filesDir, imageDirFor(preset.id, "home"))
            val lockPath = ImageStore.copyFile(path, lockDir, "foreground_image.png")
            val homePath = ImageStore.copyFile(path, homeDir, "foreground_image.png")
            lockConfig = lockConfig.copy(foregroundImagePath = lockPath ?: path)
            homeConfig = homeConfig.copy(foregroundImagePath = homePath ?: path)
        }

        saveConfig(context, preset.id, SCREEN_LOCK, lockConfig)
        saveConfig(context, preset.id, SCREEN_HOME, homeConfig)
        setActiveForAllScreens(context, preset.id)
    }
}
