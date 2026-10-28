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

/** Thin line of the saved-wallets total over the last 7 or 30 days. Tap the range to switch. */
@Composable
fun ValueChart(points: List<Pair<Long, BigDecimal>>) {
    var days by remember { mutableIntStateOf(7) }
    val since = System.currentTimeMillis() - days * 24L * 3_600_000L
    val shown = points.filter { it.first >= since }.sortedBy { it.first }
    if (shown.size < 2) return
    val line = MaterialTheme.colorScheme.onSurfaceVariant
    Column {
        Canvas(Modifier.fillMaxWidth().height(56.dp)) {
            val minT = shown.first().first.toFloat()
            val spanT = (shown.last().first - shown.first().first).coerceAtLeast(1).toFloat()
            val values = shown.map { it.second.toFloat() }
            val minV = values.min()
            val spanV = (values.max() - minV).takeIf { it > 0f } ?: 1f
            val path = Path()
            shown.forEachIndexed { i, (t, v) ->
                val x = (t - minT) / spanT * size.width
                val y = size.height - (v.toFloat() - minV) / spanV * (size.height - 4f) - 2f
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            // Soft grey fill under the line.
            val fill = Path().apply { addPath(path); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
            drawPath(fill, androidx.compose.ui.graphics.Brush.verticalGradient(listOf(line.copy(alpha = 0.18f), androidx.compose.ui.graphics.Color.Transparent)))
            drawPath(path, line, style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round))
            drawCircle(line, 2.5.dp.toPx(), Offset(size.width, size.height - (values.last() - minV) / spanV * (size.height - 4f) - 2f))
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val hi = shown.maxOf { it.second }
            val lo = shown.minOf { it.second }
            listOf(7 to "7D", 30 to "30D").forEach { (d, label) ->
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (days == d) FontWeight.Bold else FontWeight.Normal,
                    color = if (days == d) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { days = d },
                )
            }
            Spacer(Modifier.weight(1f))
            Text("High " + hideable(money(hi)) + "  ·  Low " + hideable(money(lo)), style = MaterialTheme.typography.labelSmall,
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

/** Your top wallets by value. Tap one to open it. */
@Composable
fun WalletsSection(mine: List<SavedAddress>, cache: Map<String, Cached>, onOpen: (String) -> Unit, onSeeAll: () -> Unit) {
    val byValue = mine.sortedByDescending { cache[it.address]?.totals?.usd ?: BigDecimal.ZERO }
    val top = (byValue.filter { it.pinned } + byValue.filter { !it.pinned }).take(4)
    Column {
        SectionHeader("Your wallets", if (mine.size > top.size) "See all" else null, onSeeAll)
        Card(Modifier.fillMaxWidth().animateContentSize()) {
            Column(Modifier.padding(vertical = 6.dp)) {
                top.forEach { w ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpen(w.address) }.padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        NetworkIcon(labelOf(w.address), 30.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(w.name, maxLines = 1)
                            // Folder first, so "Bybit · Tron" and "Trust Wallet · Tron" are easy to tell apart.
                            Text((if (w.group.isNotBlank()) w.group + " · " else "") + labelOf(w.address),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                        Text(cache[w.address]?.let { hideable(money(it.totals.usd)) } ?: "—")
                    }
                }
            }
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
                                Text((if (t.incoming) "From " else "To ") + (other ?: net), maxLines = 1,
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