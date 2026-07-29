package com.example.depthwp.update

import android.content.Context

/**
 * Where to look for updates. Kept in preferences rather than baked into the build so the address
 * can change — new NAS IP, different folder, added password — without rebuilding the app.
 */
object UpdateSettings {

    private const val PREFS = "update_settings"
    private const val KEY_URL = "manifest_url"
    private const val KEY_USER = "username"
    private const val KEY_PASSWORD = "password"
    private const val KEY_AUTO_CHECK = "auto_check"

    data class Values(
        val manifestUrl: String = "",
        val username: String = "",
        val password: String = "",
        val autoCheck: Boolean = true
    ) {
        val isConfigured: Boolean get() = manifestUrl.isNotBlank()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): Values {
        val p = prefs(context)
        return Values(
            manifestUrl = p.getString(KEY_URL, "").orEmpty(),
            username = p.getString(KEY_USER, "").orEmpty(),
            password = p.getString(KEY_PASSWORD, "").orEmpty(),
            autoCheck = p.getBoolean(KEY_AUTO_CHECK, true)
        )
    }

    fun save(context: Context, values: Values) {
        prefs(context).edit()
            .putString(KEY_URL, values.manifestUrl.trim())
            .putString(KEY_USER, values.username.trim())
            .putString(KEY_PASSWORD, values.password)
            .putBoolean(KEY_AUTO_CHECK, values.autoCheck)
            .apply()
    }
}
