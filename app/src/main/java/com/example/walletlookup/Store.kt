package com.example.walletlookup

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/**
 * A saved address. [group] is free text such as "Trust Wallet"; empty means no group.
 * [contact] marks someone else's address (address book): not counted in totals or watched by alerts.
 */
data class SavedAddress(
    val name: String,
    val address: String,
    val group: String = "",
    val contact: Boolean = false,
    /** Pinned wallets stay at the top of their list. */
    val pinned: Boolean = false,
    /** Where this wallet lives, e.g. "Binance" or "Trust Wallet" (optional). */
    val platform: String = "",
    /** Free-text note, e.g. "Ali's Binance, rent payments" (optional). */
    val note: String = "",
    /** Archived wallets are hidden everywhere (totals, alerts, lists) until you show or unarchive them. */
    val archived: Boolean = false,
    /** Muted wallets are still refreshed and their payments logged, but they never notify. */
    val muted: Boolean = false,
)

/** How the Wallets tab orders addresses. CUSTOM is your own order (Move up / Move down). */
enum class WalletSort(val label: String) { CUSTOM("Custom"), VALUE("Value"), NAME("Name") }

/** A balance change noticed by payment alerts: +amount received, −amount sent. */
data class Seen(
    val symbol: String,
    val network: String,
    val amount: BigDecimal,
    val time: Long,
    /** Transaction found for this change when the alert fired (null for older entries). */
    val hash: String? = null,
    val from: String? = null,
    val to: String? = null,
)

/** Reads a backup made by [Store.exportJson] (also accepts a bare JSON array of wallets). */
fun parseBackup(json: String): List<SavedAddress> {
    val t = json.trim()
    val arr = if (t.startsWith("[")) JSONArray(t) else JSONObject(t).getJSONArray("wallets")
    return (0 until arr.length()).mapNotNull<Int, SavedAddress> { i ->
        val o = arr.getJSONObject(i)
        val address = o.optString("address").trim()
        if (address.isEmpty()) null
        else SavedAddress(o.optString("name", address.take(8)), address, o.optString("group", ""),
            o.optBoolean("contact", false), o.optBoolean("pinned", false), o.optString("platform", ""), o.optString("note", ""),
            o.optBoolean("archived", false), o.optBoolean("muted", false))
    }.map(::foldered)
}

/** The Etherscan key saved in a backup file (empty when the backup has none). */
fun parseBackupKey(json: String): String =
    runCatching { JSONObject(json.trim()).optString("etherscan_key", "").trim() }.getOrDefault("")

/** Folders and platforms are one thing now: a wallet with only a platform goes into that platform's folder. */
private fun foldered(w: SavedAddress): SavedAddress =
    if (!w.contact && w.group.isBlank() && w.platform.isNotBlank()) w.copy(group = w.platform) else w

enum class ThemeMode(val label: String) { SYSTEM("Follow phone (black or white)"), DARK("Black (OLED)"), LIGHT("White") }

/** Small on-device store for saved addresses and settings (nothing leaves the phone). */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("wallet_lookup", Context.MODE_PRIVATE)

    fun loadAddresses(): List<SavedAddress> {
        val arr = JSONArray(prefs.getString("addresses", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            SavedAddress(o.getString("name"), o.getString("address"), o.optString("group", ""),
                o.optBoolean("contact", false), o.optBoolean("pinned", false), o.optString("platform", ""), o.optString("note", ""),
            o.optBoolean("archived", false), o.optBoolean("muted", false))
        }.map(::foldered)
    }

    fun saveAddresses(list: List<SavedAddress>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("name", it.name).put("address", it.address).put("group", it.group)
                .put("contact", it.contact).put("pinned", it.pinned).put("platform", it.platform).put("note", it.note)
                .put("archived", it.archived).put("muted", it.muted))
        }
        prefs.edit().putString("addresses", arr.toString()).putLong("addresses_changed", System.currentTimeMillis()).apply()
    }

    /** When a backup was last exported, and when the wallet list last changed (for the backup reminder). */
    var lastBackup: Long
        get() = prefs.getLong("last_backup", 0L)
        set(v) = prefs.edit().putLong("last_backup", v).apply()
    val addressesChangedAt: Long get() = prefs.getLong("addresses_changed", 0L)

    var theme: ThemeMode
        get() = runCatching { ThemeMode.valueOf(prefs.getString("theme2", ThemeMode.SYSTEM.name)!!) }.getOrDefault(ThemeMode.SYSTEM)
        set(v) = prefs.edit().putString("theme2", v.name).apply()

    var lockEnabled: Boolean
        get() = prefs.getBoolean("lock", true)
        set(v) = prefs.edit().putBoolean("lock", v).apply()

    /** How long the app may stay in the background before asking for the fingerprint again (0 = every time). */
    var lockTimeoutSec: Int
        get() = prefs.getInt("lock_timeout", 0)
        set(v) = prefs.edit().putInt("lock_timeout", v).apply()

    /** Payments the background alerts detected, newest first (kept per wallet, last 100). */
    fun addActivity(address: String, entries: List<Seen>) = saveActivity(address, entries + loadActivity(address))

    /** Replaces the wallet's detected payments (e.g. after filling in details found later). */
    fun saveActivity(address: String, list: List<Seen>) {
        val all = list.take(100)
        val arr = JSONArray()
        all.forEach {
            arr.put(JSONObject().put("sym", it.symbol).put("net", it.network).put("amt", it.amount.toPlainString()).put("t", it.time)
                .put("hash", it.hash ?: "").put("from", it.from ?: "").put("to", it.to ?: ""))
        }
        prefs.edit().putString("activity:$address", arr.toString()).apply()
    }

    fun loadActivity(address: String): List<Seen> = runCatching {
        val arr = JSONArray(prefs.getString("activity:$address", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Seen(o.getString("sym"), o.getString("net"), BigDecimal(o.getString("amt")), o.getLong("t"),
                o.optString("hash").ifEmpty { null }, o.optString("from").ifEmpty { null }, o.optString("to").ifEmpty { null })
        }
    }.getOrDefault(emptyList())

    /** The last safety check of an address and when it ran (re-checked every few hours, not on every open). */
    fun saveSafety(address: String, safety: Safety) {
        val arr = JSONArray()
        safety.checks.forEach {
            arr.put(JSONObject().put("level", it.level.name).put("title", it.title).put("detail", it.detail).put("failed", it.failed))
        }
        prefs.edit().putString("safety:" + address.lowercase(), JSONObject().put("t", System.currentTimeMillis()).put("checks", arr).toString()).apply()
    }

    fun loadSafety(address: String): Pair<Long, Safety>? = runCatching {
        val o = JSONObject(prefs.getString("safety:" + address.lowercase(), null) ?: return null)
        val arr = o.getJSONArray("checks")
        o.getLong("t") to Safety((0 until arr.length()).map { arr.getJSONObject(it) }
            .map { Check(Level.valueOf(it.getString("level")), it.getString("title"), it.getString("detail"), it.optBoolean("failed")) })
    }.getOrNull()

    /** Daily summary notification at about 9 AM. */
    var dailySummary: Boolean
        get() = prefs.getBoolean("daily_summary", false)
        set(v) = prefs.edit().putBoolean("daily_summary", v).apply()

    /** USDT amount that counts as a "big payment" (louder alert). 0 = off. */
    var bigPayment: Int
        get() = prefs.getInt("big_payment", 0)
        set(v) = prefs.edit().putInt("big_payment", v).apply()

    /** "app" locks the whole app; "wallets" locks only the Wallets tab. */
    /** App language: "en", "ru" or "hi". */
    var language: String
        get() = prefs.getString("language", "en") ?: "en"
        set(v) = prefs.edit().putString("language", v).apply()

    /** Tabs whose first-run tips were already shown (or skipped). */
    var tipsSeen: Set<String>
        get() = prefs.getStringSet("tips_seen", emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet("tips_seen", v).apply()

    /** The crash report that was already offered for sending (its hash), so it is offered only once. */
    var crashPrompted: Int
        get() = prefs.getInt("crash_prompted", 0)
        set(v) = prefs.edit().putInt("crash_prompted", v).apply()

    /** Wallets tab: hide wallets worth less than $1. */
    var hideSmall: Boolean
        get() = prefs.getBoolean("hide_small", false)
        set(v) = prefs.edit().putBoolean("hide_small", v).apply()



    /** Only notify for USDT changes at least this big (0 = every change, including gas fees). */
    var alertMin: Int
        get() = prefs.getInt("alert_min", 0)
        set(v) = prefs.edit().putInt("alert_min", v).apply()

    /** Your own order of folders (groups) in the Wallets tab. */
    var groupOrder: List<String>
        get() = runCatching {
            val arr = JSONArray(prefs.getString("group_order", "[]"))
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())
        set(v) = prefs.edit().putString("group_order", JSONArray(v).toString()).apply()

    var currency: Fiat
        get() = runCatching { Fiat.valueOf(prefs.getString("currency", Fiat.USDT.name)!!) }.getOrDefault(Fiat.USDT)
        set(v) = prefs.edit().putString("currency", v.name).apply()

    /** Last known USD → currency rate, so values show instantly offline. */
    var currencyRate: Double
        get() = prefs.getFloat("currency_rate", 1f).toDouble()
        set(v) = prefs.edit().putFloat("currency_rate", v.toFloat()).apply()

    /** Folders you collapsed in the Wallets tab (kept after restarting the app). */
    var collapsedGroups: Set<String>
        get() = runCatching {
            val arr = JSONArray(prefs.getString("collapsed_groups", "[]"))
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        }.getOrDefault(emptySet())
        set(v) = prefs.edit().putString("collapsed_groups", JSONArray(v.toList()).toString()).apply()

    var walletSort: WalletSort
        get() = runCatching { WalletSort.valueOf(prefs.getString("wallet_sort", WalletSort.CUSTOM.name)!!) }.getOrDefault(WalletSort.CUSTOM)
        set(v) = prefs.edit().putString("wallet_sort", v.name).apply()

    /** Latest transfers across your wallets for Home: (wallet address, network, transfer). */
    fun saveRecentTx(list: List<Triple<String, String, Transfer>>) {
        val arr = JSONArray()
        list.forEach { (w, net, t) ->
            arr.put(JSONObject().put("w", w).put("net", net).put("in", t.incoming).put("amt", t.amount.toPlainString())
                .put("sym", t.symbol).put("t", t.time).put("hash", t.hash ?: "").put("from", t.from ?: "")
                .put("to", t.to ?: "").put("cp", t.counterparty ?: "").put("url", t.txUrl ?: ""))
        }
        prefs.edit().putString("recent_tx", arr.toString()).apply()
    }

    fun loadRecentTx(): List<Triple<String, String, Transfer>> = runCatching {
        val arr = JSONArray(prefs.getString("recent_tx", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Triple(o.getString("w"), o.getString("net"), Transfer(
                o.getBoolean("in"), BigDecimal(o.getString("amt")), o.optString("cp").ifEmpty { null }, o.getLong("t"), o.optString("url").ifEmpty { null },
                o.optString("hash").ifEmpty { null }, o.optString("from").ifEmpty { null }, o.optString("to").ifEmpty { null },
                o.getString("sym"),
            ))
        }
    }.getOrDefault(emptyList())
    /** Addresses checked on Home that aren't saved, newest first (last 5), with when they were checked. */
    fun addRecent(address: String) {
        val list = (listOf(address to System.currentTimeMillis()) + loadRecentTimed().filter { it.first != address }).take(5)
        val arr = JSONArray()
        list.forEach { (a, t) -> arr.put(JSONObject().put("a", a).put("t", t)) }
        prefs.edit().putString("recent", arr.toString()).apply()
    }

    fun loadRecentTimed(): List<Pair<String, Long>> = runCatching {
        val arr = JSONArray(prefs.getString("recent", "[]"))
        (0 until arr.length()).map { i ->
            // Older versions kept plain addresses without a time.
            arr.optJSONObject(i)?.let { it.getString("a") to it.optLong("t") } ?: (arr.getString(i) to 0L)
        }
    }.getOrDefault(emptyList())

    fun clearRecent() = prefs.edit().remove("recent").apply()

    /** Lock straight away when the phone screen turns off, whatever the auto-lock time. */
    var lockOnScreenOff: Boolean
        get() = prefs.getBoolean("lock_screen_off", true)
        set(v) = prefs.edit().putBoolean("lock_screen_off", v).apply()

    /** Forget everything stored about a deleted wallet (balances, alert baseline, payment log). */
    fun forget(address: String) {
        prefs.edit().remove("cache:$address").remove("snap:$address").remove("activity:$address")
            .remove("safety:" + address.lowercase()).apply()
    }

    var hideBalances: Boolean
        get() = prefs.getBoolean("hide", false)
        set(v) = prefs.edit().putBoolean("hide", v).apply()

    var etherscanKey: String
        get() = prefs.getString("etherscan_key", "").orEmpty()
        set(v) = prefs.edit().putString("etherscan_key", v).apply()

    /** Saved wallets as a JSON backup file. */
    fun exportJson(): String {
        val arr = JSONArray()
        loadAddresses().forEach {
            arr.put(JSONObject().put("name", it.name).put("address", it.address).put("group", it.group)
                .put("contact", it.contact).put("pinned", it.pinned).put("platform", it.platform).put("note", it.note)
                .put("archived", it.archived).put("muted", it.muted))
        }
        return JSONObject().put("app", "Batwallet").put("version", 2).put("wallets", arr)
            .put("etherscan_key", etherscanKey).put("group_order", JSONArray(groupOrder)).toString(2)
    }

    var alertsEnabled: Boolean
        get() = prefs.getBoolean("alerts", false)
        set(v) = prefs.edit().putBoolean("alerts", v).apply()

    /** Instant mode: a foreground service checks every 30 seconds instead of every 15 minutes. */
    var instantAlerts: Boolean
        get() = prefs.getBoolean("instant", false)
        set(v) = prefs.edit().putBoolean("instant", v).apply()

    /** Calls [listener] with the address whenever its saved balances change (e.g. after a payment alert). */
    fun onCacheChanged(listener: (String) -> Unit): SharedPreferences.OnSharedPreferenceChangeListener {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != null && key.startsWith("cache:")) listener(key.removePrefix("cache:"))
        }
        prefs.registerOnSharedPreferenceChangeListener(l)
        return l
    }

    /** Calls [listener] with the address whenever payment alerts record (or fill in) a payment for it. */
    fun onActivityChanged(listener: (String) -> Unit): SharedPreferences.OnSharedPreferenceChangeListener {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != null && key.startsWith("activity:")) listener(key.removePrefix("activity:"))
        }
        prefs.registerOnSharedPreferenceChangeListener(l)
        return l
    }

    fun stopListening(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(l)

    /** Last full lookup of an address, shown straight away while a fresh one loads. */
    fun loadCache(address: String): Cached? = runCatching {
        val o = JSONObject(prefs.getString("cache:$address", null) ?: return null)
        val arr = o.getJSONArray("results")
        val results = (0 until arr.length()).map { i ->
            val r = arr.getJSONObject(i)
            val rows = r.getJSONArray("rows").let { rs ->
                (0 until rs.length()).map { j -> rs.getJSONArray(j).let { it.getString(0) to it.getString(1) } }
            }
            ChainResult(
                chain = r.getString("chain"),
                rows = rows,
                explorerUrl = r.optString("explorer").ifEmpty { null },
                active = r.optBoolean("active", true),
                usdt = BigDecimal(r.optString("usdt", "0")),
                usdc = BigDecimal(r.optString("usdc").ifEmpty { "0" }),
                nativeSym = r.optString("nativeSym"),
                native = BigDecimal(r.optString("native", "0")),
                usd = r.optString("usd").ifEmpty { null }?.let { BigDecimal(it) },
            )
        }
        Cached(results, o.getLong("time"))
    }.getOrNull()

    fun saveCache(address: String, cached: Cached) {
        val arr = JSONArray()
        cached.results.forEach { r ->
            val rows = JSONArray()
            r.rows.forEach { (l, v) -> rows.put(JSONArray().put(l).put(v)) }
            arr.put(
                JSONObject()
                    .put("chain", r.chain).put("rows", rows).put("explorer", r.explorerUrl.orEmpty())
                    .put("active", r.active).put("usdt", r.usdt.toPlainString()).put("usdc", r.usdc.toPlainString())
                    .put("nativeSym", r.nativeSym).put("native", r.native.toPlainString())
                    .put("usd", r.usd?.toPlainString().orEmpty())
            )
        }
        prefs.edit().putString("cache:$address", JSONObject().put("time", cached.time).put("results", arr).toString()).apply()
    }

    /** Last balances seen by the background checker, or null if this address was never checked. */
    fun loadSnapshot(address: String): Map<String, BigDecimal>? = runCatching {
        // A damaged entry counts as "never checked": the next check records fresh starting balances.
        val o = JSONObject(prefs.getString("snap:$address", null) ?: return null)
        o.keys().asSequence().associateWith { BigDecimal(o.getString(it)) }
    }.getOrNull()

    fun saveSnapshot(address: String, snap: Map<String, BigDecimal>) {
        val o = JSONObject()
        snap.forEach { (k, v) -> o.put(k, v.toPlainString()) }
        prefs.edit().putString("snap:$address", o.toString()).apply()
    }
}

/** "Folder · Name" for a saved wallet (just the name when it has no folder). */
fun walletLabel(w: SavedAddress): String = if (w.group.isNotBlank()) "${w.group} · ${w.name}" else w.name

/**
 * Labels by address, for showing who is on the other side of a transfer. Keys are the bare address and
 * "address@network", so the wallet saved for that exact network wins (e.g. your BSC one in a BSC transfer).
 * Your own wallets win over contacts with the same address.
 */
fun nameLabels(saved: List<SavedAddress>): Map<String, String> {
    val out = HashMap<String, String>()
    // Later puts win: contacts first, then your wallets (network-pinned ones before "all networks").
    val order = saved.filter { it.contact } + saved.filter { !it.contact && netOf(it.address) != null } +
        saved.filter { !it.contact && netOf(it.address) == null }
    for (w in order) {
        val raw = rawAddress(w.address).lowercase()
        out[raw] = walletLabel(w)
        netOf(w.address)?.let { out["$raw@${it.lowercase()}"] = walletLabel(w) }
    }
    return out
}

/** Label for [address] on [network] from [nameLabels]. */
fun Map<String, String>.labelFor(address: String, network: String?): String? {
    val raw = rawAddress(address).lowercase()
    return network?.let { this["$raw@${it.lowercase()}"] } ?: this[raw]
}