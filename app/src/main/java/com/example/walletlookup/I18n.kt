package com.example.walletlookup

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * English, Russian and Hindi. The UI is written in English; [tr] looks each text up in res/raw/i18n.tsv
 * (English, Russian, Hindi per line) and falls back to English when a line is missing.
 */
object I18n {
    /** Compose state, so the whole UI switches language at once. */
    var lang by mutableStateOf("en")
    private var ru: Map<String, String> = emptyMap()
    private var hi: Map<String, String> = emptyMap()

    fun load(context: Context) {
        if (ru.isNotEmpty()) return
        runCatching {
            val r = HashMap<String, String>()
            val h = HashMap<String, String>()
            context.resources.openRawResource(R.raw.i18n).bufferedReader(Charsets.UTF_8).forEachLine { line ->
                val p = line.split('\t')
                if (p.size >= 3 && !line.startsWith("#")) {
                    val en = p[0].replace("\\n", "\n")
                    r[en] = p[1].replace("\\n", "\n")
                    h[en] = p[2].replace("\\n", "\n")
                }
            }
            ru = r; hi = h
        }
    }

    fun t(s: String): String = when (lang) {
        "ru" -> ru[s] ?: s
        "hi" -> hi[s] ?: s
        else -> s
    }
}

fun tr(s: String): String = I18n.t(s)

/** First-run tips for each tab. */
val TIPS: Map<String, List<String>> = mapOf(
    "home" to listOf(
        "Tap Paste & check to look up any wallet address, or Scan to read a QR code.",
        "Long-press Paste & check for recent checks, transaction lookup and address compare.",
        "Tap your total to hide or show balances.",
        "Pull down to refresh all balances.",
        "Tap a recent transaction to see who paid, who received, and follow the money.",
    ),
    "wallets" to listOf(
        "Tap Add to save a wallet or a contact. Put wallets in folders like Binance or Trust Wallet.",
        "Tap ⋯ → Edit order, then drag ≡ to put wallets and folders in your order.",
        "Long-press a wallet to copy, see its QR, share, history or edit. Long-press a folder to rename it.",
        "Tap a folder name to fold it. Pull down to refresh balances.",
    ),
    "settings" to listOf(
        "Choose language, currency and black or white theme here.",
        "Turn on payment alerts and the daily summary.",
        "Lock the app with your fingerprint.",
        "Export a backup before changing phones. It keeps your wallets, folders and API key.",
    ),
)

@Composable
fun TipsDialog(tips: List<String>, onDone: () -> Unit, onSkipAll: () -> Unit) {
    var step by remember(tips) { mutableIntStateOf(0) }
    val last = step == tips.lastIndex
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(tr("Tip") + " ${step + 1}/${tips.size}", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                Text(tr(tips[step]), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(4.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = { if (last) onDone() else step++ }) { Text(tr(if (last) "Got it" else "Next")) }
        },
        dismissButton = { TextButton(onClick = onSkipAll) { Text(tr("Skip tips")) } },
    )
}
