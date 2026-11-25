package dev.logit.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.widget.ProgressBar
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject

internal fun releaseVersion(value: String): List<Int>? {
    val parts = Regex("v?([0-9]+)\\.([0-9]+)\\.([0-9]+)").matchEntire(value)?.groupValues?.drop(1) ?: return null
    return parts.map { it.toIntOrNull() ?: return null }
}

internal fun newerRelease(tag: String, installed: String): Boolean {
    val candidate = releaseVersion(tag) ?: return false
    val current = releaseVersion(installed) ?: return false
    return candidate.zip(current).firstOrNull { (a, b) -> a != b }?.let { (a, b) -> a > b } ?: false
}

private const val UPDATE_CHECK_INTERVAL = 6 * 60 * 60 * 1000L
private const val UPDATE_CACHE_LIFETIME = 24 * 60 * 60 * 1000L
private const val UPDATE_RETRY_INTERVAL = 60 * 1000L
private const val DOWNLOAD_METADATA_LIFETIME = 5 * 60 * 1000L

private fun recentCheck(checkedAt: Long, now: Long, lifetime: Long): Boolean =
    checkedAt > 0 && checkedAt <= now && now - checkedAt < lifetime

internal fun cachedReleaseVersion(
    tag: String,
    installed: String,
    checkedFor: String,
    checkedAt: Long,
    now: Long,
): String? = tag.removePrefix("v").takeIf {
    checkedFor == installed && recentCheck(checkedAt, now, UPDATE_CACHE_LIFETIME) && newerRelease(tag, installed)
}

object AppUpdates {
    const val INSTALL_PERMISSION_REQUEST = 92
    const val INSTALL_PACKAGE_REQUEST = 93

    private const val repository = "wrestle-R/LogIt"
    private const val limit = 100L * 1024 * 1024
    private const val versionKey = "available-version"
    private const val checkedAtKey = "update-success-time"
    private const val checkedForKey = "update-checked-version"

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private data class Release(val tag: String, val notes: String, val url: String, val bytes: Long, val digest: String)
    private data class Lookup(val release: Release? = null, val unavailable: String? = null)

    private sealed interface InstallState {
        class Checking : InstallState
        class Downloading : InstallState {
            val cancelled = AtomicBoolean()
            var progress = 0
        }
        data class Ready(val release: Release, val file: File) : InstallState
        data class PermissionSettings(val apk: Ready) : InstallState
        data class Installer(val apk: Ready, val uri: Uri) : InstallState
    }

    // UI and request state belongs to the main thread. The worker uses application context.
    private var visibleActivity: Activity? = null
    private var onRefresh: (() -> Unit)? = null
    private var displayedVersion: String? = null
    private var lookupCallbacks: MutableList<(Result<Lookup>) -> Unit>? = null
    private var lastRelease: Release? = null
    private var retryAfter = 0L
    private var installState: InstallState? = null
    private var installDialog: PrettyDialog? = null
    private var progressBar: ProgressBar? = null

    private fun connection(url: String): HttpsURLConnection = (URL(url).openConnection() as HttpsURLConnection).apply {
        connectTimeout = 12000
        readTimeout = 20000
        setRequestProperty("User-Agent", "LogIt Android")
    }

    private fun lookup(installed: String): Lookup {
        val connection = connection("https://api.github.com/repos/$repository/releases/latest")
        val json = try {
            if (connection.responseCode == 404) return Lookup()
            check(connection.responseCode == 200) { "GitHub couldn't check for updates. Try again shortly." }
            connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        } finally { connection.disconnect() }
        val tag = json.getString("tag_name")
        if (json.optBoolean("draft") || json.optBoolean("prerelease") || !newerRelease(tag, installed)) return Lookup()
        val assets = json.getJSONArray("assets")
        val asset = (0 until assets.length()).map(assets::getJSONObject)
            .firstOrNull { it.optString("name") == "logit.apk" && it.optString("state") == "uploaded" }
            ?: return Lookup(unavailable = "This release doesn't have a LogIt APK yet.")
        val expected = "https://github.com/$repository/releases/download/$tag/logit.apk"
        val digest = asset.optString("digest")
        val bytes = asset.optLong("size")
        if (asset.optString("browser_download_url") != expected || bytes !in 1..limit ||
            (digest.isNotBlank() && !Regex("sha256:[a-f0-9]{64}").matches(digest))
        ) return Lookup(unavailable = "GitHub returned an invalid APK.")
        return Lookup(Release(tag, json.optString("body").take(1600), expected, bytes, digest))
    }

    private fun installedVersion(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    fun availableVersion(activity: Activity): String? {
        val prefs = Store.prefs
        return cachedReleaseVersion(
            prefs.getString(versionKey, "").orEmpty(),
            installedVersion(activity),
            prefs.getString(checkedForKey, "").orEmpty(),
            prefs.getLong(checkedAtKey, 0L),
            System.currentTimeMillis(),
        )
    }

    fun cachedUpdate(activity: Activity): Boolean = availableVersion(activity) != null

    fun refresh(activity: Activity, done: () -> Unit) {
        if (visibleActivity !== activity) dismissInstallDialog()
        visibleActivity = activity
        onRefresh = done
        displayedVersion = availableVersion(activity)
        done()
        displayInstallState()
        val prefs = Store.prefs
        val installed = installedVersion(activity)
        if (prefs.getString(checkedForKey, null) == installed &&
            recentCheck(prefs.getLong(checkedAtKey, 0L), System.currentTimeMillis(), UPDATE_CHECK_INTERVAL)
        ) return
        if (SystemClock.elapsedRealtime() >= retryAfter) checkRelease(installed)
    }

    fun pause(activity: Activity) {
        if (visibleActivity !== activity) return
        visibleActivity = null
        onRefresh = null
        displayedVersion = null
        dismissInstallDialog()
    }

    private fun foreground(): Activity? = visibleActivity?.takeUnless { it.isFinishing || it.isDestroyed }

    private fun checkRelease(installed: String, done: ((Result<Lookup>) -> Unit)? = null) {
        lookupCallbacks?.let { callbacks ->
            if (done != null) callbacks.add(done)
            return
        }
        val callbacks = mutableListOf<(Result<Lookup>) -> Unit>()
        if (done != null) callbacks.add(done)
        lookupCallbacks = callbacks
        executor.execute {
            val result = runCatching { lookup(installed) }
            main.post {
                lookupCallbacks = null
                result.onSuccess { response ->
                    val checkedAt = if (response.unavailable == null) System.currentTimeMillis() else 0L
                    retryAfter = if (checkedAt != 0L) 0L else SystemClock.elapsedRealtime() + UPDATE_RETRY_INTERVAL
                    lastRelease = response.release
                    Store.prefs.edit()
                        .putString(versionKey, response.release?.tag)
                        .putString(checkedForKey, installed)
                        .putLong(checkedAtKey, checkedAt)
                        .apply()
                }.onFailure {
                    lastRelease = null
                    retryAfter = SystemClock.elapsedRealtime() + UPDATE_RETRY_INTERVAL
                }
                foreground()?.let { activity ->
                    val version = availableVersion(activity)
                    if (displayedVersion != version) {
                        displayedVersion = version
                        onRefresh?.invoke()
                    }
                }
                callbacks.forEach { it(result) }
            }
        }
    }

    fun show(activity: Activity) {
        val installed = installedVersion(activity)
        val checking = PrettyDialog.Builder(activity).setTitle("Checking for updates")
            .setMessage("Looking for the latest LogIt release.").setNegativeButton("Close", null).show()
        checkRelease(installed) { result ->
            if (activity.isFinishing || activity.isDestroyed || !checking.isShowing) return@checkRelease
            checking.dismiss()
            if (foreground() !== activity) return@checkRelease
            result.fold({ response ->
                val release = response.release
                when {
                    response.unavailable != null -> failure(activity, "Couldn't check for updates", response.unavailable) { show(activity) }
                    release == null -> upToDate(activity, installed)
                    else -> PrettyDialog.Builder(activity).setTitle("LogIt ${release.tag.removePrefix("v")} is available")
                        .setMessage(release.notes.ifBlank { "Download the update to install it." })
                        .setNegativeButton("Later", null).setPositiveButton("Download") { _, _ ->
                            if (recentRelease(installed) == release) startDownload(release) else show(activity)
                        }.show()
                }
            }, { failure(activity, "Couldn't check for updates", it.message) { show(activity) } })
        }
    }

    private fun recentRelease(installed: String): Release? =
        lastRelease?.takeIf {
            lookupCallbacks == null && newerRelease(it.tag, installed) &&
                recentCheck(Store.prefs.getLong(checkedAtKey, 0L), System.currentTimeMillis(), DOWNLOAD_METADATA_LIFETIME)
        }

    fun update(activity: Activity) {
        if (foreground() !== activity || installState != null) return
        val installed = installedVersion(activity)
        val cached = recentRelease(installed)
        if (cached != null) {
            startDownload(cached)
            return
        }
        val checking = InstallState.Checking()
        setInstallState(checking)
        checkRelease(installed) { result ->
            if (installState !== checking) return@checkRelease
            val cancelled = installDialog?.isShowing == false
            setInstallState(null)
            if (cancelled) return@checkRelease
            result.fold({ response ->
                val release = response.release
                if (release != null) startDownload(release)
                else foreground()?.let { current ->
                    if (response.unavailable == null) upToDate(current, installed)
                    else failure(current, "Couldn't check for updates", response.unavailable) { update(current) }
                }
            }, { problem ->
                foreground()?.let { current -> failure(current, "Couldn't check for updates", problem.message) { update(current) } }
            })
        }
    }

    private fun startDownload(release: Release) {
        if (installState != null) return
        val context = Store.context
        val downloading = InstallState.Downloading()
        setInstallState(downloading)
        executor.execute {
            val result = runCatching { download(context, release, downloading) }
            main.post {
                if (downloading.cancelled.get() || installDialog?.isShowing == false) {
                    result.getOrNull()?.delete()
                    setInstallState(null)
                } else {
                    result.fold({ setInstallState(InstallState.Ready(release, it)) }, { problem ->
                        setInstallState(null)
                        foreground()?.let { activity ->
                            failure(activity, "Couldn't download the update", problem.message) { update(activity) }
                        }
                    })
                }
            }
        }
    }

    private fun download(context: Context, release: Release, state: InstallState.Downloading): File {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val expiration = System.currentTimeMillis() - UPDATE_CACHE_LIFETIME
        directory.listFiles()?.forEach { if (it.isFile && it.lastModified() < expiration) it.delete() }
        val partial = File.createTempFile("logit-", ".part.apk", directory)
        val file = File(directory, partial.name.removeSuffix(".part.apk") + ".apk")
        try {
            val connection = connection(release.url)
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            var progress = 0
            try {
                check(connection.responseCode == 200) { "The download couldn't start." }
                connection.inputStream.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(32768)
                        while (!state.cancelled.get()) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            size += count
                            check(size <= release.bytes) { "The download size doesn't match the release." }
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            val percent = (size * 100 / release.bytes).toInt()
                            if (progress == percent) continue
                            progress = percent
                            main.post {
                                state.progress = percent
                                if (installState === state) progressBar?.progress = percent
                            }
                        }
                    }
                }
            } finally { connection.disconnect() }
            check(!state.cancelled.get())
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            check(size == release.bytes && (release.digest.isBlank() || release.digest == "sha256:$hash")) { "The downloaded APK didn't pass verification." }
            verify(context, partial, release.tag)
            check(partial.renameTo(file)) { "Couldn't save the update." }
            return file
        } finally { partial.delete() }
    }

    @Suppress("DEPRECATION")
    private fun verify(context: Context, file: File, tag: String) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val pm = context.packageManager
        val installed = pm.getPackageInfo(context.packageName, flags)
        val apk = pm.getPackageArchiveInfo(file.path, flags) ?: error("Android couldn't read the APK.")
        fun signatures(info: PackageInfo) = (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)?.toSet().orEmpty()
        fun version(info: PackageInfo) = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        val signers = signatures(installed)
        check(apk.packageName == context.packageName && signers.isNotEmpty() && signers == signatures(apk)) { "The APK isn't signed for this installation of LogIt." }
        check(version(apk) > version(installed) && releaseVersion(apk.versionName.orEmpty()) == releaseVersion(tag)) { "The APK version doesn't match the update." }
    }

    private fun setInstallState(state: InstallState?) {
        dismissInstallDialog()
        installState = state
        displayInstallState()
    }

    private fun dismissInstallDialog() {
        installDialog?.dismiss()
        installDialog = null
        progressBar = null
    }

    private fun displayInstallState() {
        val activity = foreground() ?: return
        if (installDialog?.isShowing == true) return
        when (val state = installState) {
            is InstallState.Checking -> {
                fun cancel() {
                    if (installState === state) setInstallState(null)
                }
                installDialog = PrettyDialog.Builder(activity).setTitle("Checking for updates")
                    .setNegativeButton("Cancel") { _, _ -> cancel() }.setOnCancelListener { cancel() }.show()
            }
            is InstallState.Downloading -> {
                if (state.cancelled.get()) return
                val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    progress = state.progress
                }
                progressBar = bar
                installDialog = PrettyDialog.Builder(activity).setTitle("Downloading LogIt").setView(bar)
                    .setNegativeButton("Cancel") { _, _ -> state.cancelled.set(true) }
                    .setOnCancelListener { state.cancelled.set(true) }.show()
            }
            is InstallState.Ready -> {
                if (!newerRelease(state.release.tag, installedVersion(activity)) || state.file.length() != state.release.bytes) {
                    discard(state)
                    failure(activity, "Couldn't open the update", "Check for updates and try again.") { update(activity) }
                } else if (activity.packageManager.canRequestPackageInstalls()) {
                    launchInstaller(activity, state)
                } else {
                    installDialog = PrettyDialog.Builder(activity).setTitle("Allow updates from LogIt")
                        .setMessage("In Android settings, allow installs from LogIt, then come back to install the update.")
                        .setNegativeButton("Later") { _, _ -> discard(state) }
                        .setPositiveButton("Open settings") { _, _ -> openInstallSettings(activity, state) }
                        .setOnCancelListener { if (installState === state) discard(state) }.show()
                }
            }
            else -> Unit
        }
    }

    @Suppress("DEPRECATION")
    private fun openInstallSettings(activity: Activity, apk: InstallState.Ready) {
        setInstallState(InstallState.PermissionSettings(apk))
        try {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            activity.startActivityForResult(intent, INSTALL_PERMISSION_REQUEST)
        } catch (problem: Exception) {
            discard(apk)
            failure(activity, "Couldn't open install settings", problem.message) { update(activity) }
        }
    }

    fun resumeInstall(activity: Activity) {
        val state = installState as? InstallState.PermissionSettings ?: return
        if (activity.packageManager.canRequestPackageInstalls()) setInstallState(state.apk)
        else discard(state.apk)
    }

    @Suppress("DEPRECATION")
    private fun launchInstaller(activity: Activity, apk: InstallState.Ready) {
        try {
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", apk.file)
            setInstallState(InstallState.Installer(apk, uri))
            fun open(intent: Intent) = activity.startActivityForResult(
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(Intent.EXTRA_RETURN_RESULT, true),
                INSTALL_PACKAGE_REQUEST,
            )
            try {
                open(Intent(Intent.ACTION_INSTALL_PACKAGE, uri))
            } catch (_: ActivityNotFoundException) {
                open(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive"))
            }
        } catch (problem: Exception) {
            discard(apk)
            failure(activity, "Couldn't open the Android installer", problem.message) { update(activity) }
        }
    }

    fun installFinished(activity: Activity, result: Int) {
        val state = installState as? InstallState.Installer ?: return
        activity.revokeUriPermission(state.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        discard(state.apk)
        if (result != Activity.RESULT_OK && result != Activity.RESULT_CANCELED) {
            Toast.makeText(activity, "Android couldn't install the update.", Toast.LENGTH_LONG).show()
        }
    }

    private fun discard(apk: InstallState.Ready) {
        apk.file.delete()
        setInstallState(null)
    }

    private fun upToDate(activity: Activity, installed: String) {
        PrettyDialog.Builder(activity).setTitle("You're up to date")
            .setMessage("LogIt $installed is installed.").setNegativeButton("Close", null).show()
    }

    private fun failure(activity: Activity, title: String, message: String?, retry: () -> Unit) {
        PrettyDialog.Builder(activity).setTitle(title)
            .setMessage(message?.take(200) ?: "Give it another try.")
            .setNegativeButton("Close", null).setPositiveButton("Try again") { _, _ -> retry() }.show()
    }
}
