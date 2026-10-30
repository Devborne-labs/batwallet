package com.example.walletlookup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Exchange badges: the exchange's mark where a freely licensed one exists (Simple Icons, CC0: Binance, OKX, KuCoin;
 * the CC0 coin set's exchange tokens: HTX, Bitfinex), otherwise a monogram in its brand colours. Marks are shown only
 * to identify the exchange.
 */
private class Brand(val bg: Color, val fg: Color, val letters: String, val mark: Int? = null, val image: Int? = null)

private val BRANDS = mapOf(
    "Binance" to Brand(Color(0xFF1E2026), Color(0xFFF0B90B), "B", mark = R.drawable.exch_binance),
    "Bybit" to Brand(Color(0xFF17181E), Color(0xFFF7A600), "BY"),
    "OKX" to Brand(Color(0xFF000000), Color.White, "OKX", mark = R.drawable.exch_okx),
    "Coinbase" to Brand(Color(0xFF0052FF), Color.White, "C"),
    "Kraken" to Brand(Color(0xFF5741D9), Color.White, "K"),
    "KuCoin" to Brand(Color(0xFF23AF91), Color.White, "KC", mark = R.drawable.exch_kucoin),
    "Bitfinex" to Brand(Color(0xFF16B157), Color.White, "BF", image = R.drawable.exch_bitfinex),
    "Gemini" to Brand(Color(0xFF00DCFA), Color(0xFF0B0B0B), "G"),
    "HTX" to Brand(Color(0xFF2B66F6), Color.White, "HTX", image = R.drawable.exch_htx),
    "Gate.io" to Brand(Color(0xFF2354E6), Color.White, "G"),
    "Crypto.com" to Brand(Color(0xFF103F68), Color.White, "CR"),
    "MEXC" to Brand(Color(0xFF1972F5), Color.White, "M"),
    "Bitget" to Brand(Color(0xFF00F0FF), Color(0xFF0B0B0B), "BG"),
)

/** Brand colours for folders named after an exchange or wallet app (matched anywhere in the name, any case). */
private val FOLDER_BRANDS = listOf(
    "binance" to Color(0xFFF0B90B), "bybit" to Color(0xFFF7A600), "okx" to Color(0xFF9E9E9E), "coinbase" to Color(0xFF0052FF),
    "kraken" to Color(0xFF5741D9), "kucoin" to Color(0xFF23AF91), "bitfinex" to Color(0xFF16B157), "gemini" to Color(0xFF00DCFA),
    "htx" to Color(0xFF2B66F6), "huobi" to Color(0xFF2B66F6), "gate" to Color(0xFF2354E6), "crypto.com" to Color(0xFF3B82F6),
    "mexc" to Color(0xFF1972F5), "bitget" to Color(0xFF00F0FF), "bingx" to Color(0xFF2A54FF), "bitmart" to Color(0xFF3B8BFF),
    "upbit" to Color(0xFF2D6CDF), "wazirx" to Color(0xFF3067F0), "coindcx" to Color(0xFF2E7CF6),
    "trust" to Color(0xFF3375BB), "metamask" to Color(0xFFF6851B), "ledger" to Color(0xFFFF5300), "phantom" to Color(0xFFAB9FF2),
    "tonkeeper" to Color(0xFF45AEF5), "exodus" to Color(0xFF1F7CF6), "safepal" to Color(0xFF4A21EF), "trezor" to Color(0xFF00854D),
)

/** Colours for every other folder, one per name (the same folder always gets the same colour). */
private val FOLDER_PALETTE = listOf(
    Color(0xFF8B7CFF), Color(0xFF3B8BF6), Color(0xFF2EBD85), Color(0xFFF5A623), Color(0xFFEC4899),
    Color(0xFF19B5A5), Color(0xFFF97316), Color(0xFFA855F7), Color(0xFF06B6D4), Color(0xFFF6465D),
)

/** A folder's colour: its exchange's or wallet app's brand colour when the name says which, else one picked by the name. */
fun folderColor(name: String): Color {
    if (name.isBlank()) return Color(0xFF8A8A8A)
    val n = name.lowercase()
    FOLDER_BRANDS.firstOrNull { (key, _) -> n.contains(key) }?.let { return it.second }
    return FOLDER_PALETTE[Math.floorMod(name.hashCode(), FOLDER_PALETTE.size)]
}

/** Round badge for an exchange (a monogram in its brand colour). */
@Composable
fun ExchangeBadge(name: String, size: Dp) {
    val b = BRANDS[name] ?: Brand(Color(0xFF616161), Color.White, name.take(1).uppercase())
    b.image?.let {
        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(it), contentDescription = name,
            modifier = Modifier.size(size).clip(CircleShape))
        return
    }
    Box(Modifier.size(size).clip(CircleShape).background(b.bg), contentAlignment = Alignment.Center) {
        if (b.mark != null) {
            androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(b.mark), contentDescription = name,
                tint = b.fg, modifier = Modifier.size(size * 0.6f))
        } else {
            Text(b.letters, color = b.fg, fontWeight = FontWeight.Bold,
                fontSize = (size.value * (if (b.letters.length >= 3) 0.30f else 0.42f)).sp, maxLines = 1)
        }
    }
}

/**
 * Exchange identity at the top of an address: badge and name in large type, then a high-contrast warning that the
 * balance isn't any customer's account balance. A guessed match gets no badge (a dashed "?" instead) and says
 * "Possibly", so it never looks like fact.
 */
@Composable
fun ExchangeCard(id: ExchangeId) {
    val sure = id.attribution != Attribution.POSSIBLE
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (sure) {
                ExchangeBadge(id.name, 52.dp)
            } else {
                Box(
                    Modifier.size(52.dp).border(2.dp, MaterialTheme.colorScheme.outline, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Text("?", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(if (sure) id.name else tr("Possibly") + " " + id.name, style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold)
                Text(
                    when (id.attribution) {
                        Attribution.CONFIRMED -> tr("Confirmed exchange wallet (public label)")
                        Attribution.YOUR_LABEL -> tr("Exchange deposit address (your label)")
                        Attribution.POSSIBLE -> tr("Not confirmed: guessed from where its money goes")
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        BalanceWarning(id)
    }
}

/** Amber, high-contrast: the on-chain balance here isn't a customer's account balance. */
@Composable
fun BalanceWarning(id: ExchangeId) {
    val amber = Color(0xFFF5A623)
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = amber.copy(alpha = 0.18f)),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = amber, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Not an account balance", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(
                    tr(if (id.ownWallet) "This wallet holds many customers' coins together. Its balance isn't any one customer's balance on %s."
                    else "%s moves deposits on to its own wallets, so this address's balance isn't the account balance on %s.")
                        .replace("%s", id.name),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
