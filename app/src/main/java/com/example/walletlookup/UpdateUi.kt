package com.example.walletlookup

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Locale

/**
 * Release notes as written on GitHub: "- " lines become bullets with the text lined up beside the dot, **bold** stays
 * bold, links show their text. The "Install:" line is left out (you're already in the app), and so is a heading that
 * only repeats the version ([skipVersionHeading], when the title already says it).
 */
@Composable
fun ReleaseNotes(text: String, modifier: Modifier = Modifier, skipVersionHeading: String? = null) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (raw in text.lines()) {
            val line = raw.trim().removePrefix("#").removePrefix("#").removePrefix("#").trim()
            if (line.isEmpty() || line.startsWith("**Install:**")) continue
            val bullet = line.startsWith("- ") || line.startsWith("* ")
            val body = if (bullet) line.drop(2) else line
            val heading = !bullet && body.startsWith("**") && body.endsWith("**")
            if (heading && skipVersionHeading != null && body.contains(skipVersionHeading)) continue
            if (bullet) {
                Row {
                    Box(
                        Modifier.padding(top = 8.dp, end = 12.dp).size(6.dp)
                            .background(MaterialTheme.colorScheme.onSurface, CircleShape)
                    )
                    androidx.compose.material3.Text(markdownLine(body), style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f))
                }
            } else {
                androidx.compose.material3.Text(
                    markdownLine(body),
                    style = if (heading) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                    fontWeight = if (heading) FontWeight.SemiBold else null,
                    modifier = Modifier.fillMaxWidth().padding(top = if (heading) 4.dp else 0.dp),
                )
            }
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

/** Home's small "Version x is available" line; tap to go to Settings → Updates. */
@Composable
fun UpdateBanner(version: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(tr("Version") + " $version " + tr("is available"), style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f))
        Text("Update", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary)
    }
}

/** Shown once after updating: what changed in this version. */
@Composable
fun WhatsNewDialog(version: String, notes: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp) {
            Column(Modifier.padding(24.dp)) {
                Text("What's new", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(tr("Batwallet") + " " + version, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                HorizontalDivider(Modifier.padding(vertical = 16.dp))
                ReleaseNotes(
                    notes,
                    Modifier.weight(1f, fill = false).heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    skipVersionHeading = version,
                )
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) { Text("Got it") }
            }
        }
    }
}
