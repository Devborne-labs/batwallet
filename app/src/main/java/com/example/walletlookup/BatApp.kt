package com.example.walletlookup

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Saves the error behind any crash so it can be shared from Settings on the next launch.
 * Nothing is sent anywhere automatically.
 */
class BatApp : Application() {
    // Installed in attachBaseContext (not onCreate) so crashes during app start-up are caught too.
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                val report = buildString {
                    appendLine("Batwallet ${appVersion(base)} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    appendLine("${Build.MANUFACTURER} ${Build.MODEL} · thread ${thread.name}")
                    appendLine()
                    append(trace.take(12_000))
                }
                // commit(), not apply(): the process is about to die.
                base.getSharedPreferences(CRASH_PREFS, Context.MODE_PRIVATE).edit().putString(KEY, report).commit()
            }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        private const val CRASH_PREFS = "crash"
        private const val KEY = "last"

        fun lastCrash(context: Context): String? =
            context.getSharedPreferences(CRASH_PREFS, Context.MODE_PRIVATE).getString(KEY, null)

        fun clearCrash(context: Context) =
            context.getSharedPreferences(CRASH_PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()

        fun appVersion(context: Context): String = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }
}
