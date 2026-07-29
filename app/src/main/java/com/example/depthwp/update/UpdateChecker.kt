package com.example.depthwp.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Base64
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update against a file the user hosts themselves (a NAS share, webspace, anything that
 * answers HTTP GET).
 *
 * The flow is deliberately dumb so the server side stays hand-editable: fetch a small JSON
 * manifest, compare its versionCode against the installed one, download the APK it points at, hand
 * it to the system installer. Android always shows its own install confirmation — there is no way
 * around that outside of the Play Store or a device-owner setup, so "automatic" here means the app
 * finds and fetches the update on its own, not that it installs unattended.
 *
 * Expected manifest shape:
 * ```json
 * { "versionCode": 2, "versionName": "1.1", "apkUrl": "http://nas.local/dwp/app.apk",
 *   "changelog": "Blur sanfter" }
 * ```
 * A relative "apkUrl" is resolved against the manifest's own URL, so both files can simply sit in
 * the same folder.
 */
object UpdateChecker {

    private const val CONNECT_TIMEOUT_MS = 8000
    private const val READ_TIMEOUT_MS = 20000

    /** Guards against a mistyped URL pointing at something huge. */
    private const val MAX_MANIFEST_BYTES = 64 * 1024

    data class UpdateInfo(
        val versionCode: Long,
        val versionName: String,
        val apkUrl: String,
        val changelog: String
    )

    /**
     * Outcome of a manifest lookup. The failure cases are told apart on purpose: with a NAS share
     * link the usual mistake is pointing at the *preview page* instead of the file itself, and
     * "server not reachable" would send you hunting in entirely the wrong place.
     */
    sealed class FetchResult {
        data class Success(val info: UpdateInfo) : FetchResult()

        /** No answer at all — NAS asleep, wrong address, no network. */
        object NotReachable : FetchResult()

        /** The address answered with a web page rather than the raw file. */
        object GotWebPage : FetchResult()

        /** It was a file, but not a manifest this app understands. */
        object BadJson : FetchResult()
    }

    private const val MAX_REDIRECTS = 5

    fun currentVersionCode(context: Context): Long = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    } catch (e: PackageManager.NameNotFoundException) {
        0L
    }

    fun currentVersionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (e: PackageManager.NameNotFoundException) {
        "?"
    }

    /**
     * Downloads and parses the manifest. Returns null on any failure — a NAS that is asleep or out
     * of reach is the normal case when you're not at home, not something worth alarming about.
     */
    suspend fun fetchManifest(settings: UpdateSettings.Values): FetchResult = withContext(Dispatchers.IO) {
        val manifestUrl = settings.manifestUrl.trim()
        if (manifestUrl.isEmpty()) return@withContext FetchResult.NotReachable

        var connection: HttpURLConnection? = null
        try {
            connection = open(manifestUrl, settings)
            if (connection.responseCode !in 200..299) return@withContext FetchResult.NotReachable

            val contentType = connection.contentType.orEmpty()
            val body = connection.inputStream.use { readBounded(it, MAX_MANIFEST_BYTES) }
                ?: return@withContext FetchResult.NotReachable

            // A share link that opens a download page answers with HTML. That is the single most
            // common misconfiguration here, so it gets its own diagnosis.
            if (contentType.contains("text/html", true) || body.trimStart().startsWith("<")) {
                return@withContext FetchResult.GotWebPage
            }

            // Redirects can land elsewhere, so relative apkUrls resolve against the final address.
            val effectiveUrl = connection.url?.toString() ?: manifestUrl
            parseManifest(body, effectiveUrl)?.let { FetchResult.Success(it) } ?: FetchResult.BadJson
        } catch (e: Exception) {
            FetchResult.NotReachable
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Reads at most [limit] bytes, returning null if the response is larger. A mistyped URL
     * pointing at a video file shouldn't be pulled into memory just to fail parsing afterwards.
     */
    private fun readBounded(stream: java.io.InputStream, limit: Int): String? {
        val buffer = ByteArray(8 * 1024)
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            if (out.size() + read > limit) return null
            out.write(buffer, 0, read)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun parseManifest(body: String, manifestUrl: String): UpdateInfo? = try {
        val json = JSONObject(body)
        val apkUrl = json.getString("apkUrl").let { raw ->
            // Relative paths let both files live side by side without hardcoding the host twice.
            if (raw.startsWith("http://") || raw.startsWith("https://")) raw
            else URL(URL(manifestUrl), raw).toString()
        }
        UpdateInfo(
            versionCode = json.getLong("versionCode"),
            versionName = json.optString("versionName", "?"),
            apkUrl = apkUrl,
            changelog = json.optString("changelog", "")
        )
    } catch (e: Exception) {
        null
    }

    /** Downloads the APK into the app's cache. Returns the file, or null if anything went wrong. */
    suspend fun downloadApk(
        context: Context,
        info: UpdateInfo,
        settings: UpdateSettings.Values,
        onProgress: (Int) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = open(info.apkUrl, settings)
            if (connection.responseCode !in 200..299) return@withContext null

            val total = connection.contentLength.toLong()
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            // One fixed name: the previous download is dead weight once a newer one exists.
            val target = File(dir, "update.apk")

            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) onProgress(((downloaded * 100) / total).toInt().coerceIn(0, 100))
                    }
                }
            }
            if (target.length() <= 0L) null else target
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Opens [url], following redirects by hand.
     *
     * HttpURLConnection's own redirect handling silently gives up when the target switches protocol
     * — which is exactly what a NAS does when it bounces a plain http:// request to https://. The
     * request would appear to fail for no visible reason, so the hops are walked manually instead.
     */
    private fun open(url: String, settings: UpdateSettings.Values): HttpURLConnection {
        var current = url
        repeat(MAX_REDIRECTS) {
            val connection = connect(current, settings)
            if (connection.responseCode in 300..399) {
                val location = connection.getHeaderField("Location")
                if (!location.isNullOrBlank()) {
                    current = URL(URL(current), location).toString()
                    connection.disconnect()
                    return@repeat
                }
            }
            return connection
        }
        return connect(current, settings)
    }

    private fun connect(url: String, settings: UpdateSettings.Values): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.requestMethod = "GET"
        connection.instanceFollowRedirects = false
        if (settings.username.isNotEmpty()) {
            val raw = "${settings.username}:${settings.password}"
            val encoded = Base64.encodeToString(raw.toByteArray(), Base64.NO_WRAP)
            connection.setRequestProperty("Authorization", "Basic $encoded")
        }
        return connection
    }

    /** True once the user has allowed this app to install packages (required from Android 8 on). */
    fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.packageManager.canRequestPackageInstalls()
        else true

    /** Opens the system screen where "install unknown apps" is granted for this app. */
    fun installPermissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /** Hands the downloaded APK to the system installer. */
    fun installApk(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
