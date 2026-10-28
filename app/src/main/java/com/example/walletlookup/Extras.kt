package com.example.walletlookup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger
import java.net.URLEncoder
import java.time.Instant

private val MAX: Int get() = historyLimit

// ===================== Coin history (native coins) =====================

/** History of a network's own coin for the non-EVM networks that have a free source. */
internal fun coinHistory(type: AddrType, a: String): List<History>? = when (type) {
    AddrType.BITCOIN -> buildList {
        add(safeCoin("Bitcoin") { esploraHistory("Bitcoin", "https://mempool.space/api", "BTC", "https://mempool.space/tx/", a) })
        if (!a.lowercase().startsWith("bc1")) add(safeCoin("Bitcoin Cash") { bchHistory(a) })
    }
    AddrType.LITECOIN -> listOf(safeCoin("Litecoin") {
        esploraHistory("Litecoin", "https://litecoinspace.org/api", "LTC", "https://litecoinspace.org/tx/", a)
    })
    AddrType.BITCOIN_CASH -> listOf(safeCoin("Bitcoin Cash") { bchHistory(a) })
    AddrType.DOGECOIN -> listOf(safeCoin("Dogecoin") { dogeHistory(a) })
    AddrType.XRP -> listOf(safeCoin("XRP Ledger") { xrpHistory(a) })
    AddrType.STELLAR -> listOf(safeCoin("Stellar") { stellarHistory(a) })
    AddrType.ALGORAND -> listOf(safeCoin("Algorand") { algorandHistory(a) })
    AddrType.TEZOS -> listOf(safeCoin("Tezos") { tezosHistory(a) })
    AddrType.SOLANA -> listOf(safeCoin("Solana") { solanaHistory(a) })
    else -> null
}

private inline fun safeCoin(network: String, block: () -> History): History =
    try { block() } catch (e: Exception) { History(network, error = friendlyError(e)) }

private fun esploraHistory(name: String, base: String, sym: String, txBase: String, raw: String): History {
    val a = if (raw.startsWith("bc1", true) || raw.startsWith("ltc1", true)) raw.lowercase() else raw
    val arr = JSONArray(http("$base/address/$a/txs"))
    val items = (0 until minOf(arr.length(), MAX)).mapNotNull { i ->
        val tx = arr.getJSONObject(i)
        var net = 0L
        var other: String? = null
        val vin = tx.getJSONArray("vin")
        for (k in 0 until vin.length()) {
            val prev = vin.getJSONObject(k).optJSONObject("prevout") ?: continue
            val addr = prev.optString("scriptpubkey_address")
            if (addr == a) net -= prev.optLong("value") else if (other == null) other = addr
        }
        val vout = tx.getJSONArray("vout")
        var firstOut: String? = null
        for (k in 0 until vout.length()) {
            val o = vout.getJSONObject(k)
            val addr = o.optString("scriptpubkey_address")
            if (addr == a) net += o.optLong("value") else if (firstOut == null && addr.isNotEmpty()) firstOut = addr
        }
        if (net == 0L) return@mapNotNull null
        val incoming = net > 0
        val time = tx.optJSONObject("status")?.optLong("block_time")?.takeIf { it > 0 } ?: (System.currentTimeMillis() / 1000)
        val txid = tx.getString("txid")
        val party = if (incoming) other else firstOut
        Transfer(incoming, amount(BigInteger.valueOf(kotlin.math.abs(net)), 8), party, time * 1000, txBase + txid, txid,
            if (incoming) party else a, if (incoming) a else party, sym)
    }
    return History(name, items)
}

private fun bchHistory(a: String): History {
    val arr = JSONArray(http("https://api.haskoin.com/bch/address/${URLEncoder.encode(a, "UTF-8")}/transactions/full?limit=$MAX"))
    // Haskoin answers with cashaddr addresses; compare without the prefix.
    fun same(x: String) = x.removePrefix("bitcoincash:")
    var me: String? = null
    val items = (0 until arr.length()).mapNotNull { i ->
        val tx = arr.getJSONObject(i)
        var net = 0L
        var from: String? = null
        var to: String? = null
        val ins = tx.getJSONArray("inputs")
        val outs = tx.getJSONArray("outputs")
        // Work out our cashaddr from the first match the API reports for this legacy/cash address.
        if (me == null) {
            val all = (0 until ins.length()).map { ins.getJSONObject(it).optString("address") } +
                (0 until outs.length()).map { outs.getJSONObject(it).optString("address") }
            me = all.firstOrNull { same(it) == same(a) } ?: all.firstOrNull { a.startsWith("1") || a.startsWith("3") }
        }
        for (k in 0 until ins.length()) {
            val o = ins.getJSONObject(k)
            if (same(o.optString("address")) == same(me ?: a)) net -= o.optLong("value") else if (from == null) from = o.optString("address")
        }
        for (k in 0 until outs.length()) {
            val o = outs.getJSONObject(k)
            if (same(o.optString("address")) == same(me ?: a)) net += o.optLong("value") else if (to == null) to = o.optString("address")
        }
        if (net == 0L) return@mapNotNull null
        val incoming = net > 0
        val txid = tx.getString("txid")
        Transfer(incoming, amount(BigInteger.valueOf(kotlin.math.abs(net)), 8), if (incoming) from else to,
            tx.optLong("time") * 1000, "https://blockchair.com/bitcoin-cash/transaction/$txid", txid,
            if (incoming) from else a, if (incoming) a else to, "BCH")
    }
    return History("Bitcoin Cash", items)
}

private fun dogeHistory(a: String): History {
    val j = JSONObject(http("https://api.blockcypher.com/v1/doge/main/addrs/$a?limit=${minOf(MAX * 2, 200)}"))
    val refs = j.optJSONArray("txrefs") ?: JSONArray()
    val byTx = linkedMapOf<String, Pair<Long, Long>>() // hash -> (net, time)
    for (i in 0 until refs.length()) {
        val r = refs.getJSONObject(i)
        val hash = r.getString("tx_hash")
        val v = r.optLong("value") * (if (r.optInt("tx_input_n", -1) >= 0) -1 else 1)
        val t = runCatching { Instant.parse(r.optString("confirmed")).epochSecond }.getOrDefault(0L)
        val prev = byTx[hash]
        byTx[hash] = (prev?.first ?: 0L) + v to t
    }
    val items = byTx.entries.take(MAX).mapNotNull { (hash, p) ->
        if (p.first == 0L) null
        else Transfer(p.first > 0, amount(BigInteger.valueOf(kotlin.math.abs(p.first)), 8), null, p.second * 1000,
            "https://blockchair.com/dogecoin/transaction/$hash", hash, null, null, "DOGE")
    }
    return History("Dogecoin", items)
}

private fun xrpHistory(a: String): History {
    val body = JSONObject().put("method", "account_tx")
        .put("params", JSONArray().put(JSONObject().put("account", a).put("limit", minOf(MAX * 2, 400))))
    val txs = JSONObject(http("https://xrplcluster.com/", body.toString())).getJSONObject("result").getJSONArray("transactions")
    val items = (0 until txs.length()).mapNotNull { i ->
        val o = txs.getJSONObject(i)
        val tx = o.optJSONObject("tx") ?: o.optJSONObject("tx_json") ?: return@mapNotNull null
        if (tx.optString("TransactionType") != "Payment") return@mapNotNull null
        val amt = tx.opt("Amount") as? String ?: return@mapNotNull null // only XRP itself, not issued tokens
        val from = tx.optString("Account")
        val to = tx.optString("Destination")
        val incoming = to == a
        val hash = tx.optString("hash", o.optString("hash"))
        Transfer(incoming, amount(BigInteger(amt), 6), if (incoming) from else to,
            (tx.optLong("date") + 946_684_800L) * 1000, "https://livenet.xrpl.org/transactions/$hash", hash, from, to, "XRP")
    }.take(MAX)
    return History("XRP Ledger", items)
}

private fun stellarHistory(a: String): History {
    val recs = JSONObject(http("https://horizon.stellar.org/accounts/$a/payments?order=desc&limit=${minOf(MAX * 2, 200)}"))
        .getJSONObject("_embedded").getJSONArray("records")
    val items = (0 until recs.length()).mapNotNull { i ->
        val r = recs.getJSONObject(i)
        val (from, to, amt, sym) = when (r.optString("type")) {
            "payment" -> listOf(r.optString("from"), r.optString("to"), r.optString("amount"),
                if (r.optString("asset_type") == "native") "XLM" else r.optString("asset_code"))
            "create_account" -> listOf(r.optString("funder"), r.optString("account"), r.optString("starting_balance"), "XLM")
            else -> return@mapNotNull null
        }
        if (sym !in setOf("XLM", "USDT", "USDC")) return@mapNotNull null
        val incoming = to == a
        val hash = r.optString("transaction_hash")
        Transfer(incoming, BigDecimal(amt), if (incoming) from else to, Instant.parse(r.getString("created_at")).toEpochMilli(),
            "https://stellar.expert/explorer/public/tx/$hash", hash, from, to, sym)
    }.take(MAX)
    return History("Stellar", items)
}

private fun algorandHistory(a: String): History {
    val txs = JSONObject(http("https://mainnet-idx.algonode.cloud/v2/accounts/$a/transactions?limit=${minOf(MAX * 2, 1000)}")).getJSONArray("transactions")
    val items = (0 until txs.length()).mapNotNull { i ->
        val t = txs.getJSONObject(i)
        val sender = t.optString("sender")
        val (to, units, sym) = when (t.optString("tx-type")) {
            "pay" -> t.getJSONObject("payment-transaction").let { Triple(it.optString("receiver"), it.optLong("amount"), "ALGO") }
            "axfer" -> t.getJSONObject("asset-transfer-transaction").let {
                val sym = when (it.optLong("asset-id")) { 312769L -> "USDT"; 31566704L -> "USDC"; else -> return@mapNotNull null }
                Triple(it.optString("receiver"), it.optLong("amount"), sym)
            }
            else -> return@mapNotNull null
        }
        if (units == 0L) return@mapNotNull null
        val incoming = to == a
        val id = t.getString("id")
        Transfer(incoming, amount(BigInteger.valueOf(units), 6), if (incoming) sender else to, t.optLong("round-time") * 1000,
            "https://allo.info/tx/$id", id, sender, to, sym)
    }.take(MAX)
    return History("Algorand", items)
}

private fun tezosHistory(a: String): History {
    val arr = JSONArray(http("https://api.tzkt.io/v1/accounts/$a/operations?type=transaction&limit=$MAX&sort.desc=id"))
    val items = (0 until arr.length()).mapNotNull { i ->
        val o = arr.getJSONObject(i)
        val mutez = o.optLong("amount")
        if (mutez == 0L || o.optString("status") != "applied") return@mapNotNull null
        val from = o.optJSONObject("sender")?.optString("address")
        val to = o.optJSONObject("target")?.optString("address")
        val incoming = to == a
        val hash = o.optString("hash")
        Transfer(incoming, amount(BigInteger.valueOf(mutez), 6), if (incoming) from else to,
            Instant.parse(o.getString("timestamp")).toEpochMilli(), "https://tzkt.io/$hash", hash, from, to, "XTZ")
    }
    return History("Tezos", items)
}

/** Solana: SOL balance change of this address in each of its latest transactions. */
private fun solanaHistory(a: String): History {
    // One request at a time: Solana's free RPC rejects batches.
    val sigs = solanaCall("getSignaturesForAddress", JSONArray().put(a).put(JSONObject().put("limit", minOf(MAX, 20))))
        .getJSONArray("result")
    val items = (0 until sigs.length()).mapNotNull { i ->
        val sig = sigs.getJSONObject(i).getString("signature")
        val r = runCatching {
            solanaCall("getTransaction", JSONArray().put(sig)
                .put(JSONObject().put("encoding", "json").put("maxSupportedTransactionVersion", 0))).optJSONObject("result")
        }.getOrNull() ?: return@mapNotNull null
        val meta = r.optJSONObject("meta") ?: return@mapNotNull null
        if (meta.opt("err") != null && meta.opt("err") != JSONObject.NULL) return@mapNotNull null
        val keys = r.getJSONObject("transaction").getJSONObject("message").getJSONArray("accountKeys")
        val all = (0 until keys.length()).map { keys.getString(it) }.toMutableList()
        meta.optJSONObject("loadedAddresses")?.let { la ->
            la.optJSONArray("writable")?.let { w -> for (k in 0 until w.length()) all += w.getString(k) }
            la.optJSONArray("readonly")?.let { ro -> for (k in 0 until ro.length()) all += ro.getString(k) }
        }
        val idx = all.indexOf(a).takeIf { it >= 0 } ?: return@mapNotNull null
        val delta = meta.getJSONArray("postBalances").getLong(idx) - meta.getJSONArray("preBalances").getLong(idx)
        if (delta == 0L) return@mapNotNull null
        Transfer(delta > 0, amount(BigInteger.valueOf(kotlin.math.abs(delta)), 9), null, r.optLong("blockTime") * 1000,
            "https://solscan.io/tx/$sig", sig, null, null, "SOL")
    }
    return History("Solana", items, note = "Shows SOL balance changes; token transfers aren't listed yet.")
}
/** Native-coin transfers on an EVM network, from whichever free source covers it. */
internal fun evmNativeTransfers(name: String, chainId: Long, blockscout: String?, routescan: Boolean, txBase: String, a: String): List<Transfer> {
    val sym = mapOf("BSC" to "BNB", "Polygon" to "POL", "Avalanche" to "AVAX", "Celo" to "CELO", "Sonic" to "S")[name] ?: "ETH"
    fun etherscanStyle(url: String): List<Transfer> {
        val j = JSONObject(http(url))
        if (j.optString("status") != "1") return emptyList()
        val arr = j.getJSONArray("result")
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            if (o.optString("isError") == "1") return@mapNotNull null
            val v = BigInteger(o.optString("value", "0").ifEmpty { "0" })
            if (v.signum() == 0) return@mapNotNull null
            val from = o.getString("from")
            val to = o.optString("to")
            val incoming = to.equals(a, true)
            Transfer(incoming, amount(v, 18), if (incoming) from else to, o.getString("timeStamp").toLong() * 1000,
                txBase + o.getString("hash"), o.getString("hash"), from, to, sym)
        }
    }
    return when {
        etherscanKey.isNotBlank() -> etherscanStyle("https://api.etherscan.io/v2/api?chainid=$chainId&module=account&action=txlist" +
            "&address=$a&page=1&offset=$MAX&sort=desc&apikey=${URLEncoder.encode(etherscanKey, "UTF-8")}")
        routescan -> etherscanStyle("https://api.routescan.io/v2/network/mainnet/evm/$chainId/etherscan/api?module=account" +
            "&action=txlist&address=$a&page=1&offset=$MAX&sort=desc")
        blockscout != null -> {
            val arr = JSONObject(http("https://$blockscout/api/v2/addresses/$a/transactions")).getJSONArray("items")
            (0 until minOf(arr.length(), MAX)).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                if (o.optString("status") != "ok") return@mapNotNull null
                val v = BigInteger(o.optString("value", "0").ifEmpty { "0" })
                if (v.signum() == 0) return@mapNotNull null
                val from = o.getJSONObject("from").getString("hash")
                val to = o.optJSONObject("to")?.optString("hash").orEmpty()
                val incoming = to.equals(a, true)
                Transfer(incoming, amount(v, 18), if (incoming) from else to, Instant.parse(o.getString("timestamp")).toEpochMilli(),
                    txBase + o.getString("hash"), o.getString("hash"), from, to, sym)
            }
        }
        else -> emptyList()
    }
}

// ===================== Other tokens =====================

data class TokenBalance(val network: String, val symbol: String, val amount: BigDecimal, val usd: BigDecimal?)

private val SHOWN_ALREADY = setOf("USDT", "USD₮", "USDt", "USDC", "APT")

/**
 * Recognisable tokens beyond USDT/USDC and the native coin, for the networks where the wallet is active.
 * Only tokens with a known price or a verified listing are shown, so spam airdrops stay hidden.
 */
suspend fun otherTokens(raw: String, results: List<ChainResult>): List<TokenBalance> = withContext(Dispatchers.IO) {
    val a = rawAddress(raw)
    val active = results.filter { it.active && it.error == null }.map { it.network }.toSet()
    val jobs = buildList {
        when (detect(a)) {
            AddrType.EVM -> if ("Ethereum" in active) add(async { runCatching { ethTokens(a) }.getOrDefault(emptyList()) })
            AddrType.TON -> add(async { runCatching { tonTokens(a) }.getOrDefault(emptyList()) })
            AddrType.APTOS -> add(async { runCatching { aptosTokens(a) }.getOrDefault(emptyList()) })
            AddrType.TEZOS -> add(async { runCatching { tezosTokens(a) }.getOrDefault(emptyList()) })
            AddrType.STELLAR -> add(async { runCatching { stellarTokens(a) }.getOrDefault(emptyList()) })
            else -> {}
        }
    }
    jobs.awaitAll().flatten().sortedByDescending { it.usd ?: BigDecimal.ZERO }
}

private fun ethTokens(a: String): List<TokenBalance> {
    val j = JSONObject(http("https://api.ethplorer.io/getAddressInfo/$a?apiKey=freekey"))
    val arr = j.optJSONArray("tokens") ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        val t = arr.getJSONObject(i)
        val info = t.getJSONObject("tokenInfo")
        val sym = info.optString("symbol").trim()
        val rate = info.optJSONObject("price")?.optDouble("rate")?.takeIf { !it.isNaN() } ?: return@mapNotNull null
        if (sym.isEmpty() || sym in SHOWN_ALREADY) return@mapNotNull null
        val dec = info.optString("decimals", "0").toIntOrNull() ?: 0
        val amt = amount(BigInteger(t.optString("rawBalance", "0").ifEmpty { "0" }), dec)
        val usd = amt.multiply(BigDecimal.valueOf(rate))
        if (usd < BigDecimal.ONE) null else TokenBalance("Ethereum", sym, amt, usd)
    }
}

private fun tonTokens(a: String): List<TokenBalance> {
    val arr = JSONObject(http("https://tonapi.io/v2/accounts/${URLEncoder.encode(a, "UTF-8")}/jettons?currencies=usd"))
        .getJSONArray("balances")
    return (0 until arr.length()).mapNotNull { i ->
        val b = arr.getJSONObject(i)
        val j = b.getJSONObject("jetton")
        val sym = j.optString("symbol").trim()
        if (j.optString("verification") != "whitelist" || sym.isEmpty() || sym in SHOWN_ALREADY) return@mapNotNull null
        val amt = amount(BigInteger(b.optString("balance", "0").ifEmpty { "0" }), j.optInt("decimals", 9))
        if (amt.signum() == 0) return@mapNotNull null
        val price = b.optJSONObject("price")?.optJSONObject("prices")?.optDouble("USD")?.takeIf { !it.isNaN() }
        TokenBalance("TON", sym, amt, price?.let { amt.multiply(BigDecimal.valueOf(it)) })
    }
}

private fun aptosTokens(raw: String): List<TokenBalance> {
    val a = "0x" + raw.removePrefix("0x").lowercase().padStart(64, '0')
    val q = """query { current_fungible_asset_balances(where: { owner_address: { _eq: "$a" }, amount: { _gt: "0" } }) { amount metadata { symbol decimals } } }"""
    val arr = JSONObject(http("https://api.mainnet.aptoslabs.com/v1/graphql", JSONObject().put("query", q).toString()))
        .getJSONObject("data").getJSONArray("current_fungible_asset_balances")
    return (0 until arr.length()).mapNotNull { i ->
        val o = arr.getJSONObject(i)
        val m = o.optJSONObject("metadata") ?: return@mapNotNull null
        val sym = m.optString("symbol").trim()
        if (sym.isEmpty() || sym in SHOWN_ALREADY) return@mapNotNull null
        TokenBalance("Aptos", sym, amount(BigInteger(o.get("amount").toString()), m.optInt("decimals", 8)), null)
    }
}

private fun tezosTokens(a: String): List<TokenBalance> {
    val arr = JSONArray(http("https://api.tzkt.io/v1/tokens/balances?account=$a&balance.gt=0&limit=50"))
    return (0 until arr.length()).mapNotNull { i ->
        val o = arr.getJSONObject(i)
        val meta = o.optJSONObject("token")?.optJSONObject("metadata") ?: return@mapNotNull null
        val sym = meta.optString("symbol").trim()
        val dec = meta.optString("decimals", "0").toIntOrNull() ?: 0
        if (sym.isEmpty() || dec == 0 || sym in SHOWN_ALREADY) return@mapNotNull null // decimals 0 = NFTs
        TokenBalance("Tezos", sym, amount(BigInteger(o.getString("balance")), dec), null)
    }
}

private fun stellarTokens(a: String): List<TokenBalance> {
    val balances = JSONObject(http("https://horizon.stellar.org/accounts/$a")).getJSONArray("balances")
    return (0 until balances.length()).mapNotNull { i ->
        val b = balances.getJSONObject(i)
        val code = b.optString("asset_code")
        if (b.optString("asset_type") == "native" || code.isEmpty() || code in SHOWN_ALREADY) return@mapNotNull null
        val amt = BigDecimal(b.getString("balance"))
        if (amt.signum() == 0) null else TokenBalance("Stellar", code, amt, null)
    }
}
