package com.example.walletlookup

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

/** Where Batwallet lives on GitHub: updates come from its Releases, problem reports go to its Issues. */
const val GITHUB_REPO = "Devborne-labs/batwallet"
const val GITHUB_URL = "https://github.com/$GITHUB_REPO"

/** The newest published version: its number, the APK to download, and the release page. */
data class Release(val version: String, val apkUrl: String?, val pageUrl: String, val notes: String = "")

/** Latest release from GitHub (public API, no account needed). Null when it can't be reached. */
suspend fun latestRelease(): Release? = withContext(Dispatchers.IO) {
    runCatching {
        val j = JSONObject(http("https://api.github.com/repos/$GITHUB_REPO/releases/latest"))
        val assets = j.optJSONArray("assets")
        val apk = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk") }?.optString("browser_download_url")
        Release(j.getString("tag_name").removePrefix("v"), apk, j.optString("html_url", "$GITHUB_URL/releases"), j.optString("body"))
    }.getOrNull()
}

/** The release notes of one version (e.g. the one you just updated to), or null if GitHub can't be reached. */
suspend fun releaseNotes(version: String): String? = withContext(Dispatchers.IO) {
    runCatching { JSONObject(http("https://api.github.com/repos/$GITHUB_REPO/releases/tags/v$version")).optString("body") }
        .getOrNull()?.takeIf { it.isNotBlank() }
}

/**
 * True once per new version: the first launch after updating (not after a fresh install), so the app can show
 * what changed. [hasData]: you already had wallets saved (covers updating from a version that didn't record this).
 */
fun justUpdated(context: Context, hasData: Boolean): Boolean {
    val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    val current = BatApp.appVersion(context)
    val last = prefs.getString("seen", null)
    if (last == current) return false
    prefs.edit().putString("seen", current).apply()
    return last != null || hasData
}

/**
 * The quiet weekly check: asks GitHub at most once every 7 days (a single small request) and remembers the newest
 * version, so Settings can say "16.x available" without you checking by hand.
 */
suspend fun weeklyUpdateCheck(context: Context) {
    val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    if (System.currentTimeMillis() - prefs.getLong("checked", 0) < 7 * 24 * 3600_000L) return
    val latest = latestRelease() ?: return
    rememberRelease(context, latest)
}

fun rememberRelease(context: Context, release: Release) {
    context.getSharedPreferences("updates", Context.MODE_PRIVATE).edit()
        .putLong("checked", System.currentTimeMillis()).putString("latest", release.version).apply()
}

/** The newer version found by the last check, or null when you're up to date. */
fun knownUpdate(context: Context): String? =
    context.getSharedPreferences("updates", Context.MODE_PRIVATE).getString("latest", null)
        ?.takeIf { isNewerVersion(it, BatApp.appVersion(context)) }

/** True when version [a] (e.g. "16.10") is newer than [b] ("16.9"). */
fun isNewerVersion(a: String, b: String): Boolean {
    val x = a.split('.').map { it.toIntOrNull() ?: 0 }
    val y = b.split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(x.size, y.size)) {
        val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
        if (d != 0) return d > 0
    }
    return false
}

/**
 * Opens a new GitHub issue with the report filled in (crash details, or phone and app info to describe a problem).
 * Nothing is sent until you submit it on GitHub.
 */
fun reportOnGitHub(context: Context, crash: String?) {
    val info = "Batwallet ${BatApp.appVersion(context)} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
        "${Build.MANUFACTURER} ${Build.MODEL}"
    val body = if (crash != null) "**What happened:**\n\n\n**Crash report:**\n```\n${crash.take(5000)}\n```\n"
    else "**What went wrong:**\n\n\n**Phone:** $info\n"
    val title = if (crash != null) "Crash in Batwallet ${BatApp.appVersion(context)}" else "Problem in Batwallet ${BatApp.appVersion(context)}"
    fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    openInBrowser(context, "$GITHUB_URL/issues/new?title=${enc(title)}&body=${enc(body)}")
}
