package com.example.walletlookup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import java.util.Locale

/**
 * Release notes as written on GitHub, drawn simply: "- " lines become bullets, **bold** stays bold, links show their
 * text. The "Install:" line is left out (you're already in the app).
 */
@Composable
fun ReleaseNotes(text: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (raw in text.lines()) {
            val line = raw.trim().removePrefix("#").removePrefix("#").removePrefix("#").trim()
            if (line.isEmpty() || line.startsWith("**Install:**")) continue
            val bullet = line.startsWith("- ") || line.startsWith("* ")
            val body = if (bullet) line.drop(2) else line
            val heading = !bullet && body.startsWith("**") && body.endsWith("**")
            androidx.compose.material3.Text(
                markdownLine((if (bullet) "•  " else "") + body),
                style = if (heading) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                fontWeight = if (heading) FontWeight.SemiBold else null,
                modifier = if (heading) Modifier.fillMaxWidth() else Modifier,
            )
        }
    }
}

/** **bold**, `code` and [link](url) → plain styled text. */
private fun markdownLine(s: String): AnnotatedString {
    val text = s.replace(Regex("""\[([^]]+)]\([^)]+\)"""), "$1").replace("`", "")
    return buildAnnotatedString {
        text.split("**").forEachIndexed { i, part ->
            if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(part) } else append(part)
        }
    }
}

private fun mb(bytes: Long) = String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)

/** The update in progress: download bar, then "Installing…" until Android's installer takes over. */
@Composable
fun UpdateProgressDialog() {
    val st = SelfUpdate.state
    if (st == SelfUpdate.State.Idle) return
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(tr("Updating to") + " " + SelfUpdate.version) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (st) {
                    is SelfUpdate.State.Downloading -> {
                        if (st.total > 0) LinearProgressIndicator(progress = { st.done.toFloat() / st.total }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(
                            tr("Downloading…") + " " + mb(st.done) + (if (st.total > 0) " / " + mb(st.total) else ""),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    SelfUpdate.State.Installing -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Installing… Android may ask you to confirm.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    is SelfUpdate.State.Failed -> Text(tr(st.message), color = MaterialTheme.colorScheme.error)
                    SelfUpdate.State.Idle -> {}
                }
            }
        },
        confirmButton = {
            when (st) {
                is SelfUpdate.State.Downloading -> TextButton(onClick = { SelfUpdate.cancel() }) { Text("Cancel") }
                else -> TextButton(onClick = { SelfUpdate.state = SelfUpdate.State.Idle }) { Text("Close") }
            }
        },
    )
}

/** Shown once after updating: what changed in this version. */
@Composable
fun WhatsNewDialog(version: String, notes: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("What's new in") + " " + version) },
        text = { ReleaseNotes(notes, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}
