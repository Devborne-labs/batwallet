package com.example.walletlookup

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * In-app updates: the APK from Batwallet's own GitHub release is downloaded into the app's cache, checked (same app,
 * newer version, same signing key), handed to Android's installer and deleted straight away, so no APK is left behind.
 */
object SelfUpdate {
    private const val FILE = "update.apk"

    /** Where an update is; kept here (not in a screen) so closing Settings doesn't lose it. */
    sealed interface State {
        data object Idle : State
        data class Downloading(val done: Long, val total: Long) : State
        /** Handed to Android's installer, which may ask you to confirm. */
        data object Installing : State
        data class Failed(val message: String) : State
    }

    var state by mutableStateOf<State>(State.Idle)
    /** Set by Home's "Update available" line so Settings opens its Updates section. */
    var openInSettings by mutableStateOf(false)
    /** The version being installed, for the progress dialog's title. */
    var version by mutableStateOf("")
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** Starts downloading and installing [url]; does nothing while an update is already running. */
    /**
     * Android's "Update this app?" screen, waiting to be shown. The installer reports in the background, where newer
     * Android versions (OnePlus especially) don't let apps open screens, so the visible update dialog opens it.
     */
    var confirm by mutableStateOf<Intent?>(null)
    /** Which download try this is (1–5), shown in the dialog when it's past the first. */
    var attempt by mutableStateOf(1)
    /** The last confirm screen, so the dialog's Install button can open it again if it was closed. */
    var lastConfirm: Intent? = null

    /** Starts downloading and installing [url]; does nothing while a download is running (an install can be retried). */
    fun start(context: Context, url: String, newVersion: String) {
        if (state is State.Downloading) return
        job?.cancel()
        version = newVersion
        confirm = null
        lastConfirm = null
        state = State.Downloading(0, 0)
        val app = context.applicationContext
        job = scope.launch {
            // Up to 5 tries: GitHub's download server can hang or drop on some mobile networks (a stalled try is
            // restarted after 20 seconds without data). Problems with the file itself aren't retried.
            var error: String? = null
            for (n in 1..5) {
                withContext(Dispatchers.Main) { attempt = n; state = State.Downloading(0, 0) }
                error = downloadAndInstall(app, url)
                if (error == null || error == "Not a Batwallet release" || error.startsWith("This APK") ||
                    error == "You already have this version" || error == "Not enough storage") break
            }
            withContext(Dispatchers.Main) { state = if (error == null) State.Installing else State.Failed(error) }
            // The installer normally answers in seconds; if it never does, don't leave the dialog stuck.
            if (error == null) {
                kotlinx.coroutines.delay(120_000)
                withContext(Dispatchers.Main) {
                    if (state is State.Installing) state = State.Failed("The installer didn't answer. Tap Update to try again.")
                }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        state = State.Idle
    }

    /** Android's one-time "install unknown apps" switch for Batwallet. */
    fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Set once the leftovers were removed in this run of the app. */
    private var cleaned = false

    /**
     * Removes a download left over from an update that was interrupted. Once per run of the app, and only while no
     * update is in progress: the screen calls this every time it's recreated (rotation, theme change), which used
     * to delete the update being downloaded.
     */
    fun cleanUp(context: Context) {
        if (cleaned || state !is State.Idle) return
        cleaned = true
        File(context.cacheDir, FILE).delete()
        context.getExternalFilesDir(null)?.let { File(it, FILE).delete() }
    }

    /** Only APKs attached to Batwallet's own releases are ever downloaded. */
    fun trusted(url: String?) = url != null && url.startsWith("$GITHUB_URL/releases/download/") && url.endsWith(".apk")

    /** Downloads and starts the install; returns an error message, or null once Android's installer has it. */
    private suspend fun downloadAndInstall(context: Context, url: String): String? =
        withContext(Dispatchers.IO) {
            if (!trusted(url)) return@withContext "Not a Batwallet release"
            val file = File(context.getExternalFilesDir(null) ?: context.cacheDir, FILE)
            try {
                download(context, url, file)
                ensureActive()
                check(context, file)?.let { return@withContext it }
                install(context, file)
                null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: java.net.UnknownHostException) {
                "No internet connection"
            } catch (e: Exception) {
                e.message ?: "Update failed"
            } finally {
                // The installer keeps its own copy once the session is written.
                file.delete()
            }
        }

    /**
     * Downloads with Android's own downloader: it follows GitHub's redirect to its download servers, retries and
     * resumes when the connection drops, keeps going if you leave the app, and shows its progress as a notification.
     * The file goes to the app's own folder (no storage permission) and is deleted after installing.
     */
    private suspend fun download(context: Context, url: String, file: File) {
        val dm = context.getSystemService(android.app.DownloadManager::class.java)
        file.delete()
        val id = dm.enqueue(
            android.app.DownloadManager.Request(Uri.parse(url))
                .setTitle("Batwallet $version")
                .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(context, null, file.name)
        )
        var lastBytes = -1L
        var lastChange = System.currentTimeMillis()
        try {
            while (true) {
                kotlin.coroutines.coroutineContext.ensureActive() // Cancel stops the download (removed below)
                val q = dm.query(android.app.DownloadManager.Query().setFilterById(id))
                    ?: throw IOException("Download stopped")
                q.use { c ->
                    if (!c.moveToFirst()) throw IOException("Download stopped")
                    val status = c.getInt(c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_STATUS))
                    val done = c.getLong(c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = c.getLong(c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    withContext(Dispatchers.Main) { state = State.Downloading(maxOf(0L, done), total) }
                    // No new data for 20 seconds: this try is stuck (it happens with GitHub's server on some
                    // networks), so give it up and let the next try start fresh.
                    val now = System.currentTimeMillis()
                    if (done != lastBytes) { lastBytes = done; lastChange = now }
                    else if (status != android.app.DownloadManager.STATUS_SUCCESSFUL && now - lastChange > 20_000) throw IOException("Download stalled")
                    when (status) {
                        android.app.DownloadManager.STATUS_SUCCESSFUL -> return
                        android.app.DownloadManager.STATUS_FAILED -> {
                            val reason = c.getInt(c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_REASON))
                            throw IOException(
                                if (reason == android.app.DownloadManager.ERROR_INSUFFICIENT_SPACE) "Not enough storage"
                                else tr("Download failed (%s)").replace("%s", reason.toString())
                            )
                        }
                    }
                }
                kotlinx.coroutines.delay(400)
            }
        } catch (e: Exception) {
            dm.remove(id)
            throw e
        }
    }

    /** Same app, newer version, same signing key. Android refuses a different key anyway; this says why up front. */
    private fun check(context: Context, file: File): String? {
        val pm = context.packageManager
        val apk = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: return "The download is damaged. Try again."
        if (apk.packageName != context.packageName) return "Not a Batwallet APK"
        val mine = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        if (apk.longVersionCode <= mine.longVersionCode) return "You already have this version"
        val theirs = apk.signingInfo?.apkContentsSigners?.map { sha256(it.toByteArray()) }?.toSet()
        val ours = mine.signingInfo?.apkContentsSigners?.map { sha256(it.toByteArray()) }?.toSet()
        if (theirs != null && ours != null && theirs != ours) return "This APK isn't signed with Batwallet's key"
        return null
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun install(context: Context, file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            // Android 12+: once Batwallet installed itself, later updates can go in without the "Update" prompt.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("batwallet.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val result = PendingIntent.getBroadcast(
                context, id, Intent(context, InstallReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE, // the installer adds its status
            )
            session.commit(result.intentSender)
        }
    }
}

/** Android's installer reports here: show its confirm screen when it needs one, or say why it failed. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val screen = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                // Handed to the visible update dialog to open (opening it from here is often blocked).
                screen?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { SelfUpdate.lastConfirm = it; SelfUpdate.confirm = it }
            }
            PackageInstaller.STATUS_SUCCESS -> SelfUpdate.state = SelfUpdate.State.Idle
            PackageInstaller.STATUS_FAILURE_ABORTED -> SelfUpdate.state = SelfUpdate.State.Idle // you tapped Cancel
            else -> SelfUpdate.state = SelfUpdate.State.Failed(
                tr("Update failed") + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.let { ": $it" }.orEmpty()
            )
        }
    }
}
