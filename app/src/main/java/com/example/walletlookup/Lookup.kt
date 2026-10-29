package com.example.walletlookup

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * Address uamilies the app understands. One address can live on several networks
 * (every 0x… address works on all EVM chains; old 1…/3… Bitcoin addresses also exist on Bitcoin Cash),
 * so a family is looked up on all of its networks and shown as one card.
 */
enum class AddrType(val label: String) {
    EVM("Ethereum & EVM networks"),
    BITCOIN("Bitcoin"),
    BITCOIN_CASH("Bitcoin Cash"),
    LITECOIN("Litecoin"),
    DOGECOIN("Dogecoin"),
    TRON("Tron"),
    SOLANA("Solana"),
    TON("TON"),
    XRP("XRP Ledger"),
    APTOS("Aptos"),
    CARDANO("Cardano"),
    POLKADOT("Polkadot"),
    COSMOS("Cosmos Hub"),
    STELLAR("Stellar"),
    ALGORAND("Algorand"),
    NEAR("NEAR Protocol"),
    TEZOS("Tezos"),
    EOS("EOS"),
    BNB_BEACON("BNB Beacon Chain (BEP2)"),
    LIGHTNING("Bitcoin Lightning"),
}

/** Balances of one address on one network. [chain] is the network name, e.g. "BSC" or "Aptos". */
data class ChainResult(
    val chain: String,
    val rows: List<Pair<String, String>> = emptyList(),
    val explorerUrl: String? = null,
    val active: Boolean = true,
    val error: String? = null,
    val usdt: BigDecimal = BigDecimal.ZERO,
    val nativeSym: String = "",
    val native: BigDecimal = BigDecimal.ZERO,
    /** Everything on this network in dollars (stablecoins + native coin), null if the price is unavailable. */
    val usd: BigDecimal? = null,
) {
    /** Network name without the older " · USDT …" suffix that cached results may still carry. */
    val network: String get() = chain.substringBefore(" ·")
}

/** Where coin prices come from; chosen in Settings. */
enum class PriceSource(val label: String) {
    AUTO("Auto (CoinMarketCap, then Binance, Bybit, CoinGecko)"),
    COINMARKETCAP("CoinMarketCap"),
    BINANCE("Binance"),
    BYBIT("Bybit"),
    COINGECKO("CoinGecko"),
}

@Volatile var priceSource: PriceSource = PriceSource.AUTO

/**
 * Every balance an address holds, keyed "SYMBOL|Network" (e.g. "USDT|Aptos", "APT|Aptos").
 * Networks that failed to load are left out so they are never mistaken for a change.
 */
suspend fun snapshot(address: String, networks: Set<String>? = null): Map<String, BigDecimal> {
    val type = detect(address) ?: return emptyMap()
    return lookup(address, type, withPrices = false, networks = networks).filter { it.error == null && it.nativeSym.isNotEmpty() }.flatMap { r ->
        listOf("USDT|${r.network}" to r.usdt, "${r.nativeSym}|${r.network}" to r.native)
    }.toMap()
}

data class WalletTotals(val usdt: BigDecimal, val usd: BigDecimal?)

/** A saved lookup result and when it was uetched (epoch millis). */
data class Cached(val results: List<ChainResult>, val time: Long) {
    val totals: WalletTotals get() = totalsOf(results)
}

fun totalsOf(results: List<ChainResult>): WalletTotals = WalletTotals(
    results.fold(BigDecimal.ZERO) { s, r -> s + r.usdt },
    if (results.any { it.error == null && it.usd == null && it.active }) null
    else results.fold(BigDecimal.ZERO) { s, r -> s + (r.usd ?: BigDecimal.ZERO) },
)

// ===================== Address detection =====================

private const val B58 = "[1-9A-HJ-NP-Za-km-z]"

/**
 * Turns whatever was pasted or scanned into a bare address: strips payment-URI schemes
 * ("ethereum:", "bitcoin:", "tron:", "ton://transfer/" …), amounts and chain ids, and
 * picks the recipient out of ERC-20 transfer links.
 */
fun cleanAddress(raw: String): String {
    var s = raw.trim().replace("\n", "").replace(" ", "")
    // EIP-681 token transfer: ethereum:0xTOKEN@56/transfer?address=0xRECIPIENT&uint256=…
    Regex("[?&]address=([^&]+)").find(s)?.let { if (s.contains("/transfer")) return it.groupValues[1] }
    s = s.removePrefix("ton://transfer/")
    val raw0x = Regex("^-?\\d:[0-9a-fA-F]{64}$")
    if (!raw0x.matches(s.substringBefore('?')) && Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(s)) {
        val scheme = s.substringBefore(':').lowercase()
        s = s.substringAfter(':').removePrefix("//")
        if (scheme in setOf("bitcoincash", "bchtest")) s = "bitcoincash:$s"
    }
    s = s.substringBefore('?')
    if (s.startsWith("0x")) s = s.substringBefore('@').substringBefore('/')
    return s
}

// ===================== Network-specific addresses =====================

/** EVM networks a 0x address can be pinned to (shown in the network picker). */
val EVM_PICK = listOf("Ethereum", "BSC", "Polygon", "Arbitrum", "Optimism", "Base", "Avalanche", "Celo", "Sonic",
    "Ethereum Classic", "VeChain")

/**
 * A saved/viewed address may carry one EVM network after an @ (e.g. "0xabc…@BSC") so the same 0x address
 * can be saved separately for BSC and Ethereum. These helpers split that apart.
 */
fun rawAddress(a: String): String = a.trim().substringBefore('@')
fun netOf(a: String): String? = a.substringAfter('@', "").ifEmpty { null }
fun withNet(raw: String, network: String?): String = if (network == null) raw else "$raw@$network"

/** Network label for display: the pinned network, or the address family. */
fun labelOf(a: String): String = netOf(a) ?: detect(a)?.label ?: "Unknown network"

/** Block-explorer page for an address on its network (DeBank for "all EVM networks"). */
fun explorerUrl(a: String): String? {
    val r = rawAddress(a)
    netOf(a)?.let { n ->
        return when (n) {
            "Ethereum" -> "https://etherscan.io/address/$r"
            "BSC" -> "https://bscscan.com/address/$r"
            "Polygon" -> "https://polygonscan.com/address/$r"
            "Arbitrum" -> "https://arbiscan.io/address/$r"
            "Optimism" -> "https://optimistic.etherscan.io/address/$r"
            "Base" -> "https://basescan.org/address/$r"
            "Avalanche" -> "https://snowtrace.io/address/$r"
            "Celo" -> "https://celoscan.io/address/$r"
            "Sonic" -> "https://sonicscan.org/address/$r"
            "Ethereum Classic" -> "https://etc.blockscout.com/address/$r"
            "VeChain" -> "https://vechainstats.com/account/$r"
            else -> "https://debank.com/profile/$r"
        }
    }
    return when (detect(r)) {
        AddrType.EVM -> "https://debank.com/profile/$r"
        AddrType.BITCOIN -> "https://mempool.space/address/$r"
        AddrType.BITCOIN_CASH -> "https://blockchair.com/bitcoin-cash/address/${r.removePrefix("bitcoincash:")}"
        AddrType.LITECOIN -> "https://litecoinspace.org/address/$r"
        AddrType.DOGECOIN -> "https://blockchair.com/dogecoin/address/$r"
        AddrType.TRON -> "https://tronscan.org/#/address/$r"
        AddrType.SOLANA -> "https://solscan.io/account/$r"
        AddrType.TON -> "https://tonviewer.com/$r"
        AddrType.XRP -> "https://livenet.xrpl.org/accounts/$r"
        AddrType.APTOS -> "https://explorer.aptoslabs.com/account/$r?network=mainnet"
        AddrType.CARDANO -> "https://cardanoscan.io/address/$r"
        AddrType.POLKADOT -> "https://polkadot.subscan.io/account/$r"
        AddrType.COSMOS -> "https://www.mintscan.io/cosmos/address/$r"
        AddrType.STELLAR -> "https://stellar.expert/explorer/public/account/$r"
        AddrType.ALGORAND -> "https://allo.info/account/$r"
        AddrType.NEAR -> "https://nearblocks.io/address/$r"
        AddrType.TEZOS -> "https://tzkt.io/$r"
        AddrType.EOS -> "https://bloks.io/account/$r"
        else -> null
    }
}

fun detect(raw: String): AddrType? {
    val a = rawAddress(raw)
    if (a.isEmpty()) return null
    val l = a.lowercase()
    return when {
        l.startsWith("lnbc") || l.startsWith("lnurl") || l.startsWith("lightning:") -> AddrType.LIGHTNING
        Regex("^0x[0-9a-fA-F]{40}$").matches(a) -> AddrType.EVM
        Regex("^0x[0-9a-fA-F]{41,64}$").matches(a) -> AddrType.APTOS
        Regex("^(bc1)[02-9ac-hj-np-z]{11,87}$").matches(l) -> AddrType.BITCOIN
        Regex("^[13]$B58{25,33}$").matches(a) -> AddrType.BITCOIN
        Regex("^(bitcoincash:)?[qp][02-9ac-hj-np-z]{41}$").matches(l) -> AddrType.BITCOIN_CASH
        Regex("^ltc1[02-9ac-hj-np-z]{11,87}$").matches(l) -> AddrType.LITECOIN
        Regex("^[LM]$B58{26,33}$").matches(a) -> AddrType.LITECOIN
        Regex("^D$B58{25,33}$").matches(a) -> AddrType.DOGECOIN
        Regex("^T$B58{33}$").matches(a) -> AddrType.TRON
        Regex("^r$B58{24,34}$").matches(a) -> AddrType.XRP
        Regex("^addr1[02-9ac-hj-np-z]{50,120}$").matches(l) -> AddrType.CARDANO
        Regex("^cosmos1[02-9ac-hj-np-z]{38,58}$").matches(l) -> AddrType.COSMOS
        Regex("^bnb1[02-9ac-hj-np-z]{38}$").matches(l) -> AddrType.BNB_BEACON
        Regex("^G[A-Z2-7]{55}$").matches(a) -> AddrType.STELLAR
        Regex("^[A-Z2-7]{58}$").matches(a) -> AddrType.ALGORAND
        Regex("^(tz1|tz2|tz3|tz4|KT1)$B58{33}$").matches(a) -> AddrType.TEZOS
        Regex("^1$B58{46,47}$").matches(a) -> AddrType.POLKADOT
        Regex("^(EQ|UQ|kQ|0Q|Eu|Uu|ku|0f)[A-Za-z0-9_+/-]{46}$").matches(a) -> AddrType.TON
        Regex("^-?[0-9]:[0-9a-fA-F]{64}$").matches(a) -> AddrType.TON
        Regex("^[0-9a-f]{64}$").matches(a) -> AddrType.NEAR
        Regex("^([a-z0-9_-]+\\.)+(near|tg)$").matches(l) -> AddrType.NEAR
        Regex("^$B58{32,44}$").matches(a) -> AddrType.SOLANA
        Regex("^[a-z1-5.]{1,12}$").matches(a) -> AddrType.EOS
        else -> null
    }
}

// ===================== Lookup =====================

suspend fun lookup(
    raw: String,
    type: AddrType,
    withPrices: Boolean = true,
    /** EVM only: check just these networks (null = all). */
    networks: Set<String>? = null,
): List<ChainResult> = coroutineScope {
    val a = rawAddress(raw)
    @Suppress("NAME_SHADOWING")
    val networks = netOf(raw)?.let { setOf(it) } ?: networks
    val prices = async(Dispatchers.IO) { if (withPrices) prices() else emptyMap() }
    fun job(name: String, block: suspend (Map<String, Double>) -> ChainResult) =
        async(Dispatchers.IO) { safe(name) { block(prices.await()) } }

    val jobs = when (type) {
        AddrType.EVM -> EVM_CHAINS.filter { networks == null || it.name in networks }.map { c -> job(c.name) { evm(c, a, it) } } +
            (if (networks == null || "VeChain" in networks) listOf(job("VeChain") { vechain(a, it) }) else emptyList())
        AddrType.BITCOIN -> buildList {
            add(job("Bitcoin") { esplora("Bitcoin", "https://mempool.space/api", "BTC", "bitcoin", "https://mempool.space/address/", a, it) })
            // Legacy 1…/3… addresses also exist on Bitcoin Cash.
            if (!a.lowercase().startsWith("bc1")) add(job("Bitcoin Cash") { bch(a, it) })
        }
        AddrType.BITCOIN_CASH -> listOf(job("Bitcoin Cash") { bch(a, it) })
        AddrType.LITECOIN -> listOf(job("Litecoin") { p ->
            // litecoinspace.org is sometimes slow; BlockCypher is the backup.
            runCatching {
                esplora("Litecoin", "https://litecoinspace.org/api", "LTC", "litecoin", "https://litecoinspace.org/address/", a, p)
            }.getOrElse { blockcypher("Litecoin", "ltc", "LTC", "litecoin", "https://litecoinspace.org/address/", a, p) }
        })
        AddrType.DOGECOIN -> listOf(job("Dogecoin") {
            blockcypher("Dogecoin", "doge", "DOGE", "dogecoin", "https://blockchair.com/dogecoin/address/", a, it)
        })
        AddrType.TRON -> listOf(job("Tron") { tron(a, it) })
        AddrType.SOLANA -> listOf(job("Solana") { solana(a, it) })
        AddrType.TON -> listOf(job("TON") { ton(a, it) })
        AddrType.XRP -> listOf(job("XRP Ledger") { xrp(a, it) })
        AddrType.APTOS -> listOf(job("Aptos") { aptos(a, it) })
        AddrType.CARDANO -> listOf(job("Cardano") { cardano(a, it) })
        AddrType.POLKADOT -> listOf(job("Polkadot") { polkadot(a, it) })
        AddrType.COSMOS -> listOf(job("Cosmos Hub") { cosmos(a, it) })
        AddrType.STELLAR -> listOf(job("Stellar") { stellar(a, it) })
        AddrType.ALGORAND -> listOf(job("Algorand") { algorand(a, it) })
        AddrType.NEAR -> listOf(job("NEAR") { near(a, it) })
        AddrType.TEZOS -> listOf(job("Tezos") { tezos(a, it) })
        AddrType.EOS -> listOf(job("EOS") { eos(a, it) })
        AddrType.BNB_BEACON -> listOf(job("BNB Beacon Chain") {
            ChainResult("BNB Beacon Chain", listOf("Status" to
                "BNB Beacon Chain (BEP2) was shut down in 2024. Its balances can't be checked anymore."), active = false)
        })
        AddrType.LIGHTNING -> listOf(job("Lightning") {
            ChainResult("Lightning", listOf("Status" to
                "Lightning balances are private, so they can't be looked up from an invoice or address."), active = false)
        })
    }
    // Networks with balance/activity first, uailures last.
    jobs.awaitAll().sortedBy { if (it.error != null) 2 else if (it.active) 0 else 1 }
}

private inline fun safe(chain: String, block: () -> ChainResult): ChainResult =
    try { block() } catch (e: Exception) { ChainResult(chain, error = e.message ?: e.javaClass.simpleName) }

/** Builds a result: "Total value", stablecoins, the native coin, then any extra rows. */
private fun build(
    chain: String,
    sym: String,
    gecko: String,
    native: BigDecimal,
    p: Map<String, Double>,
    explorer: String?,
    stables: List<Pair<String, BigDecimal>> = emptyList(),
    extra: List<Pair<String, String>> = emptyList(),
    activity: Boolean = false,
): ChainResult {
    val stableSum = stables.fold(BigDecimal.ZERO) { s, x -> s + x.second }
    val total = worth(stableSum, native, p[gecko])
    val rows = mutableListOf("Total value" to money(total))
    stables.forEach { (label, v) ->
        if (v.signum() > 0 || label.startsWith("USDT")) rows += label to fmt2(v)
    }
    rows += sym to "${fmt(native)} · ${usd(native, p[gecko])}"
    rows += extra
    return ChainResult(
        chain, rows, explorer,
        active = native.signum() > 0 || stableSum.signum() > 0 || activity,
        usdt = stables.filter { it.first.startsWith("USDT") }.fold(BigDecimal.ZERO) { s, x -> s + x.second },
        nativeSym = sym, native = native, usd = total,
    )
}

// ---------- EVM networks (public JSON-RPC) ----------

private data class Token(val symbol: String, val contract: String, val decimals: Int)
private data class EvmChain(
    val name: String, val rpcs: List<String>, val sym: String, val gecko: String,
    val explorer: String, val tokens: List<Token>,
)

private val EVM_CHAINS = listOf(
    EvmChain("Ethereum", listOf("https://ethereum-rpc.publicnode.com"), "ETH", "ethereum", "https://etherscan.io/address/", listOf(
        Token("USDT (ERC20)", "0xdAC17F958D2ee523a2206206994597C13D831ec7", 6),
        Token("USDC (ERC20)", "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48", 6),
    )),
    EvmChain("BSC", listOf("https://bsc-rpc.publicnode.com"), "BNB", "binancecoin", "https://bscscan.com/address/", listOf(
        Token("USDT (BEP20)", "0x55d398326f99059fF775485246999027B3197955", 18),
        Token("USDC (BEP20)", "0x8AC76a51cc950d9822D68b83fE1Ad97B32Cd580d", 18),
    )),
    EvmChain("Polygon", listOf("https://polygon-bor-rpc.publicnode.com"), "POL", "polygon-ecosystem-token", "https://polygonscan.com/address/", listOf(
        Token("USDT (Polygon)", "0xc2132D05D31c914a87C6611C10748AEb04B58e8F", 6),
        Token("USDC (Polygon)", "0x3c499c542cEF5E3811e1192ce70d8cC03d5c3359", 6),
    )),
    EvmChain("Arbitrum", listOf("https://arbitrum-one-rpc.publicnode.com"), "ETH", "ethereum", "https://arbiscan.io/address/", listOf(
        Token("USDT (Arbitrum)", "0xFd086bC7CD5C481DCC9C85ebE478A1C0b69FCbb9", 6),
        Token("USDC (Arbitrum)", "0xaf88d065e77c8cC2239327C5EDb3A432268e5831", 6),
    )),
    EvmChain("Optimism", listOf("https://optimism-rpc.publicnode.com"), "ETH", "ethereum", "https://optimistic.etherscan.io/address/", listOf(
        Token("USDT (Optimism)", "0x94b008aA00579c1307B0EF2c499aD98a8ce58e58", 6),
        Token("USDC (Optimism)", "0x0b2C639c533813f4Aa9D7837CAf62653d097Ff85", 6),
    )),
    EvmChain("Base", listOf("https://base-rpc.publicnode.com"), "ETH", "ethereum", "https://basescan.org/address/", listOf(
        Token("USDC (Base)", "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913", 6),
    )),
    EvmChain("Avalanche", listOf("https://avalanche-c-chain-rpc.publicnode.com"), "AVAX", "avalanche-2", "https://snowtrace.io/address/", listOf(
        Token("USDT (Avalanche)", "0x9702230A8Ea53601f5cD2dc00fDBc13d4dF4A8c7", 6),
        Token("USDC (Avalanche)", "0xB97EF9Ef8734C71904D8002F8b6Bc66Dd9c48a6E", 6),
    )),
    EvmChain("Celo", listOf("https://forno.celo.org", "https://celo-rpc.publicnode.com"), "CELO", "celo", "https://celoscan.io/address/", listOf(
        Token("USDT (Celo)", "0x48065fbBE25f71C9282ddf5e1cD6D6A887483D5e", 6),
        Token("USDC (Celo)", "0xcebA9300f2b948710d2653dD7B07f33A8B32118C", 6),
    )),
    EvmChain("Sonic", listOf("https://rpc.soniclabs.com", "https://sonic-rpc.publicnode.com"), "S", "sonic-3", "https://sonicscan.org/address/", listOf(
        Token("USDC (Sonic)", "0x29219dd400f2Bf60E5a23d13Be72B486D4038894", 6),
    )),
    EvmChain("Ethereum Classic", listOf("https://etc.etcdesktop.com", "https://0xrpc.io/etc"), "ETC", "ethereum-classic",
        "https://etc.blockscout.com/address/", emptyList()),
)

/** EVM network names in the order they are checked (used for history and explorers). */
val EVM_NETWORKS: List<String> get() = EVM_CHAINS.map { it.name } + "VeChain"

private fun evm(c: EvmChain, a: String, p: Map<String, Double>): ChainResult {
    fun call(id: Int, method: String, params: JSONArray) =
        JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params)

    val batch = JSONArray()
        .put(call(1, "eth_getBalance", JSONArray().put(a).put("latest")))
        .put(call(2, "eth_getTransactionCount", JSONArray().put(a).put("latest")))
    val balanceOu = "0x70a08231" + a.removePrefix("0x").lowercase().padStart(64, '0')
    c.tokens.forEachIndexed { i, t ->
        batch.put(call(10 + i, "eth_call", JSONArray().put(JSONObject().put("to", t.contract).put("data", balanceOu)).put("latest")))
    }

    var lastError: Exception? = null
    val resp = c.rpcs.firstNotNullOfOrNull { url ->
        try { JSONArray(http(url, batch.toString())) } catch (e: Exception) { lastError = e; null }
    } ?: throw lastError ?: IOException("No response")
    val byId = (0 until resp.length()).map { resp.getJSONObject(it) }.associateBy { it.optInt("id") }
    fun hex(id: Int): BigInteger {
        val r = byId[id] ?: throw IOException("No response from RPC")
        r.optJSONObject("error")?.let { throw IOException(it.optString("message")) }
        val h = r.optString("result").removePrefix("0x")
        return if (h.isEmpty()) BigInteger.ZERO else BigInteger(h, 16)
    }

    val stables = c.tokens.mapIndexed { i, t ->
        t.symbol to amount(runCatching { hex(10 + i) }.getOrDefault(BigInteger.ZERO), t.decimals)
    }
    val nonce = hex(2)
    return build(
        c.name, c.sym, c.gecko, amount(hex(1), 18), p, c.explorer + a, stables,
        extra = listOf("Transactions sent" to nonce.toString()),
        activity = nonce.signum() > 0,
    )
}

private fun vechain(a: String, p: Map<String, Double>): ChainResult {
    val j = JSONObject(http("https://mainnet.vechain.org/accounts/$a"))
    val vet = amount(BigInteger(j.getString("balance").removePrefix("0x").ifEmpty { "0" }, 16), 18)
    val vtho = amount(BigInteger(j.getString("energy").removePrefix("0x").ifEmpty { "0" }, 16), 18)
    return build(
        "VeChain", "VET", "vechain", vet, p, "https://vechainstats.com/account/$a",
        extra = listOf("VTHO (energy)" to fmt(vtho)),
        activity = vtho.signum() > 0,
    )
}

// ---------- Bitcoin / Litecoin (Esplora), Bitcoin Cash (Haskoin), Dogecoin (BlockCypher) ----------

private fun esplora(name: String, base: String, sym: String, gecko: String, explorer: String, a: String, p: Map<String, Double>): ChainResult {
    val addr = if (a.startsWith("bc1", true) || a.startsWith("ltc1", true)) a.lowercase() else a
    val j = JSONObject(http("$base/address/$addr"))
    val c = j.getJSONObject("chain_stats")
    val m = j.getJSONObject("mempool_stats")
    val received = c.getLong("funded_txo_sum")
    val sent = c.getLong("spent_txo_sum")
    val pending = m.getLong("funded_txo_sum") - m.getLong("spent_txo_sum")
    val txCount = c.getInt("tx_count") + m.getInt("tx_count")
    val extra = mutableListOf(
        "Total received" to "${fmt(amount(BigInteger.valueOf(received), 8))} $sym",
        "Total sent" to "${fmt(amount(BigInteger.valueOf(sent), 8))} $sym",
        "Transactions" to txCount.toString(),
    )
    if (pending != 0L) extra += "Pending" to "${fmt(amount(BigInteger.valueOf(pending), 8))} $sym"
    return build(name, sym, gecko, amount(BigInteger.valueOf(received - sent), 8), p, explorer + addr,
        extra = extra, activity = txCount > 0)
}

private fun bch(a: String, p: Map<String, Double>): ChainResult {
    val j = JSONObject(http("https://api.haskoin.com/bch/address/${URLEncoder.encode(a, "UTF-8")}/balance"))
    val bal = amount(BigInteger.valueOf(j.getLong("confirmed") + j.optLong("unconfirmed")), 8)
    val txs = j.optInt("txs")
    return build("Bitcoin Cash", "BCH", "bitcoin-cash", bal, p,
        "https://blockchair.com/bitcoin-cash/address/" + j.optString("address", a).removePrefix("bitcoincash:"),
        extra = listOf(
            "Total received" to "${fmt(amount(BigInteger.valueOf(j.optLong("received")), 8))} BCH",
            "Transactions" to txs.toString(),
        ),
        activity = txs > 0)
}

private fun blockcypher(name: String, coin: String, sym: String, gecko: String, explorer: String, a: String, p: Map<String, Double>): ChainResult {
    val j = JSONObject(http("https://api.blockcypher.com/v1/$coin/main/addrs/$a/balance"))
    val txs = j.getInt("final_n_tx")
    return build(name, sym, gecko, amount(BigInteger.valueOf(j.getLong("final_balance")), 8), p, explorer + a,
        extra = listOf(
            "Total received" to "${fmt(amount(BigInteger.valueOf(j.getLong("total_received")), 8))} $sym",
            "Transactions" to txs.toString(),
        ),
        activity = txs > 0)
}

// ---------- Tron (TronGrid) ----------

private const val USDT_TRC20 = "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t"

private fun tron(a: String, p: Map<String, Double>): ChainResult {
    val explorer = "https://tronscan.org/#/address/$a"
    // TronGrid rate-limits quick bursts; publicnode's Tron node answers the same balances as a backup.
    val data = try {
        JSONObject(http("https://api.trongrid.io/v1/accounts/$a")).optJSONArray("data")
    } catch (e: Exception) {
        return tronBackup(a, p, explorer)
    }
    if (data == null || data.length() == 0) {
        return build("Tron", "TRX", "tron", BigDecimal.ZERO, p, explorer, listOf("USDT (TRC20)" to BigDecimal.ZERO),
            extra = listOf("Status" to "Not activated (no on-chain activity yet)"))
    }
    val acc = data.getJSONObject(0)
    var usdt = BigDecimal.ZERO
    acc.optJSONArray("trc20")?.let { list ->
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            if (o.has(USDT_TRC20)) usdt = amount(BigInteger(o.getString(USDT_TRC20)), 6)
        }
    }
    val extra = mutableListOf<Pair<String, String>>()
    if (acc.has("create_time")) extra += "Created" to date(acc.getLong("create_time") / 1000)
    acc.optLong("latest_opration_time").takeIf { it > 0 }?.let { extra += "Last activity" to date(it / 1000) }
    return build("Tron", "TRX", "tron", amount(BigInteger.valueOf(acc.optLong("balance")), 6), p, explorer,
        listOf("USDT (TRC20)" to usdt), extra, activity = true)
}

private fun tronBackup(a: String, p: Map<String, Double>, explorer: String): ChainResult {
    val node = "https://tron-rpc.publicnode.com/wallet"
    val acc = JSONObject(http("$node/getaccount", JSONObject().put("address", a).put("visible", true).toString()))
    if (!acc.has("address")) {
        return build("Tron", "TRX", "tron", BigDecimal.ZERO, p, explorer, listOf("USDT (TRC20)" to BigDecimal.ZERO),
            extra = listOf("Status" to "Not activated (no on-chain activity yet)"))
    }
    // USDT: ask the token contract for balanceOf(address); the address goes in as 32 bytes of hex.
    val param = tronHex(a).padStart(64, '0')
    val r = JSONObject(http("$node/triggerconstantcontract", JSONObject().put("owner_address", a).put("contract_address", USDT_TRC20)
        .put("function_selector", "balanceOf(address)").put("parameter", param).put("visible", true).toString()))
    val usdt = r.optJSONArray("constant_result")?.optString(0)?.ifEmpty { "0" }?.let { amount(BigInteger(it, 16), 6) } ?: BigDecimal.ZERO
    val extra = mutableListOf<Pair<String, String>>()
    if (acc.has("create_time")) extra += "Created" to date(acc.getLong("create_time") / 1000)
    return build("Tron", "TRX", "tron", amount(BigInteger.valueOf(acc.optLong("balance")), 6), p, explorer,
        listOf("USDT (TRC20)" to usdt), extra, activity = true)
}

// ---------- Solana (public RPC) ----------

/**
 * Solana's free public RPC rate-limits *batched* requests (it answers "Too many requests"), so each call is
 * sent on its own. One failed token call doesn't fail the whole lookup.
 */
internal fun solanaCall(method: String, params: JSONArray): JSONObject {
    val body = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", method).put("params", params).toString()
    var last: Exception? = null
    // publicnode answers in well under a second; Solana's own public node is slow and often refuses, so it's the backup.
    for (url in listOf("https://solana-rpc.publicnode.com", "https://api.mainnet-beta.solana.com")) {
        try {
            val r = JSONObject(http(url, body))
            r.optJSONObject("error")?.let { throw IOException(it.optString("message")) }
            return r
        } catch (e: Exception) {
            last = e
        }
    }
    throw last ?: IOException("Solana RPC error")
}

private fun solana(a: String, p: Map<String, Double>): ChainResult {
    val lamports = solanaCall("getBalance", JSONArray().put(a)).getJSONObject("result").getLong("value")
    fun token(mint: String): BigDecimal = runCatching {
        val accounts = solanaCall("getTokenAccountsByOwner", JSONArray().put(a).put(JSONObject().put("mint", mint))
            .put(JSONObject().put("encoding", "jsonParsed"))).getJSONObject("result").getJSONArray("value")
        var total = BigDecimal.ZERO
        for (i in 0 until accounts.length()) {
            total += BigDecimal(accounts.getJSONObject(i).getJSONObject("account").getJSONObject("data")
                .getJSONObject("parsed").getJSONObject("info").getJSONObject("tokenAmount").getString("uiAmountString"))
        }
        total
    }.getOrDefault(BigDecimal.ZERO)
    val sigs = runCatching {
        solanaCall("getSignaturesForAddress", JSONArray().put(a).put(JSONObject().put("limit", 1))).getJSONArray("result")
    }.getOrNull()
    val extra = mutableListOf<Pair<String, String>>()
    sigs?.takeIf { it.length() > 0 }?.getJSONObject(0)?.optLong("blockTime")?.takeIf { it > 0 }?.let {
        extra += "Last activity" to date(it)
    }
    return build("Solana", "SOL", "solana", amount(BigInteger.valueOf(lamports), 9), p,
        "https://solscan.io/account/$a",
        listOf("USDT (Solana)" to token("Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB"),
            "USDC (Solana)" to token("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v")),
        extra, activity = sigs != null && sigs.length() > 0)
}
// ---------- TON (tonapi for the account, toncenter for USDT) ----------

private const val USDT_TON = "EQCxE6mUtQJKFnGfaROTKOt1lZbDiiX1kCixRv7Nw2Id_sDs"

private fun ton(a: String, p: Map<String, Double>): ChainResult {
    val enc = URLEncoder.encode(a, "UTF-8")
    val acc = JSONObject(http("https://tonapi.io/v2/accounts/$enc"))
    val status = acc.optString("status", "unknown")
    val jw = JSONObject(http(
        "https://toncenter.com/api/v3/jetton/wallets?owner_address=$enc&jetton_address=$USDT_TON&limit=1"
    )).optJSONArray("jetton_wallets")
    val usdt = if (jw != null && jw.length() > 0) amount(BigInteger(jw.getJSONObject(0).getString("balance")), 6) else BigDecimal.ZERO
    // "uninit" just means the wallet hasn't sent anything yet (TON sets it up on the first send): say that plainly.
    val extra = mutableListOf("Status" to when (status) {
        "uninit" -> "Not set up yet (normal until it sends)"
        "active" -> "Active"
        "frozen" -> "Frozen"
        else -> status.replaceFirstChar { it.uppercase() }
    })
    acc.optLong("last_activity").takeIf { it > 0 }?.let { extra += "Last activity" to date(it) }
    return build("TON", "TON", "the-open-network", amount(BigInteger.valueOf(acc.optLong("balance")), 9), p,
        "https://tonviewer.com/$a", listOf("USDT (TON)" to usdt), extra, activity = status == "active")
}

// ---------- XRP Ledger ----------

private fun xrp(a: String, p: Map<String, Double>): ChainResult {
    val body = JSONObject().put("method", "account_info")
        .put("params", JSONArray().put(JSONObject().put("account", a).put("ledger_index", "validated")))
    val res = JSONObject(http("https://xrplcluster.com/", body.toString())).getJSONObject("result")
    val explorer = "https://livenet.xrpl.org/accounts/$a"
    if (res.optString("status") == "error") {
        if (res.optString("error") == "actNotFound") {
            return build("XRP Ledger", "XRP", "ripple", BigDecimal.ZERO, p, explorer,
                extra = listOf("Status" to "Not activated (no on-chain activity yet)"))
        }
        throw IOException(res.optString("error_message", res.optString("error")))
    }
    val d = res.getJSONObject("account_data")
    return build("XRP Ledger", "XRP", "ripple", amount(BigInteger(d.getString("Balance")), 6), p, explorer,
        extra = listOf("Transactions sent" to d.optLong("Sequence").toString()), activity = true)
}

// ---------- Aptos (official fullnode REST API) ----------

private const val APTOS = "https://fullnode.mainnet.aptoslabs.com/v1"
private const val USDT_APTOS = "0x357b0b74bc833e95a115ad22604854d6b0fca151cecd94111770e5d6ffc9dc2b"

private fun aptos(raw: String, p: Map<String, Double>): ChainResult {
    val a = "0x" + raw.removePrefix("0x").lowercase().padStart(64, '0')
    fun view(fn: String, type: String, vararg args: String): BigInteger {
        val body = JSONObject().put("function", fn)
            .put("type_arguments", JSONArray().put(type))
            .put("arguments", JSONArray(args.toList()))
        return BigInteger(JSONArray(http("$APTOS/view", body.toString())).getString(0))
    }
    val usdt = amount(view("0x1::primary_fungible_store::balance", "0x1::fungible_asset::Metadata", a, USDT_APTOS), 6)
    val apt = amount(view("0x1::coin::balance", "0x1::aptos_coin::AptosCoin", a), 8)
    val seq = runCatching { JSONObject(http("$APTOS/accounts/$a")).optString("sequence_number", "0") }.getOrDefault("0")
    return build("Aptos", "APT", "aptos", apt, p, "https://explorer.aptoslabs.com/account/$a?network=mainnet",
        listOf("USDT (Aptos)" to usdt), listOf("Transactions sent" to seq), activity = seq != "0")
}

// ---------- Cardano (Koios) ----------

private fun cardano(a: String, p: Map<String, Double>): ChainResult {
    val arr = JSONArray(http("https://api.koios.rest/api/v1/address_info",
        JSONObject().put("_addresses", JSONArray().put(a)).toString()))
    val o = if (arr.length() > 0) arr.getJSONObject(0) else null
    val ada = amount(BigInteger(o?.optString("balance")?.ifEmpty { "0" } ?: "0"), 6)
    val extra = mutableListOf<Pair<String, String>>()
    o?.optString("stake_address")?.takeIf { it.isNotBlank() && it != "null" }?.let { extra += "Stake address" to it.take(14) + "…" }
    return build("Cardano", "ADA", "cardano", ada, p, "https://cardanoscan.io/address/$a", extra = extra, activity = o != null)
}

// ---------- Polkadot (Parity public Sidecar) ----------

private fun polkadot(a: String, p: Map<String, Double>): ChainResult {
    val j = JSONObject(http("https://polkadot-public-sidecar.parity-chains.parity.io/accounts/$a/balance-info"))
    val free = BigInteger(j.optString("free", "0").ifEmpty { "0" })
    val reserved = BigInteger(j.optString("reserved", "0").ifEmpty { "0" })
    val extra = if (reserved.signum() > 0) listOf("Reserved / staked" to "${fmt(amount(reserved, 10))} DOT") else emptyList()
    return build("Polkadot", "DOT", "polkadot", amount(free + reserved, 10), p, "https://polkadot.subscan.io/account/$a", extra = extra)
}

// ---------- Cosmos Hub (public LCD) ----------

private fun cosmos(a: String, p: Map<String, Double>): ChainResult {
    val arr = JSONObject(http("https://cosmos-rest.publicnode.com/cosmos/bank/v1beta1/balances/$a")).getJSONArray("balances")
    var uatom = BigInteger.ZERO
    for (i in 0 until arr.length()) {
        val b = arr.getJSONObject(i)
        if (b.getString("denom") == "uatom") uatom = BigInteger(b.getString("amount"))
    }
    return build("Cosmos Hub", "ATOM", "cosmos", amount(uatom, 6), p, "https://www.mintscan.io/cosmos/address/$a",
        activity = arr.length() > 0)
}

// ---------- Stellar (Horizon) ----------

private fun stellar(a: String, p: Map<String, Double>): ChainResult {
    val explorer = "https://stellar.expert/explorer/public/account/$a"
    val j = try {
        JSONObject(http("https://horizon.stellar.org/accounts/$a"))
    } catch (e: IOException) {
        if (e.message?.startsWith("HTTP 404") == true) {
            return build("Stellar", "XLM", "stellar", BigDecimal.ZERO, p, explorer,
                extra = listOf("Status" to "Not activated (no on-chain activity yet)"))
        }
        throw e
    }
    val balances = j.getJSONArray("balances")
    var xlm = BigDecimal.ZERO
    val stables = mutableListOf<Pair<String, BigDecimal>>()
    for (i in 0 until balances.length()) {
        val b = balances.getJSONObject(i)
        if (b.getString("asset_type") == "native") xlm = BigDecimal(b.getString("balance"))
        else if (b.optString("asset_code") in setOf("USDT", "USDC")) stables += "${b.getString("asset_code")} (Stellar)" to BigDecimal(b.getString("balance"))
    }
    return build("Stellar", "XLM", "stellar", xlm, p, explorer, stables, activity = true)
}

// ---------- Algorand (AlgoNode) ----------

private fun algorand(a: String, p: Map<String, Double>): ChainResult {
    val j = JSONObject(http("https://mainnet-api.algonode.cloud/v2/accounts/$a"))
    val assets = j.optJSONArray("assets") ?: JSONArray()
    fun asset(id: Long): BigDecimal {
        for (i in 0 until assets.length()) {
            val o = assets.getJSONObject(i)
            if (o.optLong("asset-id") == id) return amount(BigInteger.valueOf(o.optLong("amount")), 6)
        }
        return BigDecimal.ZERO
    }
    return build("Algorand", "ALGO", "algorand", amount(BigInteger.valueOf(j.optLong("amount")), 6), p,
        "https://allo.info/account/$a", listOf("USDT (Algorand)" to asset(312769), "USDC (Algorand)" to asset(31566704)))
}

// ---------- NEAR (FastNEAR public RPC) ----------

private fun near(a: String, p: Map<String, Double>): ChainResult {
    val rpc = "https://free.rpc.fastnear.com"
    fun query(params: JSONObject) = JSONObject(http(rpc,
        JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "query").put("params", params).toString()))
    val acc = query(JSONObject().put("request_type", "view_account").put("finality", "final").put("account_id", a))
    val explorer = "https://nearblocks.io/address/$a"
    val err = acc.optJSONObject("error")
    if (err != null || acc.optJSONObject("result") == null) {
        if (acc.toString().contains("does not exist") || acc.toString().contains("UNKNOWN_ACCOUNT")) {
            return build("NEAR", "NEAR", "near", BigDecimal.ZERO, p, explorer,
                extra = listOf("Status" to "Account doesn't exist yet"))
        }
        throw IOException(err?.optString("message") ?: "NEAR RPC error")
    }
    val nearBal = amount(BigInteger(acc.getJSONObject("result").getString("amount")), 24)
    val usdt = runCatching {
        val args = Base64.encodeToString(JSONObject().put("account_id", a).toString().toByteArray(), Base64.NO_WRAP)
        val r = query(JSONObject().put("request_type", "call_function").put("finality", "final")
            .put("account_id", "usdt.tether-token.near").put("method_name", "ft_balance_of").put("args_base64", args))
        val bytes = r.getJSONObject("result").getJSONArray("result")
        val text = String(ByteArray(bytes.length()) { bytes.getInt(it).toByte() }).trim('"')
        amount(BigInteger(text), 6)
    }.getOrDefault(BigDecimal.ZERO)
    return build("NEAR", "NEAR", "near", nearBal, p, explorer, listOf("USDT (NEAR)" to usdt), activity = true)
}

// ---------- Tezos (TzKT) ----------

private fun tezos(a: String, p: Map<String, Double>): ChainResult {
    val j = JSONObject(http("https://api.tzkt.io/v1/accounts/$a"))
    val usdt = runCatching {
        val arr = JSONArray(http("https://api.tzkt.io/v1/tokens/balances?account=$a&token.contract=KT1XnTn74bUtxHfDtBmm2bGZAQfhPbvKWR8o"))
        if (arr.length() > 0) amount(BigInteger(arr.getJSONObject(0).getString("balance")), 6) else BigDecimal.ZERO
    }.getOrDefault(BigDecimal.ZERO)
    val txs = j.optInt("numTransactions")
    return build("Tezos", "XTZ", "tezos", amount(BigInteger.valueOf(j.optLong("balance")), 6), p, "https://tzkt.io/$a",
        listOf("USDT (Tezos)" to usdt), listOf("Transactions" to txs.toString()), activity = txs > 0)
}

// ---------- EOS (Greymass public API) ----------

private fun eos(a: String, p: Map<String, Double>): ChainResult {
    val explorer = "https://bloks.io/account/$a"
    val j = try {
        JSONObject(http("https://eos.greymass.com/v1/chain/get_account", JSONObject().put("account_name", a).toString()))
    } catch (e: IOException) {
        if (e.message?.contains("unknown key") == true || e.message?.startsWith("HTTP 500") == true) {
            return ChainResult("EOS", listOf("Status" to "No EOS account with this name"), explorer, active = false)
        }
        throw e
    }
    val bal = BigDecimal(j.optString("core_liquid_balance", "0 EOS").substringBefore(' ').ifEmpty { "0" })
    val extra = j.optString("created").takeIf { it.isNotBlank() }?.let {
        listOf("Created" to date(Instant.parse(it + "Z").epochSecond))
    } ?: emptyList()
    return build("EOS", "EOS", "eos", bal, p, explorer, extra = extra, activity = true)
}

// ===================== Explorer links & sharing =====================

/** Block-explorer pages for an address, for the networks where it was found (or all of its networks). */
fun explorerLinks(address: String, results: List<ChainResult> = emptyList()): List<Pair<String, String>> {
    val uromResults = results.filter { it.active && it.explorerUrl != null }.map { it.network to it.explorerUrl!! }
    if (uromResults.isNotEmpty()) return uromResults
    return results.filter { it.explorerUrl != null }.take(3).map { it.network to it.explorerUrl!! }
}

/**
 * Address-poisoning guard: a different address that starts and ends like one of your saved wallets.
 * Scammers create such lookalikes so a copied address from history looks right at a glance.
 */
fun lookalike(address: String, saved: List<SavedAddress>): SavedAddress? {
    val a = rawAddress(address)
    if (a.length < 20 || saved.any { rawAddress(it.address).equals(a, ignoreCase = true) }) return null
    val skip = if (a.startsWith("0x")) 2 else 0
    return saved.firstOrNull { s ->
        val b = rawAddress(s.address)
        b.length == a.length &&
            b.drop(skip).take(4).equals(a.drop(skip).take(4), ignoreCase = true) &&
            b.takeLast(4).equals(a.takeLast(4), ignoreCase = true)
    }
}

// ===================== Prices =====================

/** Price keys (CoinGecko ids) mapped to their exchange trading pair. EOS now trades as Vaulta (A). */
private val PAIRS = mapOf(
    "ethereum" to "ETHUSDT", "binancecoin" to "BNBUSDT", "tron" to "TRXUSDT", "aptos" to "APTUSDT",
    "the-open-network" to "TONUSDT", "bitcoin" to "BTCUSDT", "bitcoin-cash" to "BCHUSDT", "litecoin" to "LTCUSDT",
    "dogecoin" to "DOGEUSDT", "solana" to "SOLUSDT", "ripple" to "XRPUSDT", "cardano" to "ADAUSDT",
    "polkadot" to "DOTUSDT", "cosmos" to "ATOMUSDT", "stellar" to "XLMUSDT", "algorand" to "ALGOUSDT",
    "near" to "NEARUSDT", "tezos" to "XTZUSDT", "vechain" to "VETUSDT", "ethereum-classic" to "ETCUSDT",
    "celo" to "CELOUSDT", "avalanche-2" to "AVAXUSDT", "polygon-ecosystem-token" to "POLUSDT",
    "sonic-3" to "SUSDT", "eos" to "AUSDT",
)

@Volatile private var priceCache: Pair<Long, Map<String, Double>>? = null

private fun prices(): Map<String, Double> {
    // Reuse prices for 30 s so several lookups in a row don't hit the exchanges again.
    priceCache?.let { (t, m) -> if (System.currentTimeMillis() - t < 30_000 && m.size >= PAIRS.size - 2) return m }
    val out = mutableMapOf<String, Double>()
    fun missing() = PAIRS.filterKeys { it !in out }
    val src = priceSource
    fun use(s: PriceSource) = (src == PriceSource.AUTO || src == s) && missing().isNotEmpty()

    // CoinMarketCap first, then the exchanges, then CoinGecko for anything still missing.
    if (use(PriceSource.COINMARKETCAP)) runCatching {
        cmcQuotes().forEach { (id, q) -> out[id] = q.first }
    }
    if (use(PriceSource.BINANCE)) runCatching {
        val arr = JSONArray(http("https://api.binance.com/api/v3/ticker/price"))
        val bySymbol = (0 until arr.length()).associate { arr.getJSONObject(it).let { o -> o.getString("symbol") to o.getString("price") } }
        missing().forEach { (id, pair) -> bySymbol[pair]?.toDoubleOrNull()?.let { out[id] = it } }
    }
    if (use(PriceSource.BYBIT)) runCatching {
        val list = JSONObject(http("https://api.bybit.com/v5/market/tickers?category=spot"))
            .getJSONObject("result").getJSONArray("list")
        val bySymbol = (0 until list.length()).associate { list.getJSONObject(it).let { o -> o.getString("symbol") to o.optString("lastPrice") } }
        missing().forEach { (id, pair) -> bySymbol[pair]?.toDoubleOrNull()?.let { out[id] = it } }
    }
    if (use(PriceSource.COINGECKO)) runCatching {
        val j = JSONObject(http("https://api.coingecko.com/api/v3/simple/price?vs_currencies=usd&ids=" + missing().keys.joinToString(",")))
        j.keys().forEach { out[it] = j.getJSONObject(it).optDouble("usd") }
    }
    if (out.isNotEmpty()) {
        priceCache = System.currentTimeMillis() to out.toMap()
        // Kept, so transfer values show right away next time the app opens (before prices are fetched again).
        runCatching { historyPrefs?.edit()?.putString("last_prices", JSONObject(out.toMap()).toString())?.apply() }
    }
    return out
}

/** The latest known coin prices (USD): this session's, else the ones saved last time. */
private fun knownPrices(): Map<String, Double> = priceCache?.second ?: runCatching {
    val j = JSONObject(historyPrefs?.getString("last_prices", null) ?: return emptyMap())
    j.keys().asSequence().associateWith { j.getDouble(it) }
}.getOrDefault(emptyMap())

/**
 * How long a payment without details may still be matched: coins (BNB, ETH, SOL…) only in the first minutes, so an
 * hour; stablecoins and TRX (searchable later) a day.
 */
fun detailsWindowMs(symbol: String): Long =
    if (symbol.uppercase().startsWith("USD") || symbol.equals("TRX", true)) 86_400_000L else 3_600_000L

/** A coin move worth less than a cent (fee change, the TON that comes back with a jetton transfer): not a payment. */
fun isCoinDust(amount: BigDecimal, symbol: String): Boolean =
    !symbol.uppercase().startsWith("USD") && (transferUsd(amount.abs(), symbol)?.let { it < BigDecimal("0.01") } ?: false)

/** A transfer's value in USD: stablecoins at face value, coins at the latest known price; null without a price. */
fun transferUsd(amount: BigDecimal, symbol: String): BigDecimal? {
    val sym = symbol.uppercase()
    if (sym.startsWith("USD") || sym == "DAI") return amount
    val price = knownPrices()[SYM_GECKO[sym] ?: return null] ?: return null
    return amount.multiply(BigDecimal.valueOf(price))
}

/**
 * How lists show a transfer: its value in your chosen currency ("3.05 USDT", "$3.05"), so every row reads the same.
 * The coin amount ("9.2636 TRX") is on the transaction's own page; it's only used here while no price is known.
 */
fun transferValue(amount: BigDecimal, symbol: String): String {
    val usd = transferUsd(amount, symbol)
        ?: return (if (symbol.uppercase().startsWith("USD")) fmt2(amount) else fmt(amount)) + " " + symbol
    // Tiny moves (fees, dust) read "< 0.01 USDT" rather than a misleading "0.00 USDT".
    val cent = BigDecimal("0.01").divide(BigDecimal.valueOf(fiatRate), 8, java.math.RoundingMode.HALF_UP)
    return if (usd.signum() > 0 && usd < cent) "< " + money(cent) else money(usd)
}

/** CoinMarketCap ids for every coin the app prices (keyed by the CoinGecko-style ids used elsewhere). */
private val CMC_IDS = mapOf(
    "bitcoin" to 1, "ethereum" to 1027, "binancecoin" to 1839, "tron" to 1958, "aptos" to 21794,
    "the-open-network" to 11419, "bitcoin-cash" to 1831, "litecoin" to 2, "dogecoin" to 74, "solana" to 5426,
    "ripple" to 52, "cardano" to 2010, "polkadot" to 6636, "cosmos" to 3794, "stellar" to 512, "algorand" to 4030,
    "near" to 6535, "tezos" to 2011, "vechain" to 3077, "ethereum-classic" to 1321, "celo" to 5567,
    "avalanche-2" to 5805, "polygon-ecosystem-token" to 28321, "sonic-3" to 32684, "eos" to 1765,
)

/** Price and 24h change per coin from CoinMarketCap's public data feed (no API key needed). */
private fun cmcQuotes(): Map<String, Pair<Double, Double>> {
    val byCmc = CMC_IDS.entries.associate { (k, v) -> v to k }
    val j = JSONObject(http("https://api.coinmarketcap.com/data-api/v3/cryptocurrency/quote/latest?id=" + CMC_IDS.values.joinToString(",")))
    val arr = j.getJSONArray("data")
    val out = mutableMapOf<String, Pair<Double, Double>>()
    for (i in 0 until arr.length()) {
        val o = arr.getJSONObject(i)
        val key = byCmc[o.getInt("id")] ?: continue
        val q = o.getJSONArray("quotes").getJSONObject(0)
        val price = q.optDouble("price")
        if (!price.isNaN()) out[key] = price to q.optDouble("percentChange24h", 0.0)
    }
    return out
}

/** Drop cached prices, e.g. after the price source changes. */
fun clearPriceCache() { priceCache = null; changeCache = null }

// ===================== Today's change =====================

private val SYM_GECKO = mapOf(
    "ETH" to "ethereum", "BNB" to "binancecoin", "POL" to "polygon-ecosystem-token", "AVAX" to "avalanche-2",
    "CELO" to "celo", "S" to "sonic-3", "ETC" to "ethereum-classic", "VET" to "vechain", "BTC" to "bitcoin",
    "BCH" to "bitcoin-cash", "LTC" to "litecoin", "DOGE" to "dogecoin", "TRX" to "tron", "SOL" to "solana",
    "TON" to "the-open-network", "XRP" to "ripple", "APT" to "aptos", "ADA" to "cardano", "DOT" to "polkadot",
    "ATOM" to "cosmos", "XLM" to "stellar", "ALGO" to "algorand", "NEAR" to "near", "XTZ" to "tezos", "EOS" to "eos",
)

data class DayChange(val usd: BigDecimal, val percent: Double)

@Volatile private var changeCache: Pair<Long, Map<String, Double>>? = null

/** 24-hour price change in percent per coin (from the chosen source; Binance, then CoinGecko otherwise). */
private fun changes24h(): Map<String, Double> {
    changeCache?.let { (t, m) -> if (System.currentTimeMillis() - t < 120_000) return m }
    val out = mutableMapOf<String, Double>()
    if (priceSource == PriceSource.COINMARKETCAP || priceSource == PriceSource.AUTO) runCatching {
        cmcQuotes().forEach { (id, q) -> out[id] = q.second }
    }
    if (out.size < PAIRS.size) runCatching {
        val symbols = JSONArray(PAIRS.values.toList()).toString()
        val arr = JSONArray(http("https://api.binance.com/api/v3/ticker/24hr?symbols=" + URLEncoder.encode(symbols, "UTF-8")))
        val bySymbol = (0 until arr.length()).associate { arr.getJSONObject(it).let { o -> o.getString("symbol") to o.optString("priceChangePercent") } }
        PAIRS.forEach { (id, pair) -> bySymbol[pair]?.toDoubleOrNull()?.let { out[id] = it } }
    }
    if (out.size < PAIRS.size) runCatching {
        val missing = PAIRS.keys.filter { it !in out }
        val j = JSONObject(http("https://api.coingecko.com/api/v3/simple/price?vs_currencies=usd&include_24hr_change=true&ids=" + missing.joinToString(",")))
        j.keys().forEach { k -> j.getJSONObject(k).optDouble("usd_24h_change").takeIf { !it.isNaN() }?.let { out[k] = it } }
    }
    if (out.isNotEmpty()) changeCache = System.currentTimeMillis() to out.toMap()
    return out
}

/** How much the total of [results] moved over the last 24 hours. Stablecoins count as unchanged. */
suspend fun dayChange(results: List<ChainResult>): DayChange? = withContext(Dispatchers.IO) {
    val pct = changes24h()
    if (pct.isEmpty()) return@withContext null
    val p = prices()
    var now = BigDecimal.ZERO
    var delta = BigDecimal.ZERO
    for (r in results) {
        if (r.error != null) continue
        now += r.usd ?: continue
        val g = SYM_GECKO[r.nativeSym] ?: continue
        val price = p[g] ?: continue
        val c = pct[g] ?: continue
        val nativeNow = r.native.multiply(BigDecimal.valueOf(price))
        delta += nativeNow.multiply(BigDecimal.valueOf(c / (100 + c)))
    }
    val before = now - delta
    if (now.signum() <= 0 || before.signum() <= 0) null
    else DayChange(delta, delta.toDouble() / before.toDouble() * 100)
}

// ===================== Helpers =====================

internal fun http(url: String, body: String? = null, headers: Map<String, String> = emptyMap()): String {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 15_000
    c.readTimeout = 20_000
    c.setRequestProperty("Accept", "application/json")
    c.setRequestProperty("User-Agent", "Batwallet/1.0")
    headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
    if (body != null) {
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toByteArray()) }
    }
    try {
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            throw IOException(if (code == 429) "Rate limited by the API, try again in a minute" else "HTTP $code ${text.take(150)}")
        }
        return text
    } finally {
        c.disconnect()
    }
}

internal fun amount(units: BigInteger, decimals: Int): BigDecimal = BigDecimal(units, decimals)

/** Coin amounts: 4 decimals from 1 upwards, up to 6 for small amounts (so 0.0017 BNB doesn't show as 0). */
fun fmt(v: BigDecimal): String {
    val decimals = if (v.abs() >= BigDecimal.ONE) 4 else 6
    val scaled = v.setScale(minOf(v.scale().coerceAtLeast(0), decimals), RoundingMode.DOWN).stripTrailingZeros()
    val nu = NumberFormat.getNumberInstance(Locale.US).apply { maximumFractionDigits = decimals }
    return nu.format(scaled)
}

/** Stablecoin amounts: always exactly 2 decimals, e.g. 15.71. */
fun fmt2(v: BigDecimal): String =
    NumberFormat.getNumberInstance(Locale.US).apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }
        .format(v.setScale(2, RoundingMode.HALF_UP))

/** Stablecoins (worth $1) plus the native coin at its price; null when the price is unknown. */
private fun worth(stable: BigDecimal, native: BigDecimal, price: Double?): BigDecimal? = when {
    price != null && !price.isNaN() -> stable + native.multiply(BigDecimal.valueOf(price))
    native.signum() == 0 -> stable
    else -> null
}

// ===================== Display currency =====================

/** Currencies the app can show values in. Prices are fetched in USD and converted. */
enum class Fiat(val code: String, val locale: Locale, val label: String) {
    /** The main one: values shown as USDT (stablecoins count 1:1, coins at their USDT price). */
    USDT("USDT", Locale.US, "Tether (USDT)"),
    USD("USD", Locale.US, "US dollar ($)"),
    INR("INR", Locale("en", "IN"), "Indian rupee (₹)"),
    EUR("EUR", Locale("en", "IE"), "Euro (€)"),
    GBP("GBP", Locale.UK, "British pound (£)"),
    AED("AED", Locale("en", "AE"), "UAE dirham (AED)"),
    AZN("AZN", Locale("az", "AZ"), "Azerbaijani manat (₼)"),
    RUB("RUB", Locale("ru", "RU"), "Russian ruble (₽)"),
}

// Compose state, so every amount on screen updates when the currency changes.
var fiat: Fiat by androidx.compose.runtime.mutableStateOf(Fiat.USD)
var fiatRate: Double by androidx.compose.runtime.mutableStateOf(1.0)

/** USD → [code] exchange rate (open.er-api.com, free, no key). */
fun fetchFiatRate(code: String): Double? = runCatching {
    if (code == "USD" || code == "USDT") 1.0
    else JSONObject(http("https://open.er-api.com/v6/latest/USD")).getJSONObject("rates").getDouble(code)
}.getOrNull()

private fun fiatFormat(usd: BigDecimal): String {
    val f = fiat
    // USDT isn't an official currency code: shown as "1,234.56 USDT".
    if (f == Fiat.USDT) return fmt2(usd) + " USDT"
    val nf = NumberFormat.getCurrencyInstance(f.locale).apply { currency = java.util.Currency.getInstance(f.code) }
    return nf.format(usd.multiply(BigDecimal.valueOf(fiatRate)))
}

fun money(v: BigDecimal?): String = if (v == null) "price unavailable" else fiatFormat(v)

private fun usd(v: BigDecimal, price: Double?): String {
    if (price == null || price.isNaN()) return "price unavailable"
    return fiatFormat(v.multiply(BigDecimal.valueOf(price)))
}

private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.US).withZone(ZoneId.systemDefault())
fun date(epochSeconds: Long): String = DATE.format(Instant.ofEpochSecond(epochSeconds))

private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.US).withZone(ZoneId.systemDefault())
private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.US).withZone(ZoneId.systemDefault())
private val DAY_YEAR = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US).withZone(ZoneId.systemDefault())

/** Short date for lists: "Today 23:19", "Yesterday 09:12", "26 Oct" (or "26 Oct 2025" for other years). */
fun shortDate(epochSeconds: Long): String {
    val zone = ZoneId.systemDefault()
    val instant = Instant.ofEpochSecond(epochSeconds)
    val day = instant.atZone(zone).toLocalDate()
    val today = java.time.LocalDate.now(zone)
    return when {
        day == today -> tr("Today") + " " + TIME.format(instant)
        day == today.minusDays(1) -> tr("Yesterday") + " " + TIME.format(instant)
        day.year == today.year -> DAY.format(instant)
        else -> DAY_YEAR.format(instant)
    }
}

/** Saved wallets and contacts, used to flag lookalike addresses in history (kept current by the app). */
@Volatile var lookalikeSource: List<SavedAddress> = emptyList()

/** A transfer address that imitates one of yours (same start and end, different middle). */
fun lookalikeOf(address: String): SavedAddress? = lookalike(address, lookalikeSource)

// ===================== Smart paste / text scan =====================

/** Network named in a message ("USDT BEP20", "send on Polygon"), used to pick the network for a 0x address. */
fun networkHint(text: String): String? {
    val t = " " + text.lowercase().replace(Regex("[^a-z0-9]+"), " ") + " "
    return when {
        Regex(" (bep20|bep 20|bsc|bnb smart chain|binance smart chain|bnb chain) ").containsMatchIn(t) -> "BSC"
        Regex(" (erc20|erc 20|ethereum|eth mainnet) ").containsMatchIn(t) -> "Ethereum"
        Regex(" (polygon|matic|pol) ").containsMatchIn(t) -> "Polygon"
        Regex(" (arbitrum|arb) ").containsMatchIn(t) -> "Arbitrum"
        Regex(" (optimism|op mainnet) ").containsMatchIn(t) -> "Optimism"
        Regex(" (avalanche|avax|avax c) ").containsMatchIn(t) -> "Avalanche"
        Regex(" base (network|chain|mainnet) |\\bon base\\b").containsMatchIn(t) -> "Base"
        else -> null
    }
}

/** Common camera mix-ups inside hex addresses (O → 0, l / I → 1); base58 addresses are left alone. */
private fun fixHex(token: String): String =
    if (token.startsWith("0x") && (token.length == 42 || token.length == 66)) "0x" + token.drop(2).replace('O', '0').replace('o', '0')
        .replace('l', '1').replace('I', '1') else token

/**
 * Wallet addresses found anywhere in a piece of text (a chat message, a web page, text read from a photo).
 * A 0x address gets the network the text names, if any. Words that only look like addresses on networks with
 * free-form names (EOS accounts, NEAR names) are ignored here, so normal words never count.
 */
fun extractAddresses(text: String): List<String> {
    if (text.isBlank()) return emptyList()
    val hint = networkHint(text)
    // Addresses are sometimes split over two lines (in photos and narrow chats): try the text joined up too.
    val variants = listOf(text, text.replace(Regex("\\s*\\n\\s*"), ""))
    val found = LinkedHashSet<String>()
    for (v in variants) {
        for (raw in v.split(Regex("[\\s,;()\\[\\]{}<>\"'`|]+"))) {
            val token = fixHex(cleanAddress(raw.trim().trimEnd('.', ':', '!', '?')))
            val type = detect(token) ?: continue
            if (type == AddrType.EOS || type == AddrType.LIGHTNING) continue
            if (type == AddrType.NEAR && !token.contains('.')) continue // 64-hex is far more often a transaction ID
            if (type == AddrType.SOLANA && token.length < 32) continue
            found += if (type == AddrType.EVM && hint != null) withNet(token, hint) else token
        }
    }
    // Same address with and without a network: keep the one with the network.
    return found.filter { a -> netOf(a) != null || found.none { it != a && rawAddress(it).equals(a, true) } }.distinct()
}

/** Looks like a transaction ID rather than an address: 64 hex (Tron, Bitcoin…), 0x + 64 hex (EVM, Aptos) or a Solana signature. */
fun looksLikeTxHash(s: String): Boolean {
    val t = s.trim()
    return Regex("^(0x)?[0-9a-fA-F]{64}$").matches(t) || Regex("^[1-9A-HJ-NP-Za-km-z]{86,90}$").matches(t)
}