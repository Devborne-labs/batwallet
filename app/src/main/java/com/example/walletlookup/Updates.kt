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
data class Release(val version: String, val apkUrl: String?, val pageUrl: String)

/** Latest release from GitHub (public API, no account needed). Null when it can't be reached. */
suspend fun latestRelease(): Release? = withContext(Dispatchers.IO) {
    runCatching {
        val j = JSONObject(http("https://api.github.com/repos/$GITHUB_REPO/releases/latest"))
        val assets = j.optJSONArray("assets")
        val apk = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk") }?.optString("browser_download_url")
        Release(j.getString("tag_name").removePrefix("v"), apk, j.optString("html_url", "$GITHUB_URL/releases"))
    }.getOrNull()
}

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
