package dev.logit.app

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.ProgressBar
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import org.json.JSONObject
import javax.net.ssl.HttpsURLConnection

internal fun releaseVersion(value: String): List<Int>? {
    val parts = Regex("v?([0-9]+)\\.([0-9]+)\\.([0-9]+)").matchEntire(value)?.groupValues?.drop(1) ?: return null
    return parts.map { it.toIntOrNull() ?: return null }
}

internal fun newerRelease(tag: String, installed: String): Boolean {
    val candidate = releaseVersion(tag) ?: return false
    val current = releaseVersion(installed) ?: return false
    return candidate.zip(current).firstOrNull { (a, b) -> a != b }?.let { (a, b) -> a > b } ?: false
}

object AppUpdates {
    private const val repository = "wrestle-R/LogIt"
    private const val limit = 100L * 1024 * 1024
    private val executor = Executors.newSingleThreadExecutor()
    private data class Release(val tag: String, val notes: String, val url: String, val bytes: Long, val digest: String)

    private fun connection(url: String): HttpsURLConnection = (URL(url).openConnection() as HttpsURLConnection).apply {
        connectTimeout = 12000
        readTimeout = 20000
        setRequestProperty("User-Agent", "LogIt Android")
    }

    private fun lookup(installed: String): Release? {
        val connection = connection("https://api.github.com/repos/$repository/releases/latest")
        val json = try {
            if (connection.responseCode == 404) return null
            check(connection.responseCode == 200) { "GitHub couldn't check for updates. Try again shortly." }
            connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        } finally { connection.disconnect() }
        val tag = json.getString("tag_name")
        if (json.optBoolean("draft") || json.optBoolean("prerelease") || !newerRelease(tag, installed)) return null
        val assets = json.getJSONArray("assets")
        val asset = (0 until assets.length()).map(assets::getJSONObject)
            .firstOrNull { it.optString("name") == "logit.apk" && it.optString("state") == "uploaded" }
            ?: error("This release doesn't have a LogIt APK yet.")
        val expected = "https://github.com/$repository/releases/download/$tag/logit.apk"
        val digest = asset.optString("digest")
        val bytes = asset.optLong("size")
        check(asset.optString("browser_download_url") == expected && bytes in 1..limit) { "GitHub returned an invalid APK." }
        return Release(tag, json.optString("body").take(1600), expected, bytes, digest)
    }

    fun cachedUpdate(activity: Activity): Boolean {
        val installed = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName.orEmpty()
        return newerRelease(Store.prefs.getString("available-version", "").orEmpty(), installed)
    }

    fun refresh(activity: Activity, done: () -> Unit) {
        val now = System.currentTimeMillis()
        if (now - Store.prefs.getLong("update-check-time", 0L) < 6 * 60 * 60 * 1000L) return
        Store.prefs.edit().putLong("update-check-time", now).apply()
        val installed = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName.orEmpty()
        executor.execute {
            runCatching { lookup(installed) }.onSuccess { release ->
                Store.prefs.edit().putString("available-version", release?.tag).apply()
                activity.runOnUiThread { if (!activity.isDestroyed && !activity.isFinishing) done() }
            }
        }
    }

    fun show(activity: Activity) {
        val installed = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName.orEmpty()
        val checking = PrettyDialog.Builder(activity).setTitle("Checking for updates")
            .setMessage("Looking for the latest LogIt release.").setNegativeButton("Close", null).show()
        executor.execute {
            val result = runCatching { lookup(installed) }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed || !checking.isShowing) return@runOnUiThread
                checking.dismiss()
                result.fold({ release ->
                    if (release == null) PrettyDialog.Builder(activity).setTitle("You're up to date")
                        .setMessage("LogIt $installed is installed.").setNegativeButton("Close", null).show()
                    else PrettyDialog.Builder(activity).setTitle("LogIt ${release.tag.removePrefix("v")} is available")
                        .setMessage(release.notes.ifBlank { "Download the update to install it." })
                        .setNegativeButton("Later", null).setPositiveButton("Download") { _, _ -> download(activity, release) }.show()
                }, { failure(activity, "Couldn't check for updates", it) { show(activity) } })
            }
        }
    }

    private fun download(activity: Activity, release: Release) {
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val dialog = PrettyDialog.Builder(activity).setTitle("Downloading LogIt").setView(bar)
            .setNegativeButton("Close", null).show()
        executor.execute {
            val result = runCatching {
                val directory = File(activity.cacheDir, "updates").apply { mkdirs() }
                val file = File(directory, "logit.apk")
                val partial = File(directory, "download.part.apk")
                try {
                    val connection = connection(release.url)
                    val digest = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    try {
                        check(connection.responseCode == 200) { "The download couldn't start." }
                        connection.inputStream.use { input ->
                            partial.outputStream().use { output ->
                                val buffer = ByteArray(32768)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    size += count
                                    check(size <= release.bytes) { "The download size doesn't match the release." }
                                    output.write(buffer, 0, count)
                                    digest.update(buffer, 0, count)
                                    val percent = (size * 100 / release.bytes).toInt()
                                    activity.runOnUiThread { bar.progress = percent }
                                }
                            }
                        }
                    } finally { connection.disconnect() }
                    val hash = digest.digest().joinToString("") { "%02x".format(it) }
                    check(size == release.bytes && (release.digest.isBlank() || release.digest == "sha256:$hash")) { "The downloaded APK didn't pass verification." }
                    verify(activity, partial, release.tag)
                    check(partial.renameTo(file)) { "Couldn't save the update." }
                    file
                } finally { partial.delete() }
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed || !dialog.isShowing) return@runOnUiThread
                dialog.dismiss()
                result.fold({ install(activity, it) }, { failure(activity, "Couldn't download the update", it) { download(activity, release) } })
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun verify(activity: Activity, file: File, tag: String) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val pm = activity.packageManager
        val installed = pm.getPackageInfo(activity.packageName, flags)
        val apk = pm.getPackageArchiveInfo(file.path, flags) ?: error("Android couldn't read the APK.")
        fun signatures(info: PackageInfo) = (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)?.toSet().orEmpty()
        fun version(info: PackageInfo) = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        val signers = signatures(installed)
        check(apk.packageName == activity.packageName && signers.isNotEmpty() && signers == signatures(apk)) { "The APK isn't signed for this installation of LogIt." }
        check(version(apk) > version(installed) && releaseVersion(apk.versionName.orEmpty()) == releaseVersion(tag)) { "The APK version doesn't match the update." }
    }

    private fun install(activity: Activity, file: File) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            PrettyDialog.Builder(activity).setTitle("Allow updates from LogIt")
                .setMessage("Android needs your permission to install this update. Open settings, allow installs from LogIt, then come back.")
                .setNegativeButton("Later", null).setPositiveButton("Open settings") { _, _ ->
                    try {
                        @Suppress("DEPRECATION")
                        activity.startActivityForResult(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")), 92)
                    } catch (_: Exception) { Toast.makeText(activity, "Couldn't open install settings.", Toast.LENGTH_LONG).show() }
                }.show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", file)
            activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (failure: Exception) { failure(activity, "Couldn't open the Android installer", failure) { install(activity, file) } }
    }

    fun resumeInstall(activity: Activity) {
        val file = File(activity.cacheDir, "updates/logit.apk")
        if (file.isFile && activity.packageManager.canRequestPackageInstalls()) install(activity, file)
    }

    private fun failure(activity: Activity, title: String, problem: Throwable, retry: () -> Unit) {
        PrettyDialog.Builder(activity).setTitle(title)
            .setMessage(problem.message?.take(200) ?: "Give it another try.")
            .setNegativeButton("Close", null).setPositiveButton("Try again") { _, _ -> retry() }.show()
    }
}
