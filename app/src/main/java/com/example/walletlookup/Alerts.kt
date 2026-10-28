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

    /** Warns (at most once a day) when a wallet holds USDT but too little TRX / BNB / ETH to pay the fee to send it. */
    private fun warnLowGas(context: Context, store: Store, w: SavedAddress, fresh: Map<String, BigDecimal>) {
        if (isExchangeWallet(w.address, w.group, w.name)) return // the exchange pays its own fees
        for ((net, sym) in listOf("Tron" to "TRX", "BSC" to "BNB", "Ethereum" to "ETH")) {
            val usdt = fresh["USDT|$net"] ?: continue
            val native = fresh["$sym|$net"] ?: continue
            if (!lowGas(net, native, usdt)) continue
            val key = "${w.address}|$net"
            val now = System.currentTimeMillis()
            if (now - store.gasWarnedAt(key) < 24 * 3_600_000L) continue
            store.setGasWarned(key, now)
            notify(context, key.hashCode(), tr("Low") + " $sym · " + walletLabel(w),
                tr("may not cover the fee to send USDT") + " (${fmt(native)} $sym)")
        }
    }

    /** Compare every saved wallet with its last known balances and notify about any change. */
    suspend fun checkWallets(context: Context) = checking.withLock {
        val store = Store(context)
        if (!store.alertsEnabled) return@withLock
        // Notifications in the app's language, even when the app isn't open.
        I18n.load(context)
        I18n.lang = store.language

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
            warnLowGas(context, store, w, fresh)
            if (old == null) continue // first check: just remember the starting balances

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
            store.addActivity(w.address, changes.map { (key, diff) ->
                val (sym, net) = key.split("|", limit = 2)
                val match = recent.firstOrNull { (n, t) ->
                    n == net && t.symbol.equals(sym, true) && t.incoming == (diff.signum() > 0) &&
                        t.amount.compareTo(diff.abs()) == 0
                }?.second
                Seen(sym, net, diff, match?.time?.takeIf { it > 0 } ?: now, match?.hash, match?.from, match?.to)
            })

            // Respect the "only alert above" setting; small coin moves (gas fees) are skipped when a minimum is set.
            val min = BigDecimal(store.alertMin)
            // Exchange wallets: money leaving is the exchange sweeping it to its own wallets, not you paying anyone,
            // so only incoming payments alert.
            val exchange = isExchangeWallet(w.address, w.group, w.name)
            val worth = changes.filter { (key, diff) ->
                (!exchange || diff.signum() > 0) &&
                    (if (key.startsWith("USDT|")) diff.abs() >= min else min.signum() == 0)
            }
            if (worth.isEmpty()) continue

            val usdt = worth.filter { it.first.startsWith("USDT|") }.fold(BigDecimal.ZERO) { s, c -> s + c.second }
            val received = if (usdt.signum() != 0) usdt.signum() > 0 else worth.any { it.second.signum() > 0 }
            // Only the wallet's saved name is shown, never its address.
            val title = tr(if (received) "Received" else "Sent") + " · " + walletLabel(w)
            val text = worth.joinToString("\n") { (key, diff) ->
                val (sym, net) = key.split("|", limit = 2)
                val sign = if (diff.signum() > 0) "+" else "−"
                val f: (BigDecimal) -> String = if (sym == "USDT") ::fmt2 else ::fmt
                "$sign${f(diff.abs())} $sym · $net · " + tr("balance") + " ${f(fresh[key] ?: BigDecimal.ZERO)} $sym"
            }
            // Payments at or above the "big payment" amount use a louder channel and say so.
            val bigMin = store.bigPayment
            val big = bigMin > 0 && worth.any { (k, d) -> k.startsWith("USDT|") && d.abs() >= BigDecimal(bigMin) }
            notify(
                context, (System.currentTimeMillis() / 1000).toInt() xor w.address.hashCode(),
                if (big) "Big payment · " + title else title, text, if (big) BIG_CHANNEL else CHANNEL,
            )
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
        val n = NotificationCompat.Builder(this, Alerts.WATCH_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_bat)
            .setContentTitle("Instant alerts on")
            .setContentText("Watching $count wallet${if (count == 1) "" else "s"} for payments")
            .setOngoing(true)
            .setContentIntent(Alerts.openAppIntent(this))
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