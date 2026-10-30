package com.example.walletlookup

import kotlinx.coroutines.CancellationException
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

/**
 * History of a network's own coin for the non-EVM networks that have a free source. [networks]: for a Bitcoin
 * address, which of Bitcoin and Bitcoin Cash to load (null = both).
 */
internal suspend fun coinHistory(type: AddrType, a: String, networks: Set<String>? = null): List<History>? = when (type) {
    AddrType.BITCOIN -> coroutineScope {
        fun wanted(network: String) = networks == null || network in networks
        listOfNotNull(
            if (wanted("Bitcoin")) async(Dispatchers.IO) {
                safeCoin("Bitcoin") { esploraHistory("Bitcoin", "https://mempool.space/api", "BTC", "https://mempool.space/tx/", a) }
            } else null,
            // Legacy 1…/3… addresses also exist on Bitcoin Cash. Both load at once.
            if (!a.lowercase().startsWith("bc1") && wanted("Bitcoin Cash")) async(Dispatchers.IO) {
                safeCoin("Bitcoin Cash") { bchHistory(a) }
            } else null,
        ).awaitAll()
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
    try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { History(network, error = friendlyError(e)) }

/** Esplora (mempool.space, litecoinspace.org) lists the latest 25 confirmed transactions, whatever the limit. */
private const val ESPLORA_PAGE = 25

private fun esploraHistory(name: String, base: String, sym: String, txBase: String, raw: String): History {
    val a = if (raw.startsWith("bc1", true) || raw.startsWith("ltc1", true)) raw.lowercase() else raw
    val arr = JSONArray(http("$base/address/$a/txs"))
    var confirmed = 0
    var oldest = 0L
    val items = (0 until arr.length()).mapNotNull { i ->
        val tx = arr.getJSONObject(i)
        val blockTime = tx.optJSONObject("status")?.optLong("block_time")?.takeIf { it > 0 }
        if (blockTime != null) {
            confirmed++
            oldest = if (oldest == 0L) blockTime * 1000 else minOf(oldest, blockTime * 1000)
        }
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
        val time = blockTime ?: (System.currentTimeMillis() / 1000)
        val txid = tx.getString("txid")
        val party = if (incoming) other else firstOut
        Transfer(incoming, amount(BigInteger.valueOf(kotlin.math.abs(net)), 8), party, time * 1000, txBase + txid, txid,
            if (incoming) party else a, if (incoming) a else party, sym)
    }
    return historyOf(name, listOf(sourcePage(items, confirmed, ESPLORA_PAGE, oldest, ESPLORA_PAGE)))
}

private fun bchHistory(a: String): History {
    val arr = JSONArray(http("https://api.haskoin.com/bch/address/${URLEncoder.encode(a, "UTF-8")}/transactions/full?limit=$MAX"))
    // Haskoin answers with cashaddr addresses: compare on this address's own cashaddr (a legacy one converted).
    val me = bchKey(a)
    var oldest = 0L
    val items = (0 until arr.length()).mapNotNull { i ->
        val tx = arr.getJSONObject(i)
        tx.optLong("time").takeIf { it > 0 }?.let { oldest = if (oldest == 0L) it * 1000 else minOf(oldest, it * 1000) }
        var net = 0L
        var from: String? = null
        var to: String? = null
        val ins = tx.getJSONArray("inputs")
        val outs = tx.getJSONArray("outputs")
        for (k in 0 until ins.length()) {
            val o = ins.getJSONObject(k)
            if (bchKey(o.optString("address")) == me) net -= o.optLong("value") else if (from == null) from = o.optString("address")
        }
        for (k in 0 until outs.length()) {
            val o = outs.getJSONObject(k)
            if (bchKey(o.optString("address")) == me) net += o.optLong("value") else if (to == null) to = o.optString("address")
        }
        if (net == 0L) return@mapNotNull null
        val incoming = net > 0
        val txid = tx.getString("txid")
        Transfer(incoming, amount(BigInteger.valueOf(kotlin.math.abs(net)), 8), if (incoming) from else to,
            tx.optLong("time") * 1000, "https://blockchair.com/bitcoin-cash/transaction/$txid", txid,
            if (incoming) from else a, if (incoming) a else to, "BCH")
    }
    return historyOf("Bitcoin Cash", listOf(sourcePage(items, arr.length(), MAX, oldest)))
}

/** A Bitcoin Cash address the way Haskoin writes it, minus the "bitcoincash:" prefix (a legacy 1…/3… one converted). */
private fun bchKey(address: String): String =
    (legacyToCashAddr(address) ?: address.trim()).lowercase().removePrefix("bitcoincash:")

private const val BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
private const val CASHADDR_CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

/**
 * A legacy Bitcoin address (1… or 3…) as the same Bitcoin Cash address in cashaddr form ("bitcoincash:q…" or
 * "bitcoincash:p…"), worked out locally; null when [legacy] isn't a valid legacy address.
 */
internal fun legacyToCashAddr(legacy: String): String? {
    val raw = base58Check(legacy.trim()) ?: return null
    if (raw.size != 21) return null
    // Version byte: type (P2PKH 0, P2SH 1) in bits 3-6, hash size (0 = 160 bits) in bits 0-2.
    val type = when (raw[0].toInt() and 0xff) { 0x00 -> 0; 0x05 -> 8; else -> return null }
    val payload = toFiveBits(byteArrayOf(type.toByte()) + raw.copyOfRange(1, 21))
    val prefix = "bitcoincash"
    val mod = cashPolymod(prefix.map { it.code and 0x1f } + 0 + payload + List(8) { 0 })
    val checksum = (0 until 8).map { ((mod shr (5 * (7 - it))) and 0x1f).toInt() }
    return prefix + ":" + (payload + checksum).joinToString("") { CASHADDR_CHARSET[it].toString() }
}

/** Base58 with its 4-byte double-SHA-256 checksum, decoded and checked (null if it doesn't match). */
private fun base58Check(s: String): ByteArray? {
    if (s.isEmpty()) return null
    var n = BigInteger.ZERO
    for (c in s) {
        val d = BASE58.indexOf(c)
        if (d < 0) return null
        n = n.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(d.toLong()))
    }
    val digits = if (n.signum() == 0) ByteArray(0) else n.toByteArray().let { if (it[0].toInt() == 0) it.copyOfRange(1, it.size) else it }
    val bytes = ByteArray(s.takeWhile { it == '1' }.length) + digits
    if (bytes.size < 5) return null
    val body = bytes.copyOfRange(0, bytes.size - 4)
    val sha = java.security.MessageDigest.getInstance("SHA-256")
    val check = sha.digest(sha.digest(body)).copyOf(4)
    return if (check.contentEquals(bytes.copyOfRange(bytes.size - 4, bytes.size))) body else null
}

/** Bytes regrouped into 5-bit values, the last one padded with zeros. */
private fun toFiveBits(data: ByteArray): List<Int> {
    val out = mutableListOf<Int>()
    var acc = 0
    var bits = 0
    for (b in data) {
        acc = (acc shl 8) or (b.toInt() and 0xff)
        bits += 8
        while (bits >= 5) { bits -= 5; out += (acc shr bits) and 0x1f }
        acc = acc and ((1 shl bits) - 1)
    }
    if (bits > 0) out += (acc shl (5 - bits)) and 0x1f
    return out
}

/** The cashaddr checksum's BCH code (polymod) over 5-bit values. */
private fun cashPolymod(values: List<Int>): Long {
    var c = 1L
    for (d in values) {
        val c0 = (c ushr 35).toInt()
        c = ((c and 0x07ffffffffL) shl 5) xor d.toLong()
        if (c0 and 0x01 != 0) c = c xor 0x98f2bc8e61L
        if (c0 and 0x02 != 0) c = c xor 0x79b76d99e2L
        if (c0 and 0x04 != 0) c = c xor 0xf33e5fb3c4L
        if (c0 and 0x08 != 0) c = c xor 0xae2eabe2a8L
        if (c0 and 0x10 != 0) c = c xor 0x1e4f43e470L
    }
    return c xor 1L
}

private fun dogeHistory(a: String): History {
    val asked = minOf(MAX * 2, 200)
    val j = JSONObject(http("https://api.blockcypher.com/v1/doge/main/addrs/$a?limit=$asked"))
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
    val items = byTx.entries.mapNotNull { (hash, p) ->
        if (p.first == 0L) null
        else Transfer(p.first > 0, amount(BigInteger.valueOf(kotlin.math.abs(p.first)), 8), null, p.second * 1000,
            "https://blockchair.com/dogecoin/transaction/$hash", hash, null, null, "DOGE")
    }
    // BlockCypher counts inputs and outputs (not transactions) and says itself whether there are more.
    val more = j.optBoolean("hasMore")
    val oldest = byTx.values.map { it.second }.filter { it > 0 }.minOrNull()?.times(1000) ?: 0L
    return historyOf("Dogecoin", listOf(Part(items, more, if (more) oldest else 0L, grows = more && asked < 200)))
}

private val XRP_DROPS = Regex("[0-9]+")

private fun xrpHistory(a: String): History {
    val asked = minOf(MAX * 2, 400)
    val body = JSONObject().put("method", "account_tx")
        .put("params", JSONArray().put(JSONObject().put("account", a).put("limit", asked)))
    val result = JSONObject(http("https://xrplcluster.com/", body.toString())).getJSONObject("result")
    val txs = result.getJSONArray("transactions")
    var oldest = 0L
    val items = (0 until txs.length()).mapNotNull { i ->
        val o = txs.getJSONObject(i)
        val tx = o.optJSONObject("tx") ?: o.optJSONObject("tx_json") ?: return@mapNotNull null
        val time = (tx.optLong("date") + 946_684_800L) * 1000
        if (tx.has("date")) oldest = if (oldest == 0L) time else minOf(oldest, time)
        if (tx.optString("TransactionType") != "Payment") return@mapNotNull null
        // Only payments that went through, and only what actually arrived: a "partial payment" can name a big
        // Amount and deliver almost nothing (a known scam), so the ledger's delivered_amount is what counts.
        val meta = o.optJSONObject("meta") ?: return@mapNotNull null
        if (meta.optString("TransactionResult") != "tesSUCCESS") return@mapNotNull null
        // XRP itself is a string of drops; issued tokens are objects (left out).
        val drops = (meta.opt("delivered_amount") as? String)?.takeIf { XRP_DROPS.matches(it) } ?: return@mapNotNull null
        val from = tx.optString("Account")
        val to = tx.optString("Destination")
        // The account's history also lists payments between two others that only passed through its exchange
        // offers: not its own sends or receipts.
        if (from != a && to != a) return@mapNotNull null
        val incoming = to == a
        val hash = tx.optString("hash", o.optString("hash"))
        Transfer(incoming, amount(BigInteger(drops), 6), if (incoming) from else to,
            time, "https://livenet.xrpl.org/transactions/$hash", hash, from, to, "XRP")
    }
    // A "marker" in the answer means there are older ones.
    val count = if (result.has("marker")) maxOf(txs.length(), asked) else txs.length()
    return historyOf("XRP Ledger", listOf(sourcePage(items, count, asked, oldest, 400)))
}

private fun stellarHistory(a: String): History {
    val asked = minOf(MAX * 2, 200)
    val recs = JSONObject(http("https://horizon.stellar.org/accounts/$a/payments?order=desc&limit=$asked"))
        .getJSONObject("_embedded").getJSONArray("records")
    var oldest = 0L
    val items = (0 until recs.length()).mapNotNull { i ->
        val r = recs.getJSONObject(i)
        runCatching { Instant.parse(r.getString("created_at")).toEpochMilli() }.getOrNull()
            ?.let { oldest = if (oldest == 0L) it else minOf(oldest, it) }
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
    }
    return historyOf("Stellar", listOf(sourcePage(items, recs.length(), asked, oldest, 200)))
}

private fun algorandHistory(a: String): History {
    val asked = minOf(MAX * 2, 1000)
    val txs = JSONObject(http("https://mainnet-idx.algonode.cloud/v2/accounts/$a/transactions?limit=$asked")).getJSONArray("transactions")
    val oldest = (0 until txs.length()).map { txs.getJSONObject(it).optLong("round-time") }.filter { it > 0 }.minOrNull()?.times(1000) ?: 0L
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
    }
    return historyOf("Algorand", listOf(sourcePage(items, txs.length(), asked, oldest, 1000)))
}

private fun tezosHistory(a: String): History {
    val arr = JSONArray(http("https://api.tzkt.io/v1/accounts/$a/operations?type=transaction&limit=$MAX&sort.desc=id"))
    val oldest = (0 until arr.length()).mapNotNull { i ->
        runCatching { Instant.parse(arr.getJSONObject(i).getString("timestamp")).toEpochMilli() }.getOrNull()
    }.minOrNull() ?: 0L
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
    return historyOf("Tezos", listOf(sourcePage(items, arr.length(), MAX, oldest)))
}

/** Solana's USDT and USDC mints (history, balances and transaction lookup all use this one list). */
internal val SOL_STABLES = mapOf(
    "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB" to "USDT",
    "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v" to "USDC",
)

private fun solanaHistory(a: String): History {
    // Each signature list is a fixed number per account (every one costs a request below), so where the shortest
    // one ends is as far back as the merged list can go without gaps.
    val bounds = mutableListOf<Part>()
    // One request at a time: Solana's free RPC rejects batches.
    fun sigsOf(account: String, limit: Int, cap: Int): List<String> {
        val arr = solanaCall("getSignaturesForAddress", JSONArray().put(account).put(JSONObject().put("limit", limit))).getJSONArray("result")
        val oldest = (0 until arr.length()).map { arr.getJSONObject(it).optLong("blockTime") }.filter { it > 0 }.minOrNull() ?: 0L
        bounds += sourcePage(emptyList(), arr.length(), limit, oldest * 1000, cap)
        return (0 until arr.length()).map { arr.getJSONObject(it).getString("signature") }
    }
    // USDT / USDC arrive in separate token accounts, so their transactions are listed under those accounts.
    val tokenAccounts = SOL_STABLES.keys.flatMap { mint ->
        runCatching {
            val v = solanaCall("getTokenAccountsByOwner", JSONArray().put(a).put(JSONObject().put("mint", mint))
                .put(JSONObject().put("encoding", "jsonParsed"))).getJSONObject("result").getJSONArray("value")
            (0 until v.length()).map { v.getJSONObject(it).getString("pubkey") }
        }.getOrDefault(emptyList())
    }
    val sigs = (sigsOf(a, minOf(MAX, 20), 20) + tokenAccounts.flatMap { runCatching { sigsOf(it, 10, 10) }.getOrDefault(emptyList()) }).distinct()
    // Four at a time (one by one took most of a minute).
    val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
    val items = try {
        sigs.map { sig -> pool.submit<Transfer?> { solanaTransfer(a, sig) } }
            .mapNotNull { runCatching { it.get(20, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull() }
    } finally { pool.shutdown() }
    return historyOf("Solana", bounds + Part(items))
}

/**
 * One transaction for [a]: a USDT/USDC transfer if its token balance moved (the other side is the owner whose token
 * balance moved the opposite way), otherwise its SOL change (the other side is whoever's SOL moved the opposite way).
 */
private fun solanaTransfer(a: String, sig: String): Transfer? {
    val r = runCatching {
        solanaCall("getTransaction", JSONArray().put(sig)
            .put(JSONObject().put("encoding", "json").put("maxSupportedTransactionVersion", 0))).optJSONObject("result")
    }.getOrNull() ?: return null
    val meta = r.optJSONObject("meta") ?: return null
    if (meta.opt("err") != null && meta.opt("err") != JSONObject.NULL) return null
    val time = r.optLong("blockTime") * 1000
    val url = "https://solscan.io/tx/$sig"

    // Token balances: (owner, mint) -> amount before / after.
    fun balances(key: String): Map<Pair<String, String>, BigDecimal> {
        val arr = meta.optJSONArray(key) ?: return emptyMap()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.filter { it.optString("mint") in SOL_STABLES }
            .associate { (it.optString("owner") to it.getString("mint")) to BigDecimal(it.getJSONObject("uiTokenAmount").optString("amount", "0"))
                .movePointLeft(it.getJSONObject("uiTokenAmount").optInt("decimals", 6)) }
    }
    val preT = balances("preTokenBalances")
    val postT = balances("postTokenBalances")
    for (mint in SOL_STABLES.keys) {
        val mine = (postT[a to mint] ?: BigDecimal.ZERO) - (preT[a to mint] ?: BigDecimal.ZERO)
        if (mine.signum() == 0) continue
        val incoming = mine.signum() > 0
        val other = (preT.keys + postT.keys).filter { it.second == mint && it.first != a }.map { k ->
            k.first to (postT[k] ?: BigDecimal.ZERO) - (preT[k] ?: BigDecimal.ZERO)
        }.filter { (_, d) -> if (incoming) d.signum() < 0 else d.signum() > 0 }.maxByOrNull { it.second.abs() }?.first
        return Transfer(incoming, mine.abs(), other, time, url, sig, if (incoming) other else a, if (incoming) a else other,
            SOL_STABLES.getValue(mint))
    }

    val keys = r.getJSONObject("transaction").getJSONObject("message").getJSONArray("accountKeys")
    val all = (0 until keys.length()).map { keys.getString(it) }.toMutableList()
    meta.optJSONObject("loadedAddresses")?.let { la ->
        la.optJSONArray("writable")?.let { w -> for (k in 0 until w.length()) all += w.getString(k) }
        la.optJSONArray("readonly")?.let { ro -> for (k in 0 until ro.length()) all += ro.getString(k) }
    }
    val idx = all.indexOf(a).takeIf { it >= 0 } ?: return null
    val pre = meta.getJSONArray("preBalances")
    val post = meta.getJSONArray("postBalances")
    // The fee payer (the first account) also paid the fee from this balance: add it back, so only what was sent
    // counts, and a transaction that only cost a fee isn't listed as a payment.
    val fee = if (idx == 0) meta.optLong("fee") else 0L
    val delta = post.getLong(idx) - pre.getLong(idx) + fee
    if (delta == 0L) return null
    val other = (0 until minOf(all.size, pre.length(), post.length())).filter { it != idx }
        .map { it to post.getLong(it) - pre.getLong(it) }
        .filter { (_, d) -> if (delta > 0) d < 0 else d > 0 }
        .maxByOrNull { (_, d) -> kotlin.math.abs(d) }?.let { all[it.first] }
    val incoming = delta > 0
    return Transfer(incoming, amount(BigInteger.valueOf(kotlin.math.abs(delta)), 9), other, time, url, sig,
        if (incoming) other else a, if (incoming) a else other, "SOL")
}
/** Native-coin transfers on an EVM network, from whichever free source covers it. */
internal fun evmNativeTransfers(name: String, chainId: Long, blockscout: String?, routescan: Boolean, txBase: String, a: String): List<Transfer> =
    evmNativePage(name, chainId, blockscout, routescan, txBase, a).items

/** Ethplorer / Binplorer list at most 50 transactions per request with the free key. */
private const val PLORER_MAX = 50

/** The same, with how far back the list is complete and whether Load more can get more (see [Part]). */
internal fun evmNativePage(name: String, chainId: Long, blockscout: String?, routescan: Boolean, txBase: String, a: String): Part {
    val sym = mapOf("BSC" to "BNB", "Polygon" to "POL", "Avalanche" to "AVAX", "Celo" to "CELO", "Sonic" to "S")[name] ?: "ETH"
    /** An Etherscan-style answer with status "1" (Etherscan, Routescan). */
    fun etherscanPage(j: JSONObject): Part {
        val arr = j.getJSONArray("result")
        var oldest = 0L
        val items = (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val time = o.getString("timeStamp").toLong() * 1000
            oldest = if (oldest == 0L) time else minOf(oldest, time)
            if (o.optString("isError") == "1") return@mapNotNull null
            val v = BigInteger(o.optString("value", "0").ifEmpty { "0" })
            if (v.signum() == 0) return@mapNotNull null
            val from = o.getString("from")
            val to = o.optString("to")
            val incoming = to.equals(a, true)
            Transfer(incoming, amount(v, 18), if (incoming) from else to, time,
                txBase + o.getString("hash"), o.getString("hash"), from, to, sym)
        }
        return sourcePage(items, arr.length(), MAX, oldest)
    }
    fun etherscanStyle(url: String): Part {
        val j = JSONObject(http(url))
        return if (j.optString("status") != "1") Part(emptyList()) else etherscanPage(j)
    }
    /** Ethplorer (Ethereum) / Binplorer (BSC): free with their public "freekey". */
    fun plorer(host: String): Part {
        val asked = minOf(MAX, PLORER_MAX)
        val arr = JSONArray(http("https://$host/getAddressTransactions/$a?apiKey=freekey&limit=$asked"))
        val oldest = (0 until arr.length()).map { arr.getJSONObject(it).optLong("timestamp") }.filter { it > 0 }.minOrNull()?.times(1000) ?: 0L
        val items = (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            if (!o.optBoolean("success", true)) return@mapNotNull null
            val v = o.opt("rawValue")?.toString()?.takeIf { it.isNotBlank() && it != "null" }?.let { BigInteger(it) }
                ?: BigDecimal(o.optString("value", "0")).movePointRight(18).toBigInteger()
            if (v.signum() == 0) return@mapNotNull null
            val from = o.getString("from")
            val to = o.optString("to")
            val incoming = to.equals(a, true)
            Transfer(incoming, amount(v, 18), if (incoming) from else to, o.getLong("timestamp") * 1000,
                txBase + o.getString("hash"), o.getString("hash"), from, to, sym)
        }
        return sourcePage(items, arr.length(), asked, oldest, PLORER_MAX)
    }
    // Your Etherscan key first. Its free plan doesn't cover every network (BSC, Base, …): those answer with an
    // error, and then the free sources below answer instead, as for the stablecoin history.
    if (etherscanKey.isNotBlank()) {
        val j = runCatching {
            JSONObject(http("https://api.etherscan.io/v2/api?chainid=$chainId&module=account&action=txlist" +
                "&address=$a&page=1&offset=$MAX&sort=desc&apikey=${URLEncoder.encode(etherscanKey, "UTF-8")}"))
        }.getOrNull()
        if (j != null) {
            if (j.optString("status") == "1") return etherscanPage(j)
            if (j.optString("message").contains("No transactions", true)) return Part(emptyList())
        }
    }
    if (name == "BSC") return plorer("api.binplorer.com") // no other free BNB source
    if (name == "Ethereum") {
        runCatching { return plorer("api.ethplorer.io") } // then Routescan / Blockscout below
    }
    return when {
        routescan -> etherscanStyle("https://api.routescan.io/v2/network/mainnet/evm/$chainId/etherscan/api?module=account" +
            "&action=txlist&address=$a&page=1&offset=$MAX&sort=desc")
        blockscout != null -> {
            val j = JSONObject(http("https://$blockscout/api/v2/addresses/$a/transactions"))
            val arr = j.getJSONArray("items")
            var oldest = 0L
            val items = (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                runCatching { Instant.parse(o.getString("timestamp")).toEpochMilli() }.getOrNull()
                    ?.let { oldest = if (oldest == 0L) it else minOf(oldest, it) }
                if (o.optString("status") != "ok") return@mapNotNull null
                val v = BigInteger(o.optString("value", "0").ifEmpty { "0" })
                if (v.signum() == 0) return@mapNotNull null
                val from = o.getJSONObject("from").getString("hash")
                val to = o.optJSONObject("to")?.optString("hash").orEmpty()
                val incoming = to.equals(a, true)
                Transfer(incoming, amount(v, 18), if (incoming) from else to, Instant.parse(o.getString("timestamp")).toEpochMilli(),
                    txBase + o.getString("hash"), o.getString("hash"), from, to, sym)
            }
            // One page, whatever the limit: Load more can't reach further back here.
            val more = j.optJSONObject("next_page_params") != null || arr.length() >= BLOCKSCOUT_PAGE
            Part(items, more, if (more) oldest else 0L)
        }
        else -> Part(emptyList())
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

/**
 * Well-known Aptos tokens, by coin type (anyone can make a token with any name, so names alone prove nothing):
 * LayerZero WETH, Wormhole WBTC, Amnis amAPT / stAPT, Thala thAPT / sthAPT / MOD / THL, PancakeSwap CAKE.
 */
private val APTOS_KNOWN = setOf(
    "0xf22bede237a07e121b56d91a491eb7bcdfd1f5907926a9e58338f964a01b17fa::asset::WETH",
    "0xae478ff7d83ed072dbc5e264250e67ef58f57c99d89b447efd8a0a2e8b2be76e::coin::T",
    "0x111ae3e5bc816a5e63c2da97d0aa3886519e0cd5e4b046659fa35796bd11542a::amapt_token::AmnisApt",
    "0x111ae3e5bc816a5e63c2da97d0aa3886519e0cd5e4b046659fa35796bd11542a::stapt_token::StakedApt",
    "0xfaf4e633ae9eb31366c9ca24214231760926576c7b625313b3688b5e900731f6::staking::ThalaAPT",
    "0xfaf4e633ae9eb31366c9ca24214231760926576c7b625313b3688b5e900731f6::staking::StakedThalaAPT",
    "0x6f986d146e4a90b828d8c12c14b6f4e003fdff11a8eecceceb63744363eaac01::mod_coin::MOD",
    "0x7fd500c11216f0fe3095d0c4b8aa4d64a4e2e04f83758462f2b127255643615::thl_coin::THL",
    "0x159df6b7689437016108a019fd5bef736bac692b6d4a1f10c941f6fbb9a74ca6::oft::CakeOFT",
).map(::aptosType).toSet()

/** An Aptos coin type or asset address in one spelling: lower case, the address without leading zeros. */
private fun aptosType(t: String): String {
    val addr = t.substringBefore("::").lowercase().removePrefix("0x").trimStart('0')
    return "0x$addr" + t.substring(t.substringBefore("::").length)
}

/** Well-known Tezos token contracts: tzBTC, kUSD, ctez, PLENTY, QUIPU, USDtz, uUSD, YOU. */
private val TEZOS_KNOWN = setOf(
    "KT1PWx2mnDueood7fEmfbBDKx1D9BAnnXitn", "KT1K9gCRgaLRFKTErYt1wVxA3Frb9FjasjTV", "KT1SjXiUX63QvdNMcM2m492f7kuf8JxXRLp4",
    "KT1GRSvLoikDsXujKgZPsGLX8k8VvR2Tq95b", "KT193D4vozYnhGJQVtw7CoxxqphqUEEwK6Vb", "KT1LN4LPSqTMS7Sd2CJw4bbDGRkMv2t68Fy9",
    "KT1XRPEPXbZK25r3Htzp2o1x7xdMMmfocKNW", "KT1Xobej4mc6XgEjDoJoHtTKgbD1ELMvcQuL",
)

private fun aptosTokens(raw: String): List<TokenBalance> {
    val a = "0x" + raw.removePrefix("0x").lowercase().padStart(64, '0')
    val q = """query { current_fungible_asset_balances(where: { owner_address: { _eq: "$a" }, amount: { _gt: "0" } }) { amount asset_type metadata { symbol decimals } } }"""
    val arr = JSONObject(http("https://api.mainnet.aptoslabs.com/v1/graphql", JSONObject().put("query", q).toString()))
        .getJSONObject("data").getJSONArray("current_fungible_asset_balances")
    return (0 until arr.length()).mapNotNull { i ->
        val o = arr.getJSONObject(i)
        val m = o.optJSONObject("metadata") ?: return@mapNotNull null
        val sym = m.optString("symbol").trim()
        if (sym.isEmpty() || sym in SHOWN_ALREADY) return@mapNotNull null
        // No price here: only well-known tokens, so spam airdrops stay hidden.
        if (aptosType(o.optString("asset_type")) !in APTOS_KNOWN) return@mapNotNull null
        TokenBalance("Aptos", sym, amount(BigInteger(o.get("amount").toString()), m.optInt("decimals", 8)), null)
    }
}

private fun tezosTokens(a: String): List<TokenBalance> {
    val arr = JSONArray(http("https://api.tzkt.io/v1/tokens/balances?account=$a&balance.gt=0&limit=50"))
    return (0 until arr.length()).mapNotNull { i ->
        val o = arr.getJSONObject(i)
        val token = o.optJSONObject("token") ?: return@mapNotNull null
        // No price here: only well-known tokens, so spam airdrops stay hidden.
        if (token.optJSONObject("contract")?.optString("address") !in TEZOS_KNOWN) return@mapNotNull null
        val meta = token.optJSONObject("metadata") ?: return@mapNotNull null
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
