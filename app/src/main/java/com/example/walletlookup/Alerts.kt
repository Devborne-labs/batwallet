package com.example.walletlookup

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal
import java.util.concurrent.TimeUnit

/**
 * Payment alerts. Two modes:
 *  - battery saver: a WorkManager job every ~15 minutes, plus [ChargingWorker] checking every 30 seconds while charging
 *  - instant: [WatchService], a foreground service that checks every 30 seconds
 */
object Alerts {
    private const val CHANNEL = "payments"
    const val BIG_CHANNEL = "big_payments"
    const val SUMMARY_CHANNEL = "daily_summary"
    private const val DAILY = "daily-summary"
    const val WATCH_CHANNEL = "watching"
    private const val PERIODIC = "balance-check"
    private const val NOW = "balance-check-now"
    const val INSTANT_INTERVAL_MS = 30_000L
    private const val CHARGING = "balance-check-charging"

    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    private val checking = Mutex()
    private var checkRuns = 0

    /** Start whatever the current settings ask for, and stop the rest. Safe to call any time. */
    fun apply(context: Context) {
        val store = Store(context)
        createChannels(context)
        applyDaily(context, store)
        if (!store.alertsEnabled) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
            WorkManager.getInstance(context).cancelUniqueWork(CHARGING)
            context.stopService(Intent(context, WatchService::class.java))
            return
        }
        // The 15-minute job stays on in both modes as a safety net if the phone kills the instant service.
        val req = PeriodicWorkRequestBuilder<BalanceWorker>(15, TimeUnit.MINUTES).setConstraints(online).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        if (store.instantAlerts) {
            WorkManager.getInstance(context).cancelUniqueWork(CHARGING)
            ContextCompat.startForegroundService(context, Intent(context, WatchService::class.java))
        } else {
            context.stopService(Intent(context, WatchService::class.java))
            // Once after updating from 16.8 or older: replace the old queued run, which waited on Android's
            // "charging" condition and could stay stuck.
            val prefs = context.getSharedPreferences("alerts", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("charging2", false)) {
                WorkManager.getInstance(context).cancelUniqueWork(CHARGING)
                prefs.edit().putBoolean("charging2", true).apply()
            }
            if (pluggedIn(context)) scheduleCharging(context, ExistingWorkPolicy.KEEP)
        }
    }

    /**
     * Battery saver: the 30-second loop while plugged in. Started when the app opens or the 15-minute job finds the
     * phone plugged in. Android's own "charging" condition isn't used: it can stay unmet on a full battery.
     */
    fun scheduleCharging(context: Context, policy: ExistingWorkPolicy) {
        val req = OneTimeWorkRequestBuilder<ChargingWorker>().setConstraints(online).build()
        WorkManager.getInstance(context).enqueueUniqueWork(CHARGING, policy, req)
    }

    /** Plugged into a charger (also true when the battery is full and no longer "charging"). */
    fun pluggedIn(context: Context): Boolean = runCatching {
        context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0
    }.getOrDefault(false)

    /** Run a check right away, e.g. to record starting balances for a newly added wallet. */
    fun checkNow(context: Context) {
        val req = OneTimeWorkRequestBuilder<BalanceWorker>().setConstraints(online).build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, req)
    }

    /** Daily summary at about 9 AM (separate from payment alerts). */
    private fun applyDaily(context: Context, store: Store) {
        val wm = WorkManager.getInstance(context)
        if (!store.dailySummary) { wm.cancelUniqueWork(DAILY); return }
        val now = java.time.ZonedDateTime.now()
        var next = now.withHour(9).withMinute(0).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val delay = java.time.Duration.between(now, next).toMillis()
        val req = PeriodicWorkRequestBuilder<DailySummaryWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS).setConstraints(online).build()
        wm.enqueueUniquePeriodicWork(DAILY, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    /** Android's own page for the "Instant alerts running" notification, where it can be switched off. */
    fun watchChannelSettings(context: Context): Intent =
        Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, WATCH_CHANNEL)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun hideWatchIntent(context: Context): android.app.PendingIntent =
        android.app.PendingIntent.getActivity(context, 77, watchChannelSettings(context),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(BIG_CHANNEL, "Big payments", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Payments above the amount you chose, with a longer vibration"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 600, 250, 600, 250, 900)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(SUMMARY_CHANNEL, "Daily summary", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Your total and today's change, once a day" }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Payments", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Money received or sent from your saved wallets" }
        )
        nm.createNotificationChannel(
            NotificationChannel(WATCH_CHANNEL, "Instant alerts running", NotificationManager.IMPORTANCE_MIN)
                .apply { description = "Small permanent notification while instant alerts are on" }
        )
    }

    fun canNotify(context: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun ignoresBatteryLimits(context: Context) =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /** Opens the system prompt that lets Batwallet run in the background without battery limits. */
    @SuppressLint("BatteryLife")
    fun askToIgnoreBatteryLimits(context: Context) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        runCatching { context.startActivity(intent) }
            .onFailure { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }

    fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    internal fun notify(context: Context, id: Int, title: String, text: String, channel: String = CHANNEL) {
        if (!canNotify(context)) return
        val markRead = PendingIntent.getBroadcast(
            context, id,
            Intent(context, MarkReadReceiver::class.java).putExtra(MarkReadReceiver.EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_bat)
            .setColor(0xFF9E9E9E.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openAppIntent(context))
            .addAction(0, "Mark as read", markRead)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (_: SecurityException) {
            // Permission was revoked between the check and the call.
        }
    }

    private val STABLES = setOf("USDT", "USDC")

    /** An alert entry for one actual transfer (signed amount, its own time and details). */
    private fun seenOf(sym: String, net: String, t: Transfer, fallbackTime: Long) =
        Seen(sym, net, if (t.incoming) t.amount else t.amount.negate(), t.time.takeIf { it > 0 } ?: fallbackTime, t.hash, t.from, t.to)
    private const val DAY_MS = 24 * 3_600_000L
    private const val WEEK_MS = 7 * DAY_MS
    private val lastFill = HashMap<String, Long>()
    /** Per wallet: when its current payment notification started, its lines, and how many payments it covers. */
    private val burst = java.util.concurrent.ConcurrentHashMap<String, Triple<Long, List<Pair<String, String>>, Int>>()
    private val searchedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * The transfer behind a balance change: same network, coin, direction and exact amount, made up to 2 hours before
     * it was noticed (or just after), and not already used for another entry ([taken] transaction IDs).
     */
    private fun matchTransfer(recent: List<Pair<String, Transfer>>, sym: String, net: String, diff: BigDecimal,
                              noticed: Long, taken: Set<String>): Transfer? =
        recent.firstOrNull { (n, t) ->
            n == net && t.symbol.equals(sym, true) && t.incoming == (diff.signum() > 0) &&
                t.amount.compareTo(diff.abs()) == 0 && (t.hash == null || t.hash !in taken) &&
                (t.time <= 0 || t.time in noticed - 2 * 3_600_000L..noticed + 10 * 60_000L)
        }?.second

    /**
     * Payments noticed before their transaction could be found (the free node hadn't caught up, or the lookup failed)
     * get their sender, receiver and transaction ID filled in later: first from recent history, then by searching
     * the blocks around the time it was noticed (a few per run). For up to a week; at most every 2 minutes per wallet.
     * Also run by the app itself when Home loads.
     */
    suspend fun resolveMissing(context: Context, w: SavedAddress) {
        kotlinx.coroutines.withTimeoutOrNull(15_000) { fillMissingDetails(Store(context), w) }
    }

    private suspend fun fillMissingDetails(store: Store, w: SavedAddress) {
        // An exchange's own wallet moves thousands of payments an hour: nothing useful to match there.
        if (knownPlatform(w.address) != null) return
        val now = System.currentTimeMillis()
        // Fee entries saved by older versions (coin drop next to a stablecoin send): drop them.
        val saved = store.loadActivity(w.address)
        val acts = saved.filterNot { s ->
            s.hash == null && s.amount.signum() < 0 && s.symbol !in STABLES && saved.any { o ->
                o.network == s.network && o.symbol in STABLES && o.amount.signum() < 0 && kotlin.math.abs(o.time - s.time) < 2 * 3_600_000L
            }
        }
        if (acts.size != saved.size) store.saveActivity(w.address, acts)
        if (acts.none { it.hash == null && now - it.time < WEEK_MS }) return
        if (now - (lastFill[w.address] ?: 0L) < 2 * 60_000L) return
        lastFill[w.address] = now
        // A failed history request isn't the end: the block search below still runs.
        val recent = try {
            history(w.address, limit = 25).flatMap { h -> h.items.map { h.network to it } }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        val taken = acts.mapNotNull { it.hash }.toMutableSet()
        var changed = false
        var searches = 0
        val updated = acts.flatMap { s ->
            if (s.hash != null || now - s.time >= WEEK_MS) return@flatMap listOf(s)
            val near = recent.filter { (n, t) -> n == s.network && t.symbol.equals(s.symbol, true) && (t.hash == null || t.hash !in taken) }
            // Recent history first; then a search of the blocks around that time (a few per run).
            // Each entry gets a block search at most every 10 minutes, so ones that can't be found yet don't use up
            // the turns of the others (it used to always retry the newest three, and older ones never got a turn).
            val key = "${w.address}|${s.network}|${s.symbol}|${s.amount.toPlainString()}|${s.time}"
            val found = pickTransfers(near.map { it.second }, s.amount, s.time).ifEmpty {
                if (now - (searchedAt[key] ?: 0L) >= 10 * 60_000L && searches++ < 3) {
                    searchedAt[key] = now
                    runCatching { findTransfers(w.address, s.network, s.symbol, s.amount, s.time) }.getOrDefault(emptyList())
                        .filter { it.hash == null || it.hash !in taken }
                } else emptyList()
            }
            if (found.isEmpty()) return@flatMap listOf(s)
            found.forEach { t -> t.hash?.let { taken += it } }
            changed = true
            found.map { t -> seenOf(s.symbol, s.network, t, s.time) }
        }
        if (changed) store.saveActivity(w.address, updated.sortedByDescending { it.time })
    }

    /** Compare every saved wallet with its last known balances and notify about any change. */
    suspend fun checkWallets(context: Context) = checking.withLock {
        val store = Store(context)
        if (!store.alertsEnabled) return@withLock
        // Notifications in the app's language and currency, even when the app isn't open.
        I18n.load(context)
        I18n.lang = store.language
        fiat = store.currency
        fiatRate = store.currencyRate
        if (historyPrefs == null) historyPrefs = context.getSharedPreferences("history", Context.MODE_PRIVATE) // saved prices

        // Lighter checks: only the networks a wallet actually uses, with a full sweep every 20th run
        // (≈10 min in instant mode) to catch money arriving on a new network.
        val full = checkRuns++ % 20 == 0
        for (w in store.loadAddresses().filter { !it.contact && !it.archived }) {
            val used = if (full) null else store.loadCache(w.address)?.results
                ?.filter { it.active && it.error == null }?.map { it.network }?.toSet()?.ifEmpty { null }
            val fresh = try {
                snapshot(w.address, used)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                continue
            }
            if (fresh.isEmpty()) continue
            val old = store.loadSnapshot(w.address)
            // Keep old values for networks that failed this time, so they don't look like changes later.
            store.saveSnapshot(w.address, (old ?: emptyMap()) + fresh)
            if (old == null) continue // first check: just remember the starting balances
            // Never let the search for missing details hold up the alert itself.
            kotlinx.coroutines.withTimeoutOrNull(15_000) { fillMissingDetails(store, w) }

            val changes = fresh.mapNotNull { (key, now) ->
                val before = old[key] ?: return@mapNotNull null
                val diff = now - before
                if (diff.signum() == 0) null else key to diff
            }
            if (changes.isEmpty()) continue

            // Update the balances the app shows, so they match this payment without a manual refresh.
            try {
                val type = detect(w.address)
                if (type != null) {
                    val results = lookup(w.address, type)
                    if (results.none { it.error != null }) store.saveCache(w.address, Cached(results, System.currentTimeMillis()))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The next app refresh will catch up.
            }

            val now = System.currentTimeMillis()
            // Find the actual transactions behind these changes (they are seconds to minutes old, so even the
            // short free BSC window has them). Lets the entry open its full details later.
            val recent = try {
                history(w.address, limit = 10).flatMap { h -> h.items.map { h.network to it } }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            val taken = store.loadActivity(w.address).mapNotNull { it.hash }.toMutableSet()
            val matches = changes.associate { (key, diff) ->
                val (sym, net) = key.split("|", limit = 2)
                key to matchTransfer(recent, sym, net, diff, now, taken)?.also { m -> m.hash?.let { taken += it } }
            }
            // Several transfers between two checks show up as one net change (+100.01 and −130 as −29.99): record
            // the actual transfers instead of a payment that never happened.
            val parts = changes.filter { matches[it.first] == null }.associate { (key, diff) ->
                val (sym, net) = key.split("|", limit = 2)
                key to pickTransfers(recent.filter { (n, t) -> n == net && t.symbol.equals(sym, true) && (t.hash == null || t.hash !in taken) }
                    .map { it.second }, diff, now).also { list -> list.forEach { t -> t.hash?.let { taken += it } } }
            }.filterValues { it.isNotEmpty() }
            // Coins (BNB, ETH, POL): not in transfer logs, so trace them now, while the node still has the balances.
            val coins = changes.filter { (key, _) ->
                key.substringBefore("|") !in STABLES && matches[key] == null && key !in parts
            }.mapNotNull { (key, diff) ->
                val net = key.substringAfter("|")
                kotlinx.coroutines.withTimeoutOrNull(10_000) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { recentCoinTransfer(w.address, net, diff) }.getOrNull() }
                }?.let { key to it }
            }.toMap()
            // The coin dropping a little while a stablecoin was sent on the same network is that send's fee,
            // not a payment of its own: leave it out (it was listed as a separate "sent" before).
            val fees = changes.filter { (key, diff) ->
                val (sym, net) = key.split("|", limit = 2)
                diff.signum() < 0 && sym !in STABLES && matches[key] == null &&
                    changes.any { (k2, d2) -> k2 != key && k2.substringAfter("|") == net && d2.signum() < 0 && k2.substringBefore("|") in STABLES }
            }.map { it.first }.toSet()
            // Exchange wallets: money leaving is the exchange sweeping it, not a payment of yours, so it isn't kept.
            val exchangeWallet = isExchangeWallet(w.address, w.group, w.name)
            store.addActivity(w.address, changes.filter { it.first !in fees && !isCoinDust(it.second, it.first.substringBefore("|")) }.flatMap { (key, diff) ->
                val (sym, net) = key.split("|", limit = 2)
                val split = parts[key]
                if (split != null) split.map { t -> seenOf(sym, net, t, now) }
                else (matches[key] ?: coins[key]).let { m -> listOf(Seen(sym, net, diff, m?.time?.takeIf { it > 0 } ?: now, m?.hash, m?.from, m?.to)) }
            }.filter { !exchangeWallet || it.amount.signum() > 0 })

            // Respect the "only alert above" setting; small coin moves (gas fees) are skipped when a minimum is set.
            val min = BigDecimal(store.alertMin)
            // Exchange wallets: money leaving is the exchange sweeping it to its own wallets, not you paying anyone,
            // so only incoming payments alert.
            val exchange = isExchangeWallet(w.address, w.group, w.name)
            val worth = changes.filter { (key, diff) ->
                key !in fees && (!exchange || diff.signum() > 0) &&
                    (if (key.startsWith("USDT|")) diff.abs() >= min else min.signum() == 0)
            }
            if (worth.isEmpty()) continue

            // A tiny coin change next to a real payment (a fee, or dust) is left out.
            val shown = worth.filter { (key, diff) ->
                val sym = key.substringBefore("|")
                worth.size == 1 || sym.uppercase().startsWith("USD") ||
                    (transferUsd(diff.abs(), sym) ?: BigDecimal.ONE) >= BigDecimal("0.01")
            }
            // Each payment as (amount, details): "+700.00 USDT" and "USDT on TON · 23:30" (coins also give the coin
            // amount: "9.2636 TRX on Tron · 19:49"). Only the wallet's saved name is shown, never its address.
            val clock = java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(java.time.ZoneId.systemDefault())
            val payments = shown.map { (key, diff) ->
                val (sym, net) = key.split("|", limit = 2)
                val coin = !sym.uppercase().startsWith("USD")
                val at = matches[key]?.time?.takeIf { it > 0 } ?: now
                ((if (diff.signum() > 0) "+" else "−") + transferValue(diff.abs(), sym)) to
                    ((if (coin) "${fmt(diff.abs())} $sym" else sym) + " " + tr("on") + " $net · " + clock.format(java.time.Instant.ofEpochMilli(at)))
            }
            val balance = store.loadCache(w.address)?.totals?.usd?.let { tr("Balance") + " " + money(it) }
            // Payments at or above the "big payment" amount use a louder channel and say so.
            val bigMin = store.bigPayment
            val big = bigMin > 0 && worth.any { (k, d) -> k.startsWith("USDT|") && d.abs() >= BigDecimal(bigMin) }
            // Several payments to one wallet within 2 minutes: one notification, updated, instead of a stack.
            val nowMs = System.currentTimeMillis()
            val earlier = burst[w.address]?.takeIf { nowMs - it.first < 120_000L }
            val all = (earlier?.second.orEmpty() + payments).takeLast(5)
            val count = (earlier?.third ?: 0) + payments.size
            val start = earlier?.first ?: nowMs
            burst[w.address] = Triple(start, all, count)
            // One payment:  "+700.00 USDT · Clean trust · Ton"  /  "USDT on TON · 23:30" + balance.
            // Several:      "3 payments · Clean trust · BSC"   /  one line each + balance.
            val title = (if (big) tr("Big payment") + " · " else "") +
                (if (count == 1) all.single().first else "$count " + tr("payments")) + " · " + walletLabel(w)
            val text = (if (count == 1) listOf(all.single().second) else all.map { (amount, details) -> "$amount · $details" }) +
                listOfNotNull(balance)
            notify(context, start.toInt() xor w.address.hashCode(), title, text.joinToString("\n"), if (big) BIG_CHANNEL else CHANNEL)
        }
    }
}

/** Standard mode (and safety net for instant mode): runs about every 15 minutes. */
/**
 * Battery saver while charging: checks every 30 seconds for up to 4 minutes (Android may stop a job after 5),
 * then queues the next run, which Android starts again as long as the phone is still plugged in.
 */
class ChargingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = Store(applicationContext)
        if (!store.alertsEnabled || store.instantAlerts) return Result.success()
        val end = System.currentTimeMillis() + 4 * 60_000L
        var rounds = 0
        while (System.currentTimeMillis() < end && !isStopped && Alerts.pluggedIn(applicationContext)) {
            runCatching { Alerts.checkWallets(applicationContext) }
            rounds++
            delay(Alerts.INSTANT_INTERVAL_MS)
        }
        // Still plugged in: carry on in a new run. Unplugged: stop; the 15-minute job restarts it next time it
        // finds the phone plugged in.
        if (rounds > 0 && !isStopped && Alerts.pluggedIn(applicationContext)) {
            Alerts.scheduleCharging(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }
        return Result.success()
    }
}

class BalanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = Store(applicationContext)
        if (store.alertsEnabled && !store.instantAlerts && Alerts.pluggedIn(applicationContext)) {
            Alerts.scheduleCharging(applicationContext, ExistingWorkPolicy.KEEP)
        }
        Alerts.checkWallets(applicationContext)
        return Result.success()
    }
}

/** Instant mode: stays running with a small notification; checks every 30 seconds when charging, 5 minutes otherwise. */
class WatchService : Service() {
    private companion object {
        const val WAKE_MS = 5 * 60_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val store = Store(this)
        if (!store.alertsEnabled || !store.instantAlerts) {
            stopSelf()
            return START_NOT_STICKY
        }
        Alerts.createChannels(this)
        val count = store.loadAddresses().size
        // Android requires a notification while this runs; keep it as quiet as possible: no text, no sound, not on
        // the lock screen, swipe-away. "Hide" opens Android's switch for this one notification (alerts keep working).
        val n = NotificationCompat.Builder(this, Alerts.WATCH_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_bat)
            .setContentTitle("Batwallet")
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(false)
            .setContentIntent(Alerts.openAppIntent(this))
            .addAction(0, tr("Hide"), Alerts.hideWatchIntent(this))
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, 7, n, type)

        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Batwallet:watch")
                .apply { setReferenceCounted(false) }
        }
        if (loop == null) {
            loop = scope.launch {
                while (isActive) {
                    // Instant: every 30 seconds. The wake lock is renewed each round (time-limited, so Android can
                    // always reclaim it) and keeps the checks on time while the screen is off.
                    wakeLock?.acquire(WAKE_MS)
                    runCatching { Alerts.checkWallets(applicationContext) }
                    delay(Alerts.INSTANT_INTERVAL_MS)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }
}

/** "Mark as read" button on payment notifications: simply dismisses that notification. */
class MarkReadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_ID, 0)
        NotificationManagerCompat.from(context).cancel(id)
    }

    companion object {
        const val EXTRA_ID = "id"
    }
}

/** Restarts alerts after the phone reboots or the app is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            runCatching { Alerts.apply(context) }
        }
    }
}

/** Once a day: "Total ₹X · +1.2% today" from the balances the app already keeps. */
class DailySummaryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = Store(applicationContext)
        if (!store.dailySummary || !Alerts.canNotify(applicationContext)) return Result.success()
        fiat = store.currency
        fiatRate = store.currencyRate
        val mine = store.loadAddresses().filter { !it.contact && !it.archived && !it.onExchange }
        val results = mine.flatMap { store.loadCache(it.address)?.results.orEmpty() }
        if (results.isEmpty()) return Result.success()
        val total = mine.mapNotNull { store.loadCache(it.address)?.totals?.usd }.fold(BigDecimal.ZERO, BigDecimal::add)
        val change = runCatching { dayChange(results) }.getOrNull()
        val text = buildString {
            append("Total ").append(if (store.hideBalances) "••••" else money(total))
            if (change != null) {
                val sign = if (change.usd.signum() >= 0) "+" else "−"
                append(" · ").append(sign).append(String.format(java.util.Locale.US, "%.2f", kotlin.math.abs(change.percent))).append("% today")
            }
        }
        Alerts.notify(applicationContext, 9_000_001, "Your daily summary", text, Alerts.SUMMARY_CHANNEL)
        return Result.success()
    }
}