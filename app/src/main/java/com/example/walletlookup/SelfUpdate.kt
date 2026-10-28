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
import java.net.HttpURLConnection
import java.net.URL
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
    fun start(context: Context, url: String, newVersion: String) {
        if (state is State.Downloading || state is State.Installing) return
        version = newVersion
        state = State.Downloading(0, 0)
        val app = context.applicationContext
        job = scope.launch {
            val error = downloadAndInstall(app, url)
            state = if (error == null) State.Installing else State.Failed(error)
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

    /** Removes a download left over from an update that was interrupted. */
    fun cleanUp(context: Context) {
        File(context.cacheDir, FILE).delete()
    }

    /** Only APKs attached to Batwallet's own releases are ever downloaded. */
    fun trusted(url: String?) = url != null && url.startsWith("$GITHUB_URL/releases/download/") && url.endsWith(".apk")

    /** Downloads and starts the install; returns an error message, or null once Android's installer has it. */
    private suspend fun downloadAndInstall(context: Context, url: String): String? =
        withContext(Dispatchers.IO) {
            if (!trusted(url)) return@withContext "Not a Batwallet release"
            val file = File(context.cacheDir, FILE)
            try {
                download(url, file)
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

    private suspend fun download(url: String, file: File) {
        val c = URL(url).openConnection() as HttpURLConnection // GitHub redirects to its download host (https → https)
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "Batwallet/1.0")
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            val total = c.contentLengthLong // -1 when the server doesn't say: the dialog then shows a moving bar
            var done = 0L
            var shown = 0L
            c.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        kotlin.coroutines.coroutineContext.ensureActive() // Cancel stops the download
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        // About every 100 KB is plenty for a smooth bar, without redrawing for every block.
                        if (done - shown >= 100_000) {
                            shown = done
                            withContext(Dispatchers.Main) { state = State.Downloading(done, total) }
                        }
                    }
                }
            }
            withContext(Dispatchers.Main) { state = State.Downloading(done, if (total > 0) total else done) }
        } finally {
            c.disconnect()
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
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.let { runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            }
            PackageInstaller.STATUS_SUCCESS -> SelfUpdate.state = SelfUpdate.State.Idle
            PackageInstaller.STATUS_FAILURE_ABORTED -> SelfUpdate.state = SelfUpdate.State.Idle // you tapped Cancel
            else -> SelfUpdate.state = SelfUpdate.State.Failed(
                tr("Update failed") + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.let { ": $it" }.orEmpty()
            )
        }
    }
}
