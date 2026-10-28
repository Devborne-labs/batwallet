package com.example.walletlookup

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.math.BigDecimal

/** Recognisable tokens beyond USDT/USDC and the native coin. Not counted in totals. */
@Composable
fun OtherTokensCard(tokens: List<TokenBalance>) {
    Card(Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(18.dp)) {
            Text("Other tokens", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            tokens.take(20).forEach { t ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(t.symbol)
                        Text(t.network, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                        Text(hideable(fmt(t.amount)), textAlign = TextAlign.End)
                        t.usd?.let {
                            Text(hideable(money(it)), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("Not included in your total.", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SectionHeader(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (action != null) {
            Text(action, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(onClick = onAction).padding(4.dp))
        }
    }
}

/** Payments the alerts noticed recently across your wallets. */
@Composable
fun ActivitySection(items: List<Pair<SavedAddress, Seen>>) {
    Column {
        SectionHeader("Recent activity")
        Card(Modifier.fillMaxWidth().animateContentSize()) {
            Column(Modifier.padding(vertical = 6.dp)) {
                items.forEach { (w, s) ->
                    val incoming = s.amount.signum() > 0
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text((if (incoming) "Received · " else "Sent · ") + w.name, maxLines = 1)
                            Text("${s.network} · ${shortDate(s.time / 1000)}", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val amt = if (s.symbol.startsWith("USD")) fmt2(s.amount.abs()) else fmt(s.amount.abs())
                        Text((if (incoming) "+" else "−") + hideable(amt) + " " + s.symbol,
                            fontWeight = if (incoming) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
        }
    }
}

/** Addresses you checked recently that aren't saved. */
@Composable
fun RecentSection(recent: List<Pair<String, Long>>, onOpen: (String) -> Unit, onClear: () -> Unit) {
    Column {
        SectionHeader("Recently checked", "Clear", onClear)
        Card(Modifier.fillMaxWidth().animateContentSize()) {
            Column(Modifier.padding(vertical = 6.dp)) {
                recent.forEach { (a, time) ->
                    Row(Modifier.fillMaxWidth().clickable { onOpen(a) }.padding(horizontal = 18.dp, vertical = 10.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                rawAddress(a).let { r -> if (r.length > 22) r.take(10) + "…" + r.takeLast(8) else r },
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                            )
                            // Network and when you checked it.
                            Text(labelOf(a) + (if (time > 0) " · " + shortDate(time / 1000) else ""),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

/** First-run hint when there's nothing saved or checked yet. */
@Composable
fun EmptyHint() {
    Text(
        "Copy a wallet address and tap Paste & check, or scan its QR code. Save your own wallets to see them here.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}
/** Latest transfers across your wallets. Tap one for its full details. */
@Composable
fun RecentTxSection(
    items: List<Triple<String, String, Transfer>>,
    names: Map<String, String>,
    walletName: (String) -> String?,
    onOpen: (String, Transfer) -> Unit,
) {
    Column {
        SectionHeader("Recent transactions")
        Card(Modifier.fillMaxWidth().animateContentSize()) {
            Column(Modifier.padding(vertical = 4.dp)) {
                items.forEach { (w, net, t) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpen(net, t) }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        DirectionIcon(t.symbol, t.incoming)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            val other = t.counterparty?.let { names.labelFor(it, net) ?: knownPlatform(it) ?: (it.take(6) + "…" + it.takeLast(4)) }
                            val twin = t.counterparty?.takeIf { names.labelFor(it, net) == null }?.let(::lookalikeOf)
                            if (twin != null) {
                                Text(tr("Lookalike of") + " " + walletLabel(twin) + " · " + tr("don't copy"),
                                    style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = OutRed)
                            }
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                t.counterparty?.takeIf { names.labelFor(it, net) == null }?.let(::knownPlatform)?.let {
                                    ExchangeBadge(it, 16.dp)
                                    Spacer(Modifier.width(6.dp))
                                }
                                // No other address (a fee or a contract call): just say which way it went.
                                Text(if (other != null) fromTo(t.incoming, other) else tr(if (t.incoming) "Received" else "Sent"), maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                            }
                            // Which of your wallets it was, then when.
                            Text((walletName(w) ?: net) + " · " + (if (t.time > 0) shortDate(t.time / 1000) else tr("Pending")),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                        val amt = if (t.symbol.startsWith("USD")) fmt2(t.amount) else fmt(t.amount)
                        Text((if (t.incoming) "+" else "−") + hideable(amt) + " " + t.symbol,
                            fontWeight = FontWeight.SemiBold, color = if (t.incoming) InGreen else OutRed)
                    }
                }
            }
        }
    }
}