package com.example.walletlookup

import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.Menu
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import android.Manifest
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.TextStyle
import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.snapshotFlow
import java.math.BigDecimal
import java.util.Locale
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.alpha

private const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

// Quiet Batman palette: pure black (OLED) or white with grey accents only — nothing bright.
private val BatBlack = ComposeColor(0xFF000000)

/** Inter: the clean, modern sans-serif style used by wallets like Trust Wallet. */
private val Inter = FontFamily(
    Font(R.font.inter_400, FontWeight.Normal),
    Font(R.font.inter_500, FontWeight.Medium),
    Font(R.font.inter_600, FontWeight.SemiBold),
    Font(R.font.inter_700, FontWeight.Bold),
)

private val BatType = Typography().run {
    fun TextStyle.inter() = copy(fontFamily = Inter)
    copy(
        displayLarge = displayLarge.inter(), displayMedium = displayMedium.inter(), displaySmall = displaySmall.inter(),
        headlineLarge = headlineLarge.inter(), headlineMedium = headlineMedium.inter(), headlineSmall = headlineSmall.inter(),
        titleLarge = titleLarge.inter(), titleMedium = titleMedium.inter(), titleSmall = titleSmall.inter(),
        bodyLarge = bodyLarge.inter(), bodyMedium = bodyMedium.inter(), bodySmall = bodySmall.inter(),
        labelLarge = labelLarge.inter(), labelMedium = labelMedium.inter(), labelSmall = labelSmall.inter(),
    )
}

private val DarkBat: ColorScheme = darkColorScheme(
    primary = ComposeColor(0xFFCFCFCF), onPrimary = BatBlack,
    primaryContainer = ComposeColor(0xFF1E1E1E), onPrimaryContainer = ComposeColor(0xFFE6E6E6),
    secondary = ComposeColor(0xFFF2F2F2), onSecondary = BatBlack,
    tertiary = ComposeColor(0xFF9E9E9E),
    background = BatBlack, onBackground = ComposeColor(0xFFEDEDED),
    surface = BatBlack, onSurface = ComposeColor(0xFFEDEDED),
    surfaceContainerLowest = BatBlack, surfaceContainerLow = ComposeColor(0xFF080808),
    surfaceContainer = ComposeColor(0xFF0A0A0A), surfaceContainerHigh = ComposeColor(0xFF121212),
    surfaceContainerHighest = ComposeColor(0xFF141414),
    surfaceVariant = ComposeColor(0xFF1C1C1C), onSurfaceVariant = ComposeColor(0xFF9A9A9A),
    secondaryContainer = ComposeColor(0xFF1A1A1A), onSecondaryContainer = ComposeColor(0xFFE6E6E6),
    outline = ComposeColor(0xFF333333), outlineVariant = ComposeColor(0xFF222222),
    error = ComposeColor(0xFFD9796F),
)
private val LightBat: ColorScheme = lightColorScheme(
    primary = ComposeColor(0xFF3A3A3A), onPrimary = ComposeColor.White,
    primaryContainer = ComposeColor(0xFFEDEDED), onPrimaryContainer = ComposeColor(0xFF1A1A1A),
    secondary = ComposeColor(0xFF111111), onSecondary = ComposeColor.White,
    tertiary = ComposeColor(0xFF6B6B6B),
    background = ComposeColor.White, onBackground = ComposeColor(0xFF111111),
    surface = ComposeColor.White, onSurface = ComposeColor(0xFF111111),
    surfaceContainerLowest = ComposeColor.White, surfaceContainerLow = ComposeColor(0xFFFAFAFA),
    surfaceContainer = ComposeColor(0xFFF5F5F5), surfaceContainerHigh = ComposeColor(0xFFF1F1F1),
    surfaceContainerHighest = ComposeColor(0xFFEFEFEF),
    surfaceVariant = ComposeColor(0xFFEBEBEB), onSurfaceVariant = ComposeColor(0xFF616161),
    secondaryContainer = ComposeColor(0xFFF2F2F2), onSecondaryContainer = ComposeColor(0xFF111111),
    error = ComposeColor(0xFFB3483D),
)

class MainActivity : FragmentActivity() {
    private lateinit var store: Store
    private var locked by mutableStateOf(false)
    private var themeMode by mutableStateOf(ThemeMode.DARK)
    private var lockEnabled by mutableStateOf(true)
    private var lockTimeout by mutableIntStateOf(0)
    private var shared by mutableStateOf<String?>(null)
    private var authInProgress = false
    private var backgroundAt = 0L
    private var screenWentOff = false
    private var receiverRegistered = false
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (lockEnabled && store.lockOnScreenOff) screenWentOff = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        shared = sharedText(intent)
        I18n.load(this)
        historyPrefs = getSharedPreferences("history", MODE_PRIVATE)
        I18n.lang = store.language
        themeMode = store.theme
        lockEnabled = store.lockEnabled
        lockTimeout = store.lockTimeoutSec
        store.switchToUsdtOnce()
        store.dropChartData()
        fiat = store.currency
        fiatRate = store.currencyRate
        // The lock always covers the whole app (locking only Wallets left the total visible on Home).
        locked = lockEnabled && canAuthenticate()
        lifecycleScope.launch { runCatching { weeklyUpdateCheck(applicationContext) } }
        SelfUpdate.cleanUp(this)
        priceSource = PriceSource.AUTO // always automatic: CoinMarketCap first, then the backups
        etherscanKey = store.etherscanKey
        // Background alerts and the screen-off lock are extras: if either fails, the app still opens.
        runCatching { Alerts.apply(this) }
        receiverRegistered = runCatching {
            ContextCompat.registerReceiver(
                this, screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.isSuccess

        setContent {
            val dark = when (themeMode) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            DisposableEffect(dark) {
                val bars = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                onDispose {}
            }
            MaterialTheme(colorScheme = if (dark) DarkBat else LightBat, typography = BatType) {
                // The app stays alive under the lock screen, so unlocking returns to the exact page you were on
                // (a history, a transaction…) instead of starting again from Home.
                Box(Modifier.fillMaxSize()) {
                    App(
                        store = store,
                        dark = dark,
                        themeMode = themeMode,
                        onThemeChange = { themeMode = it; store.theme = it },
                        lockAvailable = canAuthenticate(),
                        lockEnabled = lockEnabled,
                        onLockChange = { lockEnabled = it; store.lockEnabled = it },
                        lockTimeout = lockTimeout,
                        onLockTimeoutChange = { lockTimeout = it; store.lockTimeoutSec = it },
                        confirm = ::confirm,
                        shared = shared,
                        locked = locked,
                        onSharedHandled = { shared = null },
                    )
                    if (locked) LockScreen(onUnlock = ::authenticate)
                }
            }
        }
    }

    /** "Share → Batwallet" from Telegram, a browser or any app delivers the shared text here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedText(intent)?.let { shared = it }
    }

    private fun sharedText(i: Intent?): String? =
        if (i?.action == Intent.ACTION_SEND) i.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() } else null

    override fun onDestroy() {
        if (receiverRegistered) runCatching { unregisterReceiver(screenOffReceiver) }
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        val relock = (screenWentOff && canAuthenticate()) ||
            // Lock again if the app was in the background longer than the chosen auto-lock time.
            (backgroundAt > 0 && lockEnabled && canAuthenticate() &&
                SystemClock.elapsedRealtime() - backgroundAt >= lockTimeout * 1000L)
        screenWentOff = false
        if (relock) locked = true
        backgroundAt = 0
    }

    override fun onResume() {
        super.onResume()
        if (locked && !authInProgress) authenticate()
    }

    override fun onStop() {
        super.onStop()
        // Remember when the app left the screen (but not while the fingerprint/PIN screen is showing).
        if (!authInProgress && !isChangingConfigurations) backgroundAt = SystemClock.elapsedRealtime()
    }

    /** Asks for the fingerprint (when the lock is on) before [onOk], e.g. to reveal hidden balances. */
    private fun confirm(title: String, onOk: () -> Unit) {
        if (!lockEnabled || !canAuthenticate()) { onOk(); return }
        authInProgress = true
        BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                authInProgress = false
                onOk()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                authInProgress = false
            }
        }).authenticate(
            BiometricPrompt.PromptInfo.Builder().setTitle(title).setAllowedAuthenticators(AUTHENTICATORS).build()
        )
    }

    private fun canAuthenticate() =
        BiometricManager.from(this).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    private fun authenticate() {
        if (!canAuthenticate()) { locked = false; return }
        authInProgress = true
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    authInProgress = false
                    locked = false
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    authInProgress = false
                }
            })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock")
                .setSubtitle("Use your fingerprint (or phone PIN)")
                .setAllowedAuthenticators(AUTHENTICATORS)
                .build()
        )
    }
}

/** The app icon (logo on its night-blue tile). */
@Composable
private fun BatLogo(size: Dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(BatBlack),
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(R.drawable.ic_bat), contentDescription = null, modifier = Modifier.fillMaxSize().scale(1.45f))
    }
}

@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    // Covers the app completely; Back does nothing here (it must not reach the pages underneath).
    BackHandler {}
    Surface(
        Modifier.fillMaxSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
    ) {
        // Logo sits in the upper part of the screen so the system fingerprint sheet never covers it.
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(0.5f))
            BatLogo(88.dp)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onUnlock) { Text("Unlock") }
        }
    }
}

/** Networks where the wallet has funds or activity (null = unknown, check all). */
/**
 * Recent payments, newest first, at most 30. The same payment from history and from an alert is kept once: by
 * transaction ID when both have one, else same wallet, direction, coin and amount within an hour (the history one wins).
 */
internal fun mergeRecent(all: List<Triple<String, String, Transfer>>): List<Triple<String, String, Transfer>> {
    val unique = mutableListOf<Triple<String, String, Transfer>>()
    for (x in all.sortedByDescending { it.third.txUrl != null }) {
        val t = x.third
        val dup = unique.any { (w, _, u) ->
            w == x.first && u.incoming == t.incoming && u.symbol == t.symbol &&
                (if (t.hash != null && u.hash != null) u.hash.equals(t.hash, true)
                else u.amount.compareTo(t.amount) == 0 && kotlin.math.abs(u.time - t.time) < 3_600_000)
        }
        if (!dup) unique += x
    }
    return unique.sortedByDescending { it.third.time }.take(30)
}

private fun activeNetworks(results: List<ChainResult>): Set<String>? =
    results.filter { it.active && it.error == null }.map { it.network }.toSet().ifEmpty { null }

/** True while balances are hidden (Settings or the eye on the total card). */
val LocalHidden = staticCompositionLocalOf { false }

@Composable
fun hideable(text: String): String = if (LocalHidden.current) text.replace(Regex("[0-9]"), "*") else text

private const val TAB_HOME = 0
private const val TAB_WALLETS = 1
private const val TAB_SETTINGS = 2

/** "just now", "5 min ago", "3 h ago" or a date. */
private fun ago(time: Long): String {
    val min = (System.currentTimeMillis() - time) / 60_000
    return when {
        min < 1 -> tr("just now")
        min < 60 -> "$min " + tr("min ago")
        min < 24 * 60 -> "${min / 60} " + tr("h ago")
        else -> date(time / 1000)
    }
}


@Composable
private fun App(
    store: Store,
    dark: Boolean,
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    lockAvailable: Boolean,
    lockEnabled: Boolean,
    onLockChange: (Boolean) -> Unit,
    lockTimeout: Int,
    onLockTimeoutChange: (Int) -> Unit,
    confirm: (String, () -> Unit) -> Unit,
    /** Text shared to Batwallet from another app (Telegram, a browser…), waiting to be checked. */
    shared: String?,
    /** True while the lock screen covers the app: no dialogs may pop up over it. */
    locked: Boolean,
    onSharedHandled: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(TAB_HOME) }
    // Which of Wallets (0) / Contacts (1) the Wallets tab shows.
    var walletsPage by rememberSaveable { mutableIntStateOf(0) }
    var address by rememberSaveable { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<ChainResult>>(emptyList()) }
    var resultsTime by remember { mutableStateOf<Long?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(store.loadAddresses()) }
    // Last synced balances of saved wallets, kept on the phone so they show instantly.
    var cache by remember {
        mutableStateOf(saved.mapNotNull { w -> store.loadCache(w.address)?.let { w.address to it } }.toMap())
    }
    var syncing by remember { mutableStateOf(setOf<String>()) }
    var failed by remember { mutableStateOf(setOf<String>()) }
    var syncJob by remember { mutableStateOf<Job?>(null) }
    var safetyResult by remember { mutableStateOf<Safety?>(null) }
    var safetyLoading by remember { mutableStateOf(false) }
    var homeHistory by remember { mutableStateOf<List<History>?>(null) }
    var historyFor by remember { mutableStateOf<String?>(null) }
    var hidden by remember { mutableStateOf(store.hideBalances) }
    var scanning by remember { mutableStateOf(false) }
    var receiveFor by remember { mutableStateOf<SavedAddress?>(null) }
    var fromWallets by rememberSaveable { mutableStateOf(false) }
    var recent by remember { mutableStateOf(store.loadRecentTimed()) }
    // Several addresses found in pasted/shared/scanned text (pick one), a transaction to look up, and the tools.
    var choices by remember { mutableStateOf<List<String>?>(null) }
    var choicesFromPhoto by remember { mutableStateOf(false) }
    var choicesTx by remember { mutableStateOf<String?>(null) }
    var txCheckFor by remember { mutableStateOf<String?>(null) }
    var txInfo by remember { mutableStateOf<TxInfo?>(null) }
    var comparing by remember { mutableStateOf(false) }
    var sendChecking by remember { mutableStateOf(false) }
    var walletSort by remember { mutableStateOf(store.walletSort) }
    // Addresses you came from while following money through transactions (Back walks back along it).
    var trail by remember { mutableStateOf(listOf<String>()) }
    // A 0x address waiting for you to pick its network.
    var pendingEvm by remember { mutableStateOf<String?>(null) }
    var groupOrder by remember { mutableStateOf(store.groupOrder) }
    // Latest transfers across your wallets (shown on Home), and the one opened from there.
    // Up to 30 latest payments across your wallets (Home shows 5; "See all" the rest).
    var recentTx by remember { mutableStateOf(store.loadRecentTx()) }
    // Pull-to-refresh on Home (and a payment arriving) bumps this to collect recent transactions again.
    var recentForce by remember { mutableIntStateOf(0) }
    var hideSmall by remember { mutableStateOf(store.hideSmall) }
    var homeTx by remember { mutableStateOf<Pair<String, Transfer>?>(null) }
    // Saved names by address, for history and transaction rows (built once per change, not every frame).
    val addressNames = remember(saved) { nameLabels(saved) }
    androidx.compose.runtime.SideEffect { lookalikeSource = saved }

    // Keep the exchange rate for the chosen currency fresh.
    LaunchedEffect(fiat) {
        val rate = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { fetchFiatRate(fiat.code) }
        if (rate != null) { fiatRate = rate; store.currencyRate = rate }
    }

    /** 0x addresses are case-insensitive: use the saved spelling, so a wallet opened from anywhere is "yours". */
    fun canonical(a: String): String =
        if (rawAddress(a).startsWith("0x")) saved.firstOrNull { it.address.equals(a, ignoreCase = true) }?.address ?: a else a

    /** Open an address on Home. A bare 0x address first asks which network it is on. */
    fun openAddress(a: String) {
        if (a.isNotEmpty() && detect(a) == AddrType.EVM && netOf(a) == null) pendingEvm = a else address = canonical(a)
    }
    /**
     * Pasted, shared or scanned text: one address opens straight away (from a photo you confirm it first),
     * several let you pick, and a transaction ID is looked up. Returns false when nothing usable was found.
     */
    fun handleText(text: String, fromPhoto: Boolean): Boolean {
        val found = extractAddresses(text)
        // 0x + 64 characters is both an Aptos address and an EVM transaction ID: let you choose.
        val maybeTx = found.singleOrNull()?.let(::rawAddress)?.takeIf { Regex("^0x[0-9a-fA-F]{64}$").matches(it) }
        choicesTx = maybeTx
        when {
            found.size == 1 && !fromPhoto && maybeTx == null -> openAddress(found[0])
            found.isNotEmpty() -> { choices = found; choicesFromPhoto = fromPhoto }
            else -> {
                val hash = text.split(Regex("[\\s,;]+")).firstOrNull { looksLikeTxHash(it) } ?: return false
                txCheckFor = hash
            }
        }
        return true
    }
    LaunchedEffect(shared) {
        val text = shared ?: return@LaunchedEffect
        onSharedHandled()
        // Full-screen pages would cover the result, so shared text always lands on Home.
        scanning = false; txInfo = null; homeTx = null; historyFor = null
        tab = TAB_HOME
        if (!handleText(text, fromPhoto = false)) {
            android.widget.Toast.makeText(context, tr("No wallet address found"), android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // Payments detected by alerts across your own wallets, newest first.
    val activity = remember(cache, saved) {
        saved.filter { !it.contact && !it.archived }
            .flatMap { w -> store.loadActivity(w.address).map { w to it } }
            .sortedByDescending { it.second.time }
            .take(5)
    }
    var todayChange by remember { mutableStateOf<DayChange?>(null) }
    var tokens by remember { mutableStateOf<List<TokenBalance>?>(null) }
    var tokensFor by remember { mutableStateOf<String?>(null) }

    fun remember(addr: String, fresh: List<ChainResult>): List<ChainResult> {
        // Networks that failed this time keep their last good result.
        val old = cache[addr]?.results.orEmpty().associateBy { it.chain }
        val merged = fresh.map { r -> if (r.error != null) old[r.chain] ?: r else r }
        if (merged.none { it.error != null } && saved.any { it.address == addr }) {
            val c = Cached(merged, System.currentTimeMillis())
            cache = cache + (addr to c)
            store.saveCache(addr, c)
        }
        return merged
    }

    suspend fun sync(addr: String) {
        val type = detect(addr) ?: return
        syncing = syncing + addr
        try {
            val merged = remember(addr, lookup(addr, type))
            failed = if (merged.any { it.error != null }) failed + addr else failed - addr
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = failed + addr
        } finally {
            syncing = syncing - addr
        }
    }

    /** Refresh every saved wallet, one at a time (gentle on the free APIs). */
    fun syncAll() {
        if (syncJob?.isActive == true) return
        // Your wallets first, then contacts (their balances are shown but never counted in your total).
        // Three at a time: much faster than one by one, still gentle on the free services.
        syncJob = scope.launch {
            val gate = kotlinx.coroutines.sync.Semaphore(3)
            suspend fun all(list: List<SavedAddress>) = kotlinx.coroutines.coroutineScope {
                list.forEach { w -> launch { gate.acquire(); try { sync(w.address) } finally { gate.release() } } }
            }
            all(saved.filter { !it.contact && !it.archived })
            all(saved.filter { it.contact && !it.archived })
        }
    }

    fun updateSaved(list: List<SavedAddress>) { saved = list; store.saveAddresses(list) }

    /** Edit a saved wallet in place. If its network changed, its saved balance starts fresh. */
    fun replaceWallet(old: SavedAddress, new: SavedAddress) {
        updateSaved(saved.map { if (it.address == old.address) new.copy(pinned = old.pinned) else it })
        if (new.address != old.address) {
            store.forget(old.address)
            cache = cache - old.address
            if (!new.contact) scope.launch { sync(new.address) }
        }
    }
    /** Saves a wallet with everything from the dialog (name, folder, note, …). Editing keeps its place and pin. */
    fun save(w: SavedAddress) {
        val addr = w.address
        val isNew = saved.none { it.address == addr }
        updateSaved(
            if (isNew) saved + w
            else saved.map { if (it.address == addr) w.copy(pinned = it.pinned) else it }
        )
        if (isNew && !w.contact) {
            // Keep what's on screen as its first saved balance, or fetch it.
            if (addr == address && results.isNotEmpty() && results.none { it.error != null }) remember(addr, results)
            else scope.launch { sync(addr) }
            // Record starting balances right away so the first payment after saving is noticed.
            if (store.alertsEnabled) Alerts.checkNow(context)
        }
    }

    // Runs every time the app opens or is unlocked (App only exists while unlocked).
    LaunchedEffect(Unit) { syncAll() }

    // Recent transactions for Home: every wallet asked at the same time (4 at once, 20 s each at most), and each
    // wallet's newest payments slot in as soon as they arrive. It runs on its own schedule (app opened, pull down,
    // a payment, every 3 minutes while open, wallets added or removed), and a new request never cancels one that's
    // running: it just runs once more afterwards. (Before, every balance refresh restarted it, so with frequent
    // alerts it rarely finished and Home kept showing old payments.)
    val recentRequests = remember { kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED) }
    LaunchedEffect(Unit) {
        while (true) { recentRequests.trySend(Unit); delay(3 * 60_000L) }
    }
    LaunchedEffect(saved.count { !it.contact && !it.archived }, recentForce) { recentRequests.trySend(Unit) }
    LaunchedEffect(Unit) {
        for (request in recentRequests) {
            // An exchange's own wallet (a known public one) moves other people's money all day: keep it off Home.
            // Your deposit addresses in an exchange folder still show.
            val mine = store.loadAddresses().filter { !it.contact && !it.archived && knownPlatform(it.address) == null }
            val keep = mine.map { it.address }.toSet()
            recentTx = recentTx.filter { it.first in keep }
            if (mine.isEmpty()) { store.saveRecentTx(recentTx); continue }
            /** This wallet's payments for Home: its history plus what the alerts saw (exchange sends and dust left out). */
            fun entries(w: SavedAddress, hist: List<History>): List<Triple<String, String, Transfer>> {
                val out = mutableListOf<Triple<String, String, Transfer>>()
                hist.forEach { h -> h.items.filter { it.incoming || !w.onExchange }.forEach { out += Triple(w.address, h.network, it) } }
                store.loadActivity(w.address).filter { (it.amount.signum() > 0 || !w.onExchange) && !isCoinDust(it.amount, it.symbol) }
                    .forEach { s ->
                        val incoming = s.amount.signum() > 0
                        out += Triple(w.address, s.network, Transfer(
                            incoming, s.amount.abs(), if (incoming) s.from else s.to, s.time, null, s.hash, s.from, s.to, s.symbol,
                        ))
                    }
                return out
            }
            /**
             * This wallet's fresh rows, merged with the rest (duplicates once, newest first, 30 kept for "See all").
             * [replace]: its history answered, so its old rows go even if nothing is left to show; when it didn't
             * answer, the old rows stay and only what the alerts saw is added.
             */
            fun merge(address: String, fresh: List<Triple<String, String, Transfer>>, replace: Boolean) {
                recentTx = mergeRecent((if (replace) recentTx.filter { it.first != address } else recentTx) + fresh)
                store.saveRecentTx(recentTx)
            }
            val gate = kotlinx.coroutines.sync.Semaphore(4)
            kotlinx.coroutines.coroutineScope {
                mine.forEach { w ->
                    launch {
                        gate.acquire()
                        try {
                            val hist = withTimeoutOrNull(20_000) {
                                runCatching { history(w.address, activeNetworks(cache[w.address]?.results.orEmpty()), limit = 10) }.getOrNull()
                            }
                            merge(w.address, entries(w, hist.orEmpty()), replace = hist != null)
                            // Payments the alerts saw without details: look for them now, then show what was found.
                            runCatching { Alerts.resolveMissing(context, w) }
                            merge(w.address, entries(w, hist.orEmpty()), replace = hist != null)
                        } finally {
                            gate.release()
                        }
                    }
                }
            }
        }
    }

    // Today's change of the saved-wallets total, recalculated whenever balances update.
    LaunchedEffect(cache, saved, syncing.isEmpty()) {
        todayChange = try {
            dayChange(saved.filter { !it.contact && !it.archived && !it.onExchange }.flatMap { cache[it.address]?.results.orEmpty() })
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    // A payment alert updates the saved balances in the background; show them right away.
    DisposableEffect(Unit) {
        val listener = store.onCacheChanged { addr ->
            store.loadCache(addr)?.let { c ->
                cache = cache + (addr to c)
                if (addr == address && !loading) {
                    results = c.results
                    resultsTime = c.time
                }
            }
        }
        onDispose { store.stopListening(listener) }
    }
    // While Batwallet is open, check for payments every 20 seconds whatever the alert mode, so they show up (and
    // notify) within seconds instead of after a pull-down. Only while it's on screen; background checks are the
    // alert mode's job. With alerts off it refreshes balances every minute instead.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        var rounds = 0
        while (true) {
            delay(20_000)
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) continue
            if (store.alertsEnabled) {
                runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Alerts.checkWallets(context) } }
            } else if (++rounds % 3 == 0 && syncing.isEmpty()) {
                syncAll()
            }
        }
    }
    // A payment came in (alerts recorded it): refresh every wallet's balance and Home's recent transactions,
    // not just that one wallet's balance. At most every 20 seconds, so a burst of payments is one refresh.
    DisposableEffect(Unit) {
        var last = 0L
        val listener = store.onActivityChanged { addr ->
            // Straight onto Home from what the alert saved (the full refresh below can take a while).
            store.loadAddresses().firstOrNull { it.address == addr && !it.contact && !it.archived && knownPlatform(it.address) == null }?.let { w ->
                val fresh = store.loadActivity(addr).filter { (it.amount.signum() > 0 || !w.onExchange) && !isCoinDust(it.amount, it.symbol) }.take(5).map { s ->
                    val incoming = s.amount.signum() > 0
                    Triple(addr, s.network, Transfer(incoming, s.amount.abs(), if (incoming) s.from else s.to, s.time, null,
                        s.hash, s.from, s.to, s.symbol))
                }
                recentTx = mergeRecent(fresh + recentTx)
                store.saveRecentTx(recentTx)
            }
            val now = System.currentTimeMillis()
            if (now - last > 20_000) {
                last = now
                recentForce++
                syncAll()
            }
        }
        onDispose { store.stopListening(listener) }
    }

    // Home: show the last saved result for this address at once, then fetch a fresh one.
    LaunchedEffect(address, refresh) {
        message = null
        val t = detect(address) ?: run {
            results = emptyList()
            resultsTime = null
            if (address.isNotBlank()) message = "Not a supported address. Check that it was copied completely."
            return@LaunchedEffect
        }
        val cached = cache[address]
        if (refresh == 0 || results.isEmpty() || cached != null) {
            results = cached?.results.orEmpty()
            resultsTime = cached?.time
        }
        if (cached == null) delay(400) // debounce typing
        loading = true
        try {
            val merged = remember(address, lookup(address, t))
            results = merged
            if (merged.none { it.error != null }) resultsTime = System.currentTimeMillis()
            // Remember addresses you checked that aren't saved, for "Recently checked" on Home.
            if (saved.none { it.address == address } && merged.any { it.error == null }) {
                store.addRecent(address)
                recent = store.loadRecentTimed()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            message = if (results.isEmpty()) "Lookup failed: ${e.message}" else "Couldn't refresh. Showing last saved balances."
        } finally {
            loading = false
        }
    }

    // Home: other tokens for the searched address, once its balances are known.
    LaunchedEffect(address, loading) {
        if (tokensFor != address) { tokens = null; tokensFor = null }
        if (loading || results.isEmpty() || detect(address) == null || tokensFor == address) return@LaunchedEffect
        tokens = try {
            otherTokens(address, results)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        tokensFor = address
    }

    // Home: safety check + recent history for the searched address. Runs once per address and waits for the
    // balances (cached or fresh) so history only asks the networks where the wallet is used. It is keyed on the
    // address alone so a balance refresh finishing can't interrupt it halfway.
    LaunchedEffect(address) {
        safetyResult = null
        homeHistory = null
        if (detect(address) == null) {
            safetyLoading = false
            return@LaunchedEffect
        }
        safetyLoading = true
        try {
            withTimeoutOrNull(60_000) { snapshotFlow { results.isNotEmpty() || (!loading && message != null) }.first { it } }
            if (results.isNotEmpty()) {
                val h = history(address, activeNetworks(results))
                homeHistory = h
                safetyResult = safety(address, h)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        } finally {
            safetyLoading = false
        }
    }

    if (scanning) {
        ScannerScreen(
            onResult = { scanning = false; if (!handleText(it, fromPhoto = false)) openAddress(cleanAddress(it)) },
            onClose = { scanning = false },
        )
        return
    }
    txInfo?.let { info ->
        SlideIn {
            TransactionScreen(
                info.network, info.transfer, addressNames,
                onBack = { txInfo = null },
                onOpenAddress = { a ->
                    if (address.isNotEmpty()) trail = trail + address
                    address = canonical(if (detect(a) == AddrType.EVM) withNet(a, info.network) else a)
                    txInfo = null
                    tab = TAB_HOME
                },
                onSaveContact = { a, n -> save(SavedAddress(n, if (detect(a) == AddrType.EVM) withNet(a, info.network) else a, contact = true)) },
                neutral = true,
                status = info.status,
            )
        }
        return
    }
    homeTx?.let { (net, t) ->
        SlideIn {
        TransactionScreen(
            net, t, addressNames,
            onBack = { homeTx = null },
            onOpenAddress = { a ->
                if (address.isNotEmpty()) trail = trail + address
                address = canonical(if (detect(a) == AddrType.EVM) withNet(a, net) else a)
                homeTx = null
                tab = TAB_HOME
            },
            onSaveContact = { a, n -> save(SavedAddress(n, if (detect(a) == AddrType.EVM) withNet(a, net) else a, contact = true)) },
        )
        }
        return
    }
    historyFor?.let { addr ->
        SlideIn {
        HistoryScreen(
            address = addr,
            name = (saved.firstOrNull { it.address == addr }?.let(::walletLabel) ?: addressNames.labelFor(addr, netOf(addr))),
            preloaded = if (addr == address) homeHistory else null,
            networks = activeNetworks(cache[addr]?.results ?: if (addr == address) results else emptyList()),
            names = addressNames,
            balances = cache[addr]?.results ?: if (addr == address) results else emptyList(),
            seen = remember(addr, cache) { store.loadActivity(addr) },
            onClose = { historyFor = null },
            onSaveContact = { a, net, n -> save(SavedAddress(n, if (detect(a) == AddrType.EVM) withNet(a, net) else a, contact = true)) },
            onOpenAddress = { a, net ->
                // Follow the money: open the other side of the transfer, remembering where we came from.
                if (address.isNotEmpty()) trail = trail + address
                address = canonical(if (detect(a) == AddrType.EVM) withNet(a, net) else a)
                historyFor = null
                tab = TAB_HOME
            },
        )
        }
        return
    }

    // A wallet opened from the Wallets tab is shown as a page on top of it; Back returns there.
    fun closeDetail() {
        if (trail.isNotEmpty()) {
            address = trail.last()
            trail = trail.dropLast(1)
            return
        }
        if (fromWallets) tab = TAB_WALLETS
        fromWallets = false
        address = ""
    }
    BackHandler(enabled = tab != TAB_HOME) { tab = TAB_HOME }
    // On Home, Back first clears a checked address instead of closing the app.
    BackHandler(enabled = tab == TAB_HOME && address.isNotEmpty()) { closeDetail() }

    val groups = saved.map { it.group }.filter { it.isNotBlank() }.distinct().sorted()
    // Tap a total (Home or Wallets) to hide amounts; showing them again asks for the fingerprint.
    val toggleHidden: () -> Unit = {
        if (hidden) confirm("Show balances") { hidden = false; store.hideBalances = false }
        else { hidden = true; store.hideBalances = true }
    }

    CompositionLocalProvider(LocalHidden provides hidden) {
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == TAB_HOME && !fromWallets, onClick = {
                    if (fromWallets) { fromWallets = false; address = "" }
                    tab = TAB_HOME
                },
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) }, label = { Text("Home") })
                NavigationBarItem(selected = tab == TAB_WALLETS || fromWallets, onClick = {
                    if (fromWallets) { fromWallets = false; address = "" }
                    tab = TAB_WALLETS
                },
                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) }, label = { Text("Wallets") })
                NavigationBarItem(selected = tab == TAB_SETTINGS, onClick = { tab = TAB_SETTINGS },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) }, label = { Text("Settings") })
            }
        },
    ) { pad ->
        // Swipe left/right between Home, Wallets and Settings (the bottom bar and the pages stay in step).
        // Four pages in one row: Home, Wallets, Contacts, Settings. Every swipe moves exactly one page (Wallets and
        // Contacts used to be a pager inside this one, and handing a swipe from one to the other made it stutter).
        fun pageOf(t: Int) = when (t) { TAB_HOME -> 0; TAB_WALLETS -> 1 + walletsPage; else -> 3 }
        val pager = rememberPagerState(initialPage = pageOf(tab)) { 4 }
        LaunchedEffect(tab, walletsPage) {
            val target = pageOf(tab)
            if (pager.currentPage != target) pager.animateScrollToPage(target)
        }
        LaunchedEffect(pager) {
            snapshotFlow { pager.settledPage }.collect { p ->
                if (p == 1 || p == 2) walletsPage = p - 1
                val t = when (p) { 0 -> TAB_HOME; 1, 2 -> TAB_WALLETS; else -> TAB_SETTINGS }
                if (t != tab) {
                    if (fromWallets) { fromWallets = false; address = "" }
                    tab = t
                }
            }
        }
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize().padding(pad),
            // While a wallet page is open, swiping is off so it can't be left half-way.
            userScrollEnabled = address.isEmpty(),
            beyondViewportPageCount = 1,
        ) { page ->
        Column(Modifier.fillMaxSize()) {
            if (page == 3) {
                Text(
                    "Settings",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 4.dp),
                )
            }
            when (page) {
                0 -> HomeTab(
                    onOpenUpdate = { SelfUpdate.openInSettings = true; tab = TAB_SETTINGS },
                    saved = saved,
                    cache = cache,
                    syncing = syncing,
                    address = address,
                    onAddress = { openAddress(it) },
                    loading = loading,
                    message = message,
                    results = results,
                    resultsTime = resultsTime,
                    groups = groups,
                    onRefresh = { if (detect(address) != null) refresh++; recentForce++; syncAll() },
                    onSave = { w ->
                        val existing = saved.firstOrNull { it.address == address }
                        if (existing != null && w.address != existing.address) { replaceWallet(existing, w); address = w.address }
                        else if (existing != null) replaceWallet(existing, w)
                        else { save(w); address = w.address }
                    },
                    safety = safetyResult,
                    safetyLoading = safetyLoading,
                    onHistory = { historyFor = address },
                    onToggleHidden = toggleHidden,
                    onScan = { scanning = true },
                    onReceive = { receiveFor = it },
                    dayChange = todayChange,
                    tokens = tokens,
                    detail = address.isNotEmpty(),
                    backLabel = if (trail.isNotEmpty()) "← Back" else if (fromWallets) "← Wallets" else "← Home",
                    onBack = { closeDetail() },
                    recent = recent.filter { r -> saved.none { it.address == r.first } },
                    onPaste = { handleText(it, fromPhoto = false) },
                    onCheckTx = { txCheckFor = "" },
                    onCompare = { comparing = true },
                    onSendCheck = { sendChecking = true },
                    activity = activity,
                    onOpen = { address = it },
                    onSeeAll = { tab = TAB_WALLETS },
                    platform = remember(address, homeHistory) { platformOf(address, homeHistory) },
                    // Also applied to the saved list, so sends from exchange wallets disappear straight away.
                    recentTx = recentTx.filter { (w, _, t) ->
                        (t.incoming || saved.firstOrNull { it.address == w }?.onExchange != true) && !isCoinDust(t.amount, t.symbol)
                    },
                    onOpenTx = { net, t -> homeTx = net to t },
                    onClearRecent = { store.clearRecent(); recent = emptyList() },
                )
                1, 2 -> WalletsTab(
                    subPage = page - 1,
                    onSubPage = { walletsPage = it },
                    // Between Wallets and Contacts only the list slides; the header stays put (see WalletsTab).
                    headerShift = {
                        val o = (pager.currentPage - page) + pager.currentPageOffsetFraction
                        if (page == 1) o.coerceIn(0f, 1f) else o.coerceIn(-1f, 0f)
                    },
                    saved = saved,
                    cache = cache,
                    syncing = syncing,
                    failed = failed,
                    onRefreshAll = { syncAll() },
                    onPick = { address = it.address; fromWallets = true; tab = TAB_HOME },
                    onHistory = { historyFor = it.address },
                    onReceive = { receiveFor = it },
                    onSave = { w ->
                        save(w)
                    },
                    sort = walletSort,
                    onSort = { walletSort = it; store.walletSort = it },
                    onPin = { item -> updateSaved(saved.map { if (it.address == item.address) it.copy(pinned = !it.pinned) else it }) },
                    onSwap = { a, b ->
                        // Dragging a wallet past another swaps their places.
                        val m = saved.toMutableList()
                        val i = m.indexOfFirst { it.address == a }
                        val j = m.indexOfFirst { it.address == b }
                        if (i >= 0 && j >= 0) { val x = m[i]; m[i] = m[j]; m[j] = x; updateSaved(m) }
                    },
                    groupOrder = groupOrder,
                    onEdit = { old, new -> replaceWallet(old, new) },
                    collapsedInit = store.collapsedGroups,
                    onCollapsedChange = { store.collapsedGroups = it },
                    onSwapGroups = { a, b, current ->
                        // Dragging a folder switches to your own order, starting from what is on screen.
                        val list = current.toMutableList()
                        val i = list.indexOf(a)
                        val j = list.indexOf(b)
                        if (i >= 0 && j >= 0) {
                            list[i] = b; list[j] = a
                            groupOrder = list; store.groupOrder = list
                            walletSort = WalletSort.CUSTOM; store.walletSort = WalletSort.CUSTOM
                        }
                    },
                    hideSmall = hideSmall,
                    onHideSmall = { hideSmall = it; store.hideSmall = it },
                    onAdoptOrder = { order, folders ->
                        // Keep exactly what was on screen as the new custom order.
                        val pos = order.withIndex().associate { (i, a) -> a to i }
                        updateSaved(saved.sortedBy { pos[it.address] ?: Int.MAX_VALUE })
                        groupOrder = folders; store.groupOrder = folders
                        walletSort = WalletSort.CUSTOM; store.walletSort = WalletSort.CUSTOM
                    },
                    onToggleHidden = toggleHidden,
                    onArchive = { list, archive -> updateSaved(saved.map { if (it.address in list) it.copy(archived = archive) else it }) },
                    onBulkFolder = { list, folder ->
                        updateSaved(saved.map { if (it.address in list && !it.contact) it.copy(group = folder) else it })
                    },
                    onBulkDelete = { list ->
                        updateSaved(saved.filter { it.address !in list })
                        list.forEach(store::forget)
                        cache = cache - list.toSet()
                    },
                    onRenameGroup = { old, new ->
                        updateSaved(saved.map {
                            if (it.group == old) it.copy(group = new, platform = if (it.platform == old) new else it.platform) else it
                        })
                        groupOrder = groupOrder.map { if (it == old) new else it }; store.groupOrder = groupOrder
                        store.collapsedGroups = store.collapsedGroups.map { if (it == old) new else it }.toSet()
                    },
                    onDelete = { item ->
                        updateSaved(saved - item)
                        store.forget(item.address)
                        cache = cache - item.address
                    },
                )
                else -> SettingsTab(
                    themeMode, onThemeChange, lockAvailable, lockEnabled, onLockChange, store,
                    lockTimeout = lockTimeout,
                    onLockTimeoutChange = onLockTimeoutChange,
                    onPriceSourceChange = {
                        priceSource = it
                        store.priceSource = it
                        clearPriceCache()
                        refresh++
                        syncAll()
                    },
                    hidden = hidden,
                    onHiddenChange = { hidden = it; store.hideBalances = it },
                    onImport = { imported ->
                        val known = saved.map { it.address }.toSet()
                        val added = imported.filter { it.address !in known }
                        updateSaved(saved + added)
                        syncAll()
                        added.size
                    },
                    saved = saved,
                )
            }
        }
        }
    }
    receiveFor?.let { ReceiveDialog(it) { receiveFor = null } }
    choices?.let { list ->
        AddressChooser(
            list, choicesFromPhoto, onPick = { choices = null; tab = TAB_HOME; openAddress(it) }, onDismiss = { choices = null },
            txAlternative = choicesTx, onCheckTx = { choices = null; txCheckFor = it },
        )
    }
    // An update in progress (started in Settings) and, once after updating, what changed.
    UpdateProgressDialog()
    var whatsNew by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        if (justUpdated(context, saved.isNotEmpty())) whatsNew = releaseNotes(BatApp.appVersion(context))
    }
    whatsNew?.let { WhatsNewDialog(BatApp.appVersion(context), it) { whatsNew = null } }
    txCheckFor?.let { initial ->
        TxCheckDialog(initial, onFound = { txCheckFor = null; txInfo = it }, onDismiss = { txCheckFor = null })
    }
    if (comparing) CompareDialog { comparing = false }
    if (sendChecking) SendCheckDialog(onOpen = { sendChecking = false; tab = TAB_HOME; openAddress(it) }, onDismiss = { sendChecking = false })
    // After a crash, offer once to send the report to the developer.
    var crashAsk by remember {
        mutableStateOf(BatApp.lastCrash(context)?.takeIf { it.hashCode() != store.crashPrompted })
    }
    crashAsk?.takeIf { !locked }?.let { report ->
        fun done() { store.crashPrompted = report.hashCode(); crashAsk = null }
        AlertDialog(
            onDismissRequest = { done() },
            title = { Text("Batwallet closed unexpectedly") },
            text = { Text("Report the crash on GitHub so it can be fixed? The report has only app and phone details, no wallets.") },
            confirmButton = { TextButton(onClick = { done(); reportOnGitHub(context, report) }) { Text("Report on GitHub") } },
            dismissButton = { TextButton(onClick = { done() }) { Text("Not now") } },
        )
    }
    // First time on each tab: a few skippable tips.
    var tipsSeen by remember { mutableStateOf(store.tipsSeen) }
    val tipsFor = when { address.isNotEmpty() -> null; tab == TAB_WALLETS -> "wallets"; tab == TAB_SETTINGS -> "settings"; else -> "home" }
    if (!locked && crashAsk == null && tipsFor != null && tipsFor !in tipsSeen) {
        TipsDialog(
            TIPS.getValue(tipsFor),
            onDone = { tipsSeen = tipsSeen + tipsFor; store.tipsSeen = tipsSeen },
            onSkipAll = { tipsSeen = TIPS.keys; store.tipsSeen = tipsSeen },
        )
    }
    pendingEvm?.let { raw ->
        NetworkPicker(onDismiss = { pendingEvm = null }) { net ->
            pendingEvm = null
            address = canonical(withNet(raw, net))
        }
    }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeTab(
    saved: List<SavedAddress>,
    cache: Map<String, Cached>,
    syncing: Set<String>,
    address: String,
    onAddress: (String) -> Unit,
    loading: Boolean,
    message: String?,
    results: List<ChainResult>,
    resultsTime: Long?,
    groups: List<String>,
    onRefresh: () -> Unit,
    onSave: (SavedAddress) -> Unit,
    safety: Safety?,
    safetyLoading: Boolean,
    onHistory: () -> Unit,
    onToggleHidden: () -> Unit,
    onScan: () -> Unit,
    onReceive: (SavedAddress) -> Unit,
    dayChange: DayChange?,
    tokens: List<TokenBalance>?,
    /** Opened from the Wallets tab: show a back link instead of the total and search buttons. */
    detail: Boolean,
    /** "Update available" on Home was tapped: go to Settings → Updates. */
    onOpenUpdate: () -> Unit = {},
    backLabel: String,
    onBack: () -> Unit,
    recent: List<Pair<String, Long>>,
    onPaste: (String) -> Boolean,
    onCheckTx: () -> Unit,
    onCompare: () -> Unit,
    onSendCheck: () -> Unit = {},
    activity: List<Pair<SavedAddress, Seen>>,
    onOpen: (String) -> Unit,
    onSeeAll: () -> Unit,
    onClearRecent: () -> Unit,
    platform: Platform?,
    recentTx: List<Triple<String, String, Transfer>>,
    onOpenTx: (String, Transfer) -> Unit,
) {
    val context = LocalContext.current
    // A newer version found by the weekly check (or in Settings), shown as one small line under the total.
    val newer = remember(cache) { knownUpdate(context) }
    val clipboard = LocalClipboardManager.current
    val savedWallet = saved.firstOrNull { it.address == address }
    val addressNames = remember(saved) { nameLabels(saved) }
    val type = detect(address)
    val twin = remember(address, saved) { lookalike(address, saved) }
    var showSave by remember { mutableStateOf(false) }
    var showRecent by remember { mutableStateOf(false) }

    PullToRefreshBox(isRefreshing = loading || syncing.isNotEmpty(), onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (detail) {
                item {
                    TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) { Text(backLabel) }
                }
            } else {
            item { TotalHeader(saved.filter { !it.contact && !it.archived && !it.onExchange }, cache, syncing, dayChange, onToggleHidden) }
            // A newer version found by the weekly check (or by Settings): one small line, tap to update.
            if (newer != null) item { UpdateBanner(newer, onOpenUpdate) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Tap: finds the address in whatever was copied (even a whole message), else shows recent checks.
                    // Long-press: always opens recent checks and the tools (check a transaction, compare addresses).
                    Row(
                        Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(26.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .combinedClickable(
                                onClick = { if (!onPaste(clipboard.getText()?.text.orEmpty())) showRecent = true },
                                onLongClick = { showRecent = true },
                            ),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Paste & check", style = MaterialTheme.typography.labelLarge)
                    }
                    OutlinedButton(onClick = onScan, modifier = Modifier.height(52.dp)) { Text("Scan") }
                }
            }
            // Just sent to one of your exchange deposit addresses: show it until it's surely credited.
            val deposits = recentTx.mapNotNull { (w, net, t) ->
                saved.firstOrNull { it.address == w }?.takeIf { s ->
                    t.incoming && t.time > 0 && System.currentTimeMillis() - t.time < 30 * 60_000L &&
                        s.onExchange && knownPlatform(s.address) == null
                }?.let { Triple(it, net, t) }
            }.take(3)
            if (deposits.isNotEmpty()) item { DepositsCard(deposits) }
            val mine = saved.filter { !it.contact && !it.archived }
            if (recentTx.isNotEmpty()) {
                item {
                    // Folder · name; the short address only when two of your wallets share that name.
                    val walletName: (String) -> String? = { w ->
                        saved.firstOrNull { it.address == w }?.let { s ->
                            val twins = saved.count { !it.contact && it.name.equals(s.name, true) } > 1
                            val raw = rawAddress(s.address)
                            (if (s.group.isNotBlank()) s.group + " · " else "") + s.name +
                                (if (twins) " · " + raw.take(if (raw.startsWith("0x")) 6 else 4) + "…" + raw.takeLast(4) else "")
                        }
                    }
                    var all by remember { mutableStateOf(false) }
                    RecentTxSection(
                        recentTx.take(5),
                        names = addressNames,
                        walletName = walletName,
                        onOpen = onOpenTx,
                        onSeeAll = if (recentTx.size > 5) ({ all = true }) else null,
                    )
                    // "See all": the latest 30 across your wallets.
                    if (all) ModalBottomSheet(onDismissRequest = { all = false }) {
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
                            RecentTxSection(recentTx, names = addressNames, walletName = walletName,
                                onOpen = { n, t -> all = false; onOpenTx(n, t) }, title = "All recent transactions")
                        }
                    }
                }
            } else if (activity.isNotEmpty()) item { ActivitySection(activity) }
            if (mine.isEmpty()) item { EmptyHint() }
            }
            if (address.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                savedWallet?.let(::walletLabel) ?: addressNames.labelFor(address, netOf(address)) ?: labelOf(address),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (savedWallet != null || netOf(address) != null) {
                                Text(labelOf(address),
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            savedWallet?.note?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
                            }
                            Text(
                                rawAddress(address),
                                modifier = Modifier.clickable { copyAddress(context, address) },
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.MiddleEllipsis,
                            )
                        }
                        if (!detail) TextButton(onClick = { onAddress("") }) { Text("Clear") }
                    }
                    if (type != null) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = onHistory) { Text("History") }
                            TextButton(onClick = { onReceive(savedWallet ?: SavedAddress(type.label, address)) }) { Text("QR") }
                            TextButton(onClick = {
                                shareWallet(context, savedWallet?.name, address)
                            }) { Text("Share") }
                            TextButton(onClick = { showSave = true }) { Text(if (savedWallet == null) "Save" else "Edit") }
                        }
                    }
                }
            }
            if (loading && results.isEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Checking…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            message?.let { m -> item { Text(m, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
            // Exchange identity (badge, name, balance warning) or, for a very busy business / bot address, a red
            // warning, shown above the balance so it's read first.
            val exchangeId = if (type != null && results.isNotEmpty())
                exchangeIdentity(address, platform, savedWallet?.group.orEmpty(), savedWallet?.name.orEmpty()) else null
            if (exchangeId != null) {
                item { ExchangeCard(exchangeId) }
            } else if (type != null && results.isNotEmpty()) {
                val sentTx = results.mapNotNull { r -> r.rows.firstOrNull { it.first == "Transactions sent" }?.second?.toLongOrNull() }.maxOrNull() ?: 0
                exchangeNote(address, platform, savedWallet?.group.orEmpty(), savedWallet?.name.orEmpty(), sentTx)?.let { note ->
                    item { WalletNote(note, warning = isBusyAddress(address, sentTx)) }
                }
            }
            if (type != null && results.isNotEmpty()) item {
                BalanceCard(type, results, resultsTime, loading,
                    exchange = exchangeId != null || isExchangeWallet(address, savedWallet?.group.orEmpty(), savedWallet?.name.orEmpty()),
                    // An exchange's own wallet holds everyone's coins: its total is hidden until you ask for it.
                    hiddenAtFirst = exchangeId?.ownWallet == true || savedWallet?.onExchange == true)
            }
            if (!tokens.isNullOrEmpty()) item { OtherTokensCard(tokens) }
            // Not activated: every network answered and none shows a balance or any activity.
            val unused = results.isNotEmpty() && results.all { it.error == null && !it.active }
            val incomplete = results.any { it.error != null }
            if (type != null) item { SafetyCard(safety, safetyLoading, twin, savedWallet, platform, unused, incomplete) }
        }
    }

    if (showRecent) {
        ModalBottomSheet(onDismissRequest = { showRecent = false }) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
                if (recent.isEmpty()) {
                    Text("Copy a wallet address first, then tap Paste & check.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
                } else {
                    RecentSection(recent, { showRecent = false; onOpen(it) }, { onClearRecent(); showRecent = false })
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { showRecent = false; onCheckTx() }, modifier = Modifier.weight(1f)) { Text("Check transaction") }
                    OutlinedButton(onClick = { showRecent = false; onCompare() }, modifier = Modifier.weight(1f)) { Text("Compare") }
                }
                OutlinedButton(onClick = { showRecent = false; onSendCheck() }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text("Check before sending")
                }
            }
        }
    }
    if (showSave) {
        WalletDialog(
            title = if (savedWallet == null) "Save wallet" else "Edit wallet",
            initial = savedWallet ?: SavedAddress("", address),
            addressEditable = false,
            groups = groups,
            onDismiss = { showSave = false },
            onConfirm = { onSave(it); showSave = false },
        )
    }
}

/** Total of all saved wallets. Tap it to hide or show amounts. */
@Composable
private fun TotalHeader(
    saved: List<SavedAddress>,
    cache: Map<String, Cached>,
    syncing: Set<String>,
    change: DayChange?,
    onToggleHidden: () -> Unit,
) {
    // After a refresh finishes, show a quiet "Updated just now" that fades away.
    val busy = syncing.isNotEmpty()
    var wasBusy by remember { mutableStateOf(false) }
    var justUpdated by remember { mutableStateOf(false) }
    LaunchedEffect(busy) {
        if (busy) wasBusy = true
        else if (wasBusy) {
            wasBusy = false
            justUpdated = true
            delay(2500)
            justUpdated = false
        }
    }
    val statusAlpha by animateFloatAsState(if (busy || justUpdated) 1f else 0f, tween(400), label = "status")
    val known = saved.mapNotNull { cache[it.address] }
    val usd = known.mapNotNull { it.totals.usd }.fold(BigDecimal.ZERO, BigDecimal::add)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onToggleHidden).padding(vertical = 4.dp),
    ) {
        Text("Total", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            if (saved.isEmpty()) money(BigDecimal.ZERO) else if (known.isEmpty()) "…" else hideable(money(usd)),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.SemiBold,
        )
        change?.takeIf { known.isNotEmpty() }?.let { ch ->
            val sign = if (ch.usd.signum() >= 0) "+" else "−"
            val pct = String.format(Locale.US, "%.2f", kotlin.math.abs(ch.percent))
            Text(
                hideable("$sign${money(ch.usd.abs())} ($sign$pct%)") + " " + tr("today"),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One card per address. Networks that share the address (all EVM chains, or Bitcoin + Bitcoin Cash)
 * are combined under one total, with a short section for each network that holds something.
 */
@Composable
private fun BalanceCard(
    type: AddrType,
    results: List<ChainResult>,
    time: Long?,
    loading: Boolean,
    exchange: Boolean = false,
    /** An exchange's own wallet: the total is folded away until "Show anyway" (it's everyone's coins together). */
    hiddenAtFirst: Boolean = false,
) {
    val ok = results.filter { it.error == null }
    val active = ok.filter { it.active }
    val failed = results.filter { it.error != null }
    val multi = results.size > 1
    val sections = if (multi) active else ok
    var revealed by remember(hiddenAtFirst) { mutableStateOf(!hiddenAtFirst) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text(if (multi) type.label else results.first().network,
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!revealed) {
                Text("Balance hidden", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp))
                Text("It's the exchange's money, not one person's.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { revealed = true }, contentPadding = PaddingValues(0.dp)) { Text("Show anyway") }
                return@Column
            }
            Text(hideable(money(totalsOf(ok).usd)), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            // Scope: what this number is (and isn't) for exchange addresses.
            if (exchange) {
                Text("On-chain balance of this address only", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (multi && active.isEmpty() && failed.isEmpty()) {
                Text("Empty on every network", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            sections.forEach { r ->
                val shown = r.rows.drop(1).filter { (label, value) ->
                    when {
                        label == "Status" -> true
                        label.startsWith("USDT") || label.startsWith("USDC") -> value != "0.00" || !multi && label.startsWith("USDT")
                        label == r.nativeSym -> r.native.signum() > 0 || r.usdt.signum() == 0 && !multi
                        else -> false
                    }
                }
                if (multi) {
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        NetworkIcon(r.network, 16.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(r.network, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else if (shown.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                }
                shown.forEach { (label, value) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (label != "Status") {
                            CoinIcon(label.substringBefore(" ("), 24.dp)
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(label.substringBefore(" ("), modifier = Modifier.weight(1f))
                        Text(
                            if (label == "Status") value else hideable(value.substringBefore(" · ")),
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(if (label == "Status") 3f else 1.4f),
                            color = if (label == "Status") MaterialTheme.colorScheme.onSurfaceVariant else ComposeColor.Unspecified,
                        )
                    }
                }
            }
            if (failed.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(tr("Couldn't reach:") + " " + failed.joinToString(", ") { it.network },
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WalletsTab(
    /** 0 = Wallets, 1 = Contacts (each is its own page of the main swipe row). */
    subPage: Int,
    onSubPage: (Int) -> Unit,
    /**
     * How far this page is part-way towards the other of Wallets / Contacts (0 = settled, ±1 = fully the other).
     * The header is held in place by that much and cross-fades, so swiping between the two looks like switching tabs.
     */
    headerShift: () -> Float = { 0f },
    saved: List<SavedAddress>,
    cache: Map<String, Cached>,
    syncing: Set<String>,
    failed: Set<String>,
    onRefreshAll: () -> Unit,
    onPick: (SavedAddress) -> Unit,
    onHistory: (SavedAddress) -> Unit,
    onReceive: (SavedAddress) -> Unit,
    onSave: (SavedAddress) -> Unit,
    sort: WalletSort,
    onSort: (WalletSort) -> Unit,
    onPin: (SavedAddress) -> Unit,
    onSwap: (String, String) -> Unit,
    onDelete: (SavedAddress) -> Unit,
    groupOrder: List<String>,
    onSwapGroups: (String, String, List<String>) -> Unit,
    onAdoptOrder: (addresses: List<String>, folders: List<String>) -> Unit,
    hideSmall: Boolean,
    onHideSmall: (Boolean) -> Unit,
    onRenameGroup: (String, String) -> Unit,
    onEdit: (old: SavedAddress, new: SavedAddress) -> Unit,
    collapsedInit: Set<String>,
    onCollapsedChange: (Set<String>) -> Unit,
    onToggleHidden: () -> Unit,
    onArchive: (addresses: List<String>, archive: Boolean) -> Unit,
    onBulkFolder: (addresses: List<String>, folder: String) -> Unit,
    onBulkDelete: (addresses: List<String>) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SavedAddress?>(null) }
    var deleting by remember { mutableStateOf<SavedAddress?>(null) }
    var collapsed by remember { mutableStateOf(collapsedInit) }
    val showContacts = subPage == 1
    var renaming by remember { mutableStateOf<String?>(null) }
    // Drag handles only show in "Edit order" mode (from the ⋯ menu), so normal rows stay clean.
    var moreMenu by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var showArchived by remember { mutableStateOf(false) }
    // Long-press → Select, then tap rows to pick several and move, archive or delete them together.
    var selected by remember { mutableStateOf(setOf<String>()) }
    var movingSelected by remember { mutableStateOf(false) }
    var deletingSelected by remember { mutableStateOf(false) }
    val selecting = selected.isNotEmpty()
    BackHandler(enabled = selecting) { selected = emptySet() }
    val mine = saved.filter { !it.contact && !it.archived }
    val groups = mine.map { it.group }.filter { it.isNotBlank() }.distinct().sorted()
    val archivedCount = saved.count { it.archived }
    fun usd(list: List<SavedAddress>) =
        list.filter { !it.onExchange }.mapNotNull { cache[it.address]?.totals?.usd }.fold(BigDecimal.ZERO, BigDecimal::add)
    /** Pinned first (in their own order), then the rest by the chosen sort. */
    fun ordered(list: List<SavedAddress>): List<SavedAddress> {
        val sorted = when (sort) {
            WalletSort.CUSTOM -> list
            WalletSort.VALUE -> list.sortedByDescending { cache[it.address]?.totals?.usd ?: BigDecimal.ZERO }
            WalletSort.NAME -> list.sortedBy { it.name.lowercase() }
        }
        return sorted.filter { it.pinned } + sorted.filter { !it.pinned }
    }

    /** What one page (Wallets or Contacts) shows: its rows, folders in order, and rows per folder. */
    class Page(val shown: List<SavedAddress>, val orderedNames: List<String>, val sections: Map<String, List<SavedAddress>>, val searchable: Boolean)
    fun page(contacts: Boolean): Page {
        val tabList = saved.filter { it.contact == contacts && (showArchived || !it.archived) }
        // Search (shown once a list has 10 or more): matches name, folder, network or address.
        val searchable = tabList.size >= 10
        val q = if (searchable) query.trim() else ""
        val shown = tabList
            // Wallets worth under $1 can be hidden (ones not loaded yet stay visible).
            .filter { w -> !hideSmall || (cache[w.address]?.totals?.usd ?: return@filter true) >= BigDecimal.ONE }
            .filter { w ->
                q.isEmpty() || w.name.contains(q, true) || w.group.contains(q, true) ||
                    labelOf(w.address).contains(q, true) || rawAddress(w.address).contains(q, true)
            }
        // Folders: your own order (drag the handle), or by value / name when a sort is chosen. "No folder" stays last.
        val grouped = shown.groupBy { it.group }
        val named = grouped.keys.filter { it.isNotBlank() }
        val orderedNames = when (sort) {
            WalletSort.VALUE -> named.sortedByDescending { g -> usd(grouped[g].orEmpty()) }
            WalletSort.NAME -> named.sortedBy { it.lowercase() }
            WalletSort.CUSTOM -> named.sortedWith(compareBy<String> { g -> groupOrder.indexOf(g).let { if (it < 0) Int.MAX_VALUE else it } }.thenBy { it.lowercase() })
        }
        return Page(shown, orderedNames, (orderedNames + grouped.keys.filter { it.isBlank() }).associateWith { grouped.getValue(it) }, searchable)
    }
    val pages = listOf(page(false), page(true))
    val current = pages[subPage]

    /** Dragging while sorted by value or name first makes what's on screen your own order, then moves from there. */
    fun adoptShownOrder(p: Page) {
        if (sort == WalletSort.CUSTOM) return
        onAdoptOrder(p.sections.values.flatMap { ordered(it) }.map { it.address }, p.orderedNames)
    }

    Column(Modifier.fillMaxSize()) {
        // Header: balance (tap to hide / show), + and ⋯, then the Wallets / Contacts tabs. Refresh is pull-down.
        Column(
            Modifier.graphicsLayer {
                val o = headerShift()
                translationX = o * size.width
                alpha = (1f - 2f * kotlin.math.abs(o)).coerceAtLeast(0f) // one fades out before the other fades in
            }.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 12.dp)
        ) {
            if (selecting) {
                val picked = saved.filter { it.address in selected }
                val allArchived = picked.isNotEmpty() && picked.all { it.archived }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(64.dp)) {
                    IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Filled.Close, contentDescription = "Cancel") }
                    Text("${selected.size} " + tr("selected"), style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (picked.none { it.contact }) TextButton(onClick = { movingSelected = true }) { Text("Move") }
                    TextButton(onClick = { onArchive(selected.toList(), !allArchived); selected = emptySet() }) {
                        Text(if (allArchived) "Unarchive" else "Archive")
                    }
                    TextButton(onClick = { deletingSelected = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable(onClick = onToggleHidden)) {
                        Text(if (showContacts) "Contacts" else "Total balance", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(hideable(money(usd(current.shown.filter { !it.archived }))), style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold)
                    }
                    run {
                        IconButton(onClick = { adding = true }) {
                            Icon(Icons.Filled.Add, contentDescription = if (showContacts) "Add contact" else "Add wallet")
                        }
                        Box {
                            IconButton(onClick = { moreMenu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                            DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                                WalletSort.entries.forEach { s ->
                                    DropdownMenuItem(
                                        text = { Text((if (s == sort) "✓  " else "     ") + tr("Sort") + ": " + tr(s.label)) },
                                        onClick = { moreMenu = false; onSort(s) },
                                    )
                                }
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text((if (hideSmall) "✓  " else "     ") + tr("Hide balances under $1")) },
                                    onClick = { moreMenu = false; onHideSmall(!hideSmall) },
                                )
                                if (archivedCount > 0) DropdownMenuItem(
                                    text = { Text((if (showArchived) "✓  " else "     ") + tr("Show archived") + " ($archivedCount)") },
                                    onClick = { moreMenu = false; showArchived = !showArchived },
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth()) {
                UnderlineTab("Wallets", subPage == 0, Modifier.weight(1f)) { onSubPage(0) }
                UnderlineTab("Contacts", subPage == 1, Modifier.weight(1f)) { onSubPage(1) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            if (current.searchable && !selecting) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text("Search") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
            }
        }

        PullToRefreshBox(isRefreshing = syncing.isNotEmpty(), onRefresh = onRefreshAll, modifier = Modifier.fillMaxSize()) {
            run {
                val p = subPage
                val pg = pages[p]
                val listState = rememberLazyListState()
                val drag = remember(listState) { Reorder(listState) }
                // While a row is held at the top or bottom edge, keep scrolling so it can go anywhere in the list.
                LaunchedEffect(drag) {
                    snapshotFlow { drag.key != null }.collectLatest { held ->
                        while (held) {
                            withFrameNanos { }
                            if (drag.edge != 0) {
                                listState.dispatchRawDelta(drag.edge * 14f) // the row stays under the finger by itself
                                drag.moved()
                            }
                        }
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 24.dp),
                ) {
                    if (pg.shown.isEmpty()) {
                        item {
                            Text(
                                if (p == 1) "No contacts yet." else "No saved wallets yet.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 24.dp),
                            )
                        }
                    }
                    pg.sections.forEach { (group, wallets) ->
                        // While a folder is being dragged every folder shows just its name (back as before on release).
                        val isCollapsed = group in collapsed || drag.draggingFolder
                        val showHeader = pg.sections.size > 1 || group.isNotBlank()
                        if (showHeader) {
                            item(key = "group:$group") {
                                var folderMenu by remember { mutableStateOf(false) }
                                // Named folders move (not while searching or selecting); "Other" always stays last.
                                val canDragFolder = !selecting && query.isEmpty() && pg.orderedNames.size > 1 && group in pg.orderedNames
                                val swapFolder: (String, String) -> Unit = { a, b ->
                                    adoptShownOrder(pg)
                                    onSwapGroups(a.removePrefix("group:"), b.removePrefix("group:"), pg.orderedNames)
                                }
                                Row(
                                    (if (drag.key == "group:$group") Modifier.dragged(drag, "group:$group") else Modifier.animateItem()).fillMaxWidth()
                                        .combinedClickable(
                                            onClick = {
                                                collapsed = if (isCollapsed) collapsed - group else collapsed + group
                                                onCollapsedChange(collapsed)
                                            },
                                            // Hold to drag the folder; hold without moving opens its menu.
                                            onLongClick = { if (!canDragFolder && group.isNotBlank()) folderMenu = true },
                                        )
                                        .then(if (canDragFolder) Modifier.reorderable(drag, "group:$group", pg.orderedNames.map { "group:$it" }, swapFolder) {
                                            folderMenu = true
                                        } else Modifier)
                                        .padding(top = 18.dp, bottom = 6.dp, end = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        (if (isCollapsed) "▸  " else "▾  ") + group.ifBlank { tr("Other") },
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(if (wallets.isNotEmpty() && wallets.all { it.onExchange }) "Exchange" else hideable(money(usd(wallets.filter { !it.archived }))), style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (group.isNotBlank()) {
                                        Box {
                                            DropdownMenu(expanded = folderMenu, onDismissRequest = { folderMenu = false }) {
                                                DropdownMenuItem(text = { Text("Rename folder") },
                                                    onClick = { folderMenu = false; renaming = group })
                                                // Archive (or bring back) every wallet in this folder at once.
                                                val allArchived = wallets.all { it.archived }
                                                DropdownMenuItem(
                                                    text = { Text(if (allArchived) "Unarchive folder" else "Archive folder") },
                                                    onClick = { folderMenu = false; onArchive(wallets.map { it.address }, !allArchived) },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (!isCollapsed) {
                            val inOrder = ordered(wallets)
                            items(inOrder, key = { it.address }) { item ->
                                val c = cache[item.address]
                                val peers = inOrder.filter { it.pinned == item.pinned }.map { it.address }
                                fun toggle() { selected = if (item.address in selected) selected - item.address else selected + item.address }
                                val swapRow: (String, String) -> Unit = { a, b -> adoptShownOrder(pg); onSwap(a, b) }
                                val canDrag = !selecting && query.isEmpty() && peers.size > 1
                                WalletRow(
                                    if (drag.key == item.address) Modifier.dragged(drag, item.address) else Modifier.animateItem(),
                                    item, c,
                                    syncing = item.address in syncing,
                                    failed = item.address in failed,
                                    onPick = onPick,
                                    onHistory = onHistory,
                                    onReceive = onReceive,
                                    onShare = { shareWallet(context, it.name, it.address) },
                                    onEdit = { editing = it },
                                    onDelete = { deleting = it },
                                    onPin = onPin,
                                    holdDrag = if (canDrag) { onHold -> Modifier.reorderable(drag, item.address, peers, swapRow, onHold) } else null,
                                    selectMode = selecting,
                                    isSelected = item.address in selected,
                                    onToggleSelect = ::toggle,
                                    onArchive = { onArchive(listOf(item.address), !item.archived) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (movingSelected) {
        var folder by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { movingSelected = false },
            title = { Text("Move to folder") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = folder, onValueChange = { folder = it }, singleLine = true, label = { Text("Folder name") })
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (groups + listOf("Binance", "Bybit", "OKX", "Trust Wallet")).distinct().forEach { g ->
                            FilterChip(selected = folder == g, onClick = { folder = g }, label = { Text(g) })
                        }
                        FilterChip(selected = false, onClick = { folder = "" }, label = { Text("No folder") })
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { onBulkFolder(selected.toList(), folder.trim()); selected = emptySet(); movingSelected = false }) { Text("Move") }
            },
            dismissButton = { TextButton(onClick = { movingSelected = false }) { Text("Cancel") } },
        )
    }
    if (deletingSelected) {
        AlertDialog(
            onDismissRequest = { deletingSelected = false },
            title = { Text(tr("Delete") + " ${selected.size}?") },
            text = { Text("This only removes them from this app.") },
            confirmButton = {
                TextButton(onClick = { onBulkDelete(selected.toList()); selected = emptySet(); deletingSelected = false }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deletingSelected = false }) { Text("Cancel") } },
        )
    }

    if (adding) {
        WalletDialog(if (showContacts) "Add contact" else "Add wallet", SavedAddress("", "", contact = showContacts),
            addressEditable = true, groups = groups, existing = saved,
            onDismiss = { adding = false }) { onSave(it); adding = false }
    }
    editing?.let { item ->
        WalletDialog("Edit wallet", item, addressEditable = false, groups = groups, existing = saved,
            onDismiss = { editing = null }) { onEdit(item, it); editing = null }
    }
    renaming?.let { old ->
        var text by remember(old) { mutableStateOf(old) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename folder") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, label = { Text("Folder name") }) },
            confirmButton = {
                TextButton(onClick = { onRenameGroup(old, text.trim()); renaming = null },
                    enabled = text.isNotBlank() && text.trim() != old) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    deleting?.let { item ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${item.name}\"?") },
            text = { Text("This only removes it from this app.") },
            confirmButton = { TextButton(onClick = { onDelete(item); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

/** One saved wallet: name, network and value. Tap to open; long-press for more. */
@Composable
private fun WalletRow(
    modifier: Modifier,
    item: SavedAddress,
    cached: Cached?,
    syncing: Boolean,
    failed: Boolean,
    onPick: (SavedAddress) -> Unit,
    onHistory: (SavedAddress) -> Unit,
    onReceive: (SavedAddress) -> Unit,
    onShare: (SavedAddress) -> Unit,
    onEdit: (SavedAddress) -> Unit,
    onDelete: (SavedAddress) -> Unit,
    onPin: (SavedAddress) -> Unit,
    /** Hold-and-drag to reorder; given what a hold without moving should do (open the menu). */
    holdDrag: (@Composable (onHold: () -> Unit) -> Modifier)?,
    selectMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelect: () -> Unit = {},
    onArchive: () -> Unit = {},
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val dragging = holdDrag?.invoke { menu = true } ?: Modifier
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) MaterialTheme.colorScheme.surfaceVariant else ComposeColor.Transparent)
            .combinedClickable(
                onClick = { if (selectMode) onToggleSelect() else onPick(item) },
                // With hold-and-drag on, the drag gesture opens the menu itself when you hold without moving.
                onLongClick = { if (selectMode) onToggleSelect() else if (holdDrag == null) menu = true },
            )
            .then(dragging)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectMode) {
            Box(
                Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) { if (isSelected) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.surface) }
        } else {
            NetworkIcon(labelOf(item.address), 40.dp)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                (if (item.archived) tr("Archived") + " · " else "") + (if (item.pinned) tr("Pinned") + " · " else "") + labelOf(item.address),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        // Value on top, the USDT it holds underneath (like a token row in Trust Wallet). Contacts show theirs too.
        when {
            // An exchange's coins aren't yours: no balance here (the wallet page can still show it).
            item.onExchange -> Text("Exchange", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            cached != null -> Column(horizontalAlignment = Alignment.End) {
                Text(hideable(money(cached.totals.usd)), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(hideable(fmt2(cached.totals.usdt)) + " USDT", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            syncing -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            failed -> Text("—", color = MaterialTheme.colorScheme.error)
            else -> Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Box {
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Copy address") }, onClick = { menu = false; copyAddress(context, item.address) })
                DropdownMenuItem(text = { Text(if (item.pinned) "Unpin" else "Pin to top") }, onClick = { menu = false; onPin(item) })
                DropdownMenuItem(text = { Text("History") }, onClick = { menu = false; onHistory(item) })
                DropdownMenuItem(text = { Text("Show QR") }, onClick = { menu = false; onReceive(item) })
                DropdownMenuItem(text = { Text("Share") }, onClick = { menu = false; onShare(item) })
                DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onEdit(item) })
                DropdownMenuItem(text = { Text("Select") }, onClick = { menu = false; onToggleSelect() })
                DropdownMenuItem(text = { Text(if (item.archived) "Unarchive" else "Archive") }, onClick = { menu = false; onArchive() })
                DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDelete(item) })
            }
        }
    }
}

/** Round icon button with a label underneath (Trust Wallet's Send / Receive row). */
@Composable
private fun ActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(50.dp).clip(androidx.compose.foundation.shape.CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

/** Text tab with a line under the selected one. */
@Composable
private fun UnderlineTab(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.titleSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 12.dp))
        Box(Modifier.width(48.dp).height(2.dp).background(if (selected) MaterialTheme.colorScheme.onSurface else ComposeColor.Transparent))
    }
}


/** Drag-to-reorder state for the Wallets list: which row is held and how far it has moved. */
private class Reorder(val list: LazyListState) {
    var key by mutableStateOf<String?>(null)
    /** Where the held row's top should be on screen: follows the finger, whatever the list does underneath. */
    var top by mutableFloatStateOf(0f)
    /** -1, 0 or 1: the held row is at the top edge, in the middle, or at the bottom edge (auto-scroll). */
    var edge = 0
    var peers: List<String> = emptyList()
    var onSwap: (String, String) -> Unit = { _, _ -> }
    // After a swap, wait for the list to redraw before the next one, so a quick drag can't swap straight back.
    private var settleFrom: Int? = null
    private var settleWait = 0

    /** True while a folder is held: the list then shows folder names only, so folders swap like a short list. */
    val draggingFolder: Boolean get() = key?.startsWith("group:") == true

    private fun row(k: String) = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == k }

    fun start(k: String) {
        key = k; edge = 0; settleFrom = null
        top = row(k)?.offset?.toFloat() ?: 0f
    }
    fun stop() { key = null; edge = 0; settleFrom = null }

    /** How far the held row is drawn from its place in the list, so it stays under the finger. */
    fun shift(k: String): Float = row(k)?.let { top - it.offset } ?: 0f

    /** The held row moved (finger or auto-scroll): note the edge, and swap with the row it now covers. */
    fun moved() {
        val k = key ?: return
        val info = list.layoutInfo
        val cur = row(k) ?: return
        settleFrom?.let { from ->
            if (cur.offset == from && settleWait++ < 10) return
            settleFrom = null
        }
        val margin = 48f
        edge = when {
            top < info.viewportStartOffset + margin -> -1
            top + cur.size > info.viewportEndOffset - margin -> 1
            else -> 0
        }
        val mid = top + cur.size / 2f
        val target = info.visibleItemsInfo.firstOrNull { it.key != k && it.key in peers && mid > it.offset && mid < it.offset + it.size }
            ?: return
        settleFrom = cur.offset
        settleWait = 0
        onSwap(k, target.key as String)
    }
}

/** The held row follows your finger and floats above the others. */
private fun Modifier.dragged(r: Reorder, key: String): Modifier =
    if (r.key == key) zIndex(1f).graphicsLayer { translationY = r.shift(key); shadowElevation = 12f } else this

/**
 * Hold (about half a second, with a little buzz) and drag to reorder among [peers]; a plain swipe still scrolls the list.
 * Holding without moving calls [onHold] instead (the row's usual menu).
 */
@Composable
private fun Modifier.reorderable(r: Reorder, key: String, peers: List<String>, onSwap: (String, String) -> Unit, onHold: () -> Unit): Modifier {
    val peersNow by rememberUpdatedState(peers)
    val swapNow by rememberUpdatedState(onSwap)
    val holdNow by rememberUpdatedState(onHold)
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    return pointerInput(key) {
        var moved = 0f
        detectDragGesturesAfterLongPress(
            onDragStart = {
                moved = 0f
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                r.start(key)
            },
            onDragEnd = {
                r.stop()
                if (moved < viewConfiguration.touchSlop) holdNow()
            },
            onDragCancel = { r.stop() },
        ) { change, amount ->
            change.consume()
            moved += kotlin.math.abs(amount.x) + kotlin.math.abs(amount.y)
            r.peers = peersNow
            r.onSwap = swapNow
            r.top += amount.y
            r.moved()
        }
    }
}

/** Address as a QR code so someone can scan it to pay you. */
@Composable
private fun ReceiveDialog(wallet: SavedAddress, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(wallet.name) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                QrImage(rawAddress(wallet.address), Modifier.size(230.dp))
                Spacer(Modifier.height(12.dp))
                Text(labelOf(wallet.address), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SelectionContainer {
                    Text(rawAddress(wallet.address), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        textAlign = TextAlign.Center)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { clipboard.setText(AnnotatedString(rawAddress(wallet.address))); onDismiss() }) { Text("Copy") }
        },
        dismissButton = {
            TextButton(onClick = { shareWallet(context, wallet.name, wallet.address) }) { Text("Share") }
        },
    )
}

/** Add or edit a wallet: name, group (pick an existing one or type a new one) and, when adding, the address. */
@Composable
private fun WalletDialog(
    title: String,
    initial: SavedAddress,
    addressEditable: Boolean,
    groups: List<String>,
    /** Already saved wallets and contacts, so the same address (on the same network) can't be saved twice. */
    existing: List<SavedAddress> = emptyList(),
    onDismiss: () -> Unit,
    onConfirm: (SavedAddress) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var name by remember { mutableStateOf(initial.name) }
    var group by remember { mutableStateOf(initial.group) }
    var addr by remember { mutableStateOf(initial.address) }
    var contact by remember { mutableStateOf(initial.contact) }
    var net by remember { mutableStateOf(netOf(initial.address)) }
    var platform by remember { mutableStateOf(initial.platform) }
    var note by remember { mutableStateOf(initial.note) }
    val type = detect(addr)
    val fullAddr = if (type == AddrType.EVM) withNet(rawAddress(addr), net) else addr
    val duplicate = existing.firstOrNull { w ->
        w.address != initial.address && rawAddress(w.address).equals(rawAddress(fullAddr), true) && netOf(w.address) == netOf(fullAddr)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = !contact, onClick = { contact = false }, label = { Text("My wallet") })
                    FilterChip(selected = contact, onClick = { contact = true }, label = { Text("Contact") })
                }
                OutlinedTextField(value = name, onValueChange = { name = it },
                    label = { Text(if (contact) "Name (e.g. Ali)" else "Name (e.g. Trust wallet Aptos)") }, singleLine = true)
                if (!contact) {
                    OutlinedTextField(value = group, onValueChange = { group = it },
                        label = { Text("Folder (optional)") }, singleLine = true,
                        )
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (groups + listOf("Binance", "Bybit", "OKX", "Trust Wallet", "MetaMask", "Coinbase", "Ledger")).distinct().forEach { g ->
                            FilterChip(selected = group == g, onClick = { group = if (group == g) "" else g }, label = { Text(g) })
                        }
                    }
                }
                if (addressEditable) {
                    OutlinedTextField(
                        value = addr, onValueChange = { addr = it.trim() }, label = { Text("Address") }, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        supportingText = {
                            Text(
                                when {
                                    addr.isEmpty() -> "Paste a USDT address"
                                    type == null -> "Not a supported address"
                                    else -> type.label
                                },
                                color = if (addr.isNotEmpty() && type == null) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                    TextButton(onClick = {
                        clipboard.getText()?.text?.let { text ->
                            // Finds the address inside a whole copied message, and its network if the message names one.
                            val found = extractAddresses(text).firstOrNull()
                            addr = found?.let(::rawAddress) ?: cleanAddress(text)
                            found?.let(::netOf)?.let { net = it }
                        }
                    }) { Text("Paste address") }
                }
                duplicate?.let {
                    Text(tr("Already saved as") + " " + walletLabel(it), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Note (optional)") }, maxLines = 3)
                if (type == AddrType.EVM) {
                    Text("Network", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = net == null, onClick = { net = null }, label = { Text("All") })
                        EVM_PICK.forEach { n -> FilterChip(selected = net == n, onClick = { net = n }, label = { Text(n) }) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val full = if (type == AddrType.EVM) withNet(rawAddress(addr), net) else addr
                    onConfirm(initial.copy(name = name.trim(), address = full, group = if (contact) "" else group.trim(),
                        contact = contact, platform = if (contact) "" else platform, note = note.trim()))
                },
                enabled = name.isNotBlank() && type != null && duplicate == null,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}


@Composable
private fun RadioRow(selected: Boolean, title: String, subtitle: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(tr(title))
            if (subtitle != null) {
                Text(tr(subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private val LOCK_TIMES = listOf(0 to "Every time", 60 to "1 min", 120 to "2 min", 180 to "3 min", 300 to "5 min")

@Composable
private fun SettingsTab(
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    lockAvailable: Boolean,
    lockEnabled: Boolean,
    onLockChange: (Boolean) -> Unit,
    store: Store,
    lockTimeout: Int,
    onLockTimeoutChange: (Int) -> Unit,
    onPriceSourceChange: (PriceSource) -> Unit,
    hidden: Boolean,
    onHiddenChange: (Boolean) -> Unit,
    onImport: (List<SavedAddress>) -> Int,
    saved: List<SavedAddress>,
) {
    val context = LocalContext.current
    var backupMessage by remember { mutableStateOf<String?>(null) }
    var keyText by remember { mutableStateOf(store.etherscanKey) }
    var savedKey by remember { mutableStateOf(store.etherscanKey) }
    val scope = rememberCoroutineScope()
    // Backup with an optional password: the file content is prepared (and encrypted) first, then saved.
    var askExport by remember { mutableStateOf(false) }
    var exportContent by remember { mutableStateOf<String?>(null) }
    var lockedImport by remember { mutableStateOf<String?>(null) } // a password-protected backup waiting for its password
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val content = exportContent
        exportContent = null
        if (uri == null || content == null) return@rememberLauncherForActivityResult
        backupMessage = try {
            context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
            tr("Backup saved") + " (${saved.size})."
        } catch (e: Exception) {
            "Couldn't save the backup: ${e.message}"
        }
    }
    fun restore(text: String) {
        backupMessage = try {
            val list = parseBackup(text)
            val added = onImport(list)
            parseBackupKey(text).takeIf { it.isNotEmpty() && savedKey.isEmpty() }?.let { k ->
                store.etherscanKey = k; etherscanKey = k; keyText = k; savedKey = k
            }
            tr("Restored") + " $added · " + tr("already saved") + " ${list.size - added}."
        } catch (e: Exception) {
            "That file isn't a Batwallet backup (${e.message})."
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull().orEmpty()
        if (BackupCrypto.isEncrypted(text)) lockedImport = text else restore(text)
    }
    var alerts by remember { mutableStateOf(store.alertsEnabled && Alerts.canNotify(context)) }
    var instant by remember { mutableStateOf(store.instantAlerts) }
    var alertMin by remember { mutableIntStateOf(store.alertMin) }
    var screenOffLock by remember { mutableStateOf(store.lockOnScreenOff) }
    var daily by remember { mutableStateOf(store.dailySummary) }
    var bigPay by remember { mutableIntStateOf(store.bigPayment) }
    var crash by remember { mutableStateOf(BatApp.lastCrash(context)) }
    var source by remember { mutableStateOf(store.priceSource) }
    var batteryOk by remember { mutableStateOf(Alerts.ignoresBatteryLimits(context)) }

    // Re-check the battery setting when coming back from the system screen.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) batteryOk = Alerts.ignoresBatteryLimits(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun setAlerts(on: Boolean) {
        alerts = on
        store.alertsEnabled = on
        Alerts.apply(context)
        if (on) Alerts.checkNow(context) // remember current balances as the starting point
    }
    fun setInstant(on: Boolean) {
        instant = on
        store.instantAlerts = on
        Alerts.apply(context)
    }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        setAlerts(granted)
    }

    var open by rememberSaveable { mutableStateOf<String?>(null) }
    fun toggle(id: String) { open = if (open == id) null else id }
    // Arrived from Home's "Update available": open Updates straight away.
    LaunchedEffect(SelfUpdate.openInSettings) {
        if (SelfUpdate.openInSettings) { open = "updates"; SelfUpdate.openInSettings = false }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        SettingsSection("General") {
        SettingGroup("Appearance", tr(themeMode.label), open == "theme", { toggle("theme") }) {
            ThemeMode.entries.forEach { mode -> RadioRow(mode == themeMode, mode.label) { onThemeChange(mode) } }
        }
        SettingGroup("Language", LANGUAGES.first { it.first == I18n.lang }.second, open == "lang", { toggle("lang") }) {
            LANGUAGES.forEach { (code, label) ->
                RadioRow(I18n.lang == code, label) { I18n.lang = code; store.language = code }
            }
        }
        SettingGroup("Currency", fiat.label, open == "currency", { toggle("currency") }) {
            // USDT first; the usual currencies below it.
            RadioRow(fiat == Fiat.USDT, Fiat.USDT.label) { fiat = Fiat.USDT; store.currency = Fiat.USDT }
            Label("Other currencies")
            Fiat.entries.filter { it != Fiat.USDT }.forEach { f -> RadioRow(f == fiat, f.label) { fiat = f; store.currency = f } }
        }
        }
        SettingsSection("Alerts & security") {
        SettingGroup(
            "Notifications",
            if (!alerts) tr("Off") else tr(if (instant) "Instant" else "Battery saver") + (if (daily) " · " + tr("Daily summary") else ""),
            open == "alerts", { toggle("alerts") },
        ) {
            SwitchRow("Payment alerts", null, alerts) { on ->
                if (on && Build.VERSION.SDK_INT >= 33 && !Alerts.canNotify(context)) {
                    askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    setAlerts(on)
                }
            }
            if (alerts) {
                RadioRow(instant, "Instant", "30 s") { setInstant(true) }
                if (instant) {
                    // Android insists on a small notification while instant alerts run; it can be switched off there.
                    TextButton(onClick = { runCatching { context.startActivity(Alerts.watchChannelSettings(context)) } },
                        contentPadding = PaddingValues(start = 36.dp)) {
                        Text("Hide the \"running\" notification", style = MaterialTheme.typography.bodySmall)
                    }
                }
                RadioRow(!instant, "Battery saver", "15 min · 30 s charging") { setInstant(false) }
                // Say plainly what each mode means for how fast a payment shows up.
                Text(
                    if (!instant) "Payments can show up 15 minutes late or more while the phone isn't charging. Choose Instant for alerts within 30 seconds."
                    else if (!batteryOk) "Your phone may still pause Batwallet in the background, delaying alerts. Allow background running below."
                    else "Alerts within about 30 seconds.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (instant && !batteryOk) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 6.dp),
                )
                Label("Minimum")
                ChipRow(listOf(0 to "Any amount", 1 to "1 USDT", 10 to "10 USDT", 100 to "100 USDT"), alertMin) { alertMin = it; store.alertMin = it }
                Label("Big payment")
                ChipRow(listOf(0 to "Off", 100 to "100 USDT", 500 to "500 USDT", 1000 to "1,000 USDT", 5000 to "5,000 USDT"), bigPay) { bigPay = it; store.bigPayment = it }
                if (!batteryOk) {
                    TextButton(onClick = { Alerts.askToIgnoreBatteryLimits(context) }, contentPadding = PaddingValues(0.dp)) {
                        Text("Allow background running")
                    }
                }
            }
            SwitchRow("Daily summary", "9 AM", daily) { on ->
                daily = on; store.dailySummary = on; runCatching { Alerts.apply(context) }
            }
        }
        SettingGroup(
            "Security",
            tr(if (lockEnabled && lockAvailable) "On" else "Off"),
            open == "lock", { toggle("lock") },
        ) {
            SwitchRow(
                "Fingerprint lock",
                if (lockAvailable) null else "Set up a screen lock on your phone first",
                lockEnabled && lockAvailable, enabled = lockAvailable, onChange = onLockChange,
            )
            if (lockEnabled && lockAvailable) {
                Label("Lock again after")
                ChipRow(LOCK_TIMES, lockTimeout, onLockTimeoutChange)
                SwitchRow("Lock on screen off", null, screenOffLock) { screenOffLock = it; store.lockOnScreenOff = it }
            }
        }
        }
        SettingsSection("Data") {
        SettingGroup("Backup", tr("Export or import"), open == "backup", { toggle("backup") }) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                OutlinedButton(onClick = { askExport = true }, enabled = saved.isNotEmpty()) { Text("Export") }
                OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text("Import") }
            }
            backupMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        }
        if (askExport) {
            var pw by remember { mutableStateOf("") }
            var pw2 by remember { mutableStateOf("") }
            var busy by remember { mutableStateOf(false) }
            // Show / Hide lets you check what you typed (the backup is unreadable without it).
            var showPw by remember { mutableStateOf(false) }
            val hideMask = if (showPw) androidx.compose.ui.text.input.VisualTransformation.None
                else androidx.compose.ui.text.input.PasswordVisualTransformation()
            val eye: @Composable () -> Unit = { TextButton(onClick = { showPw = !showPw }) { Text(if (showPw) "Hide" else "Show") } }
            AlertDialog(
                onDismissRequest = { if (!busy) askExport = false },
                title = { Text("Export backup") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = pw, onValueChange = { pw = it }, singleLine = true, label = { Text("Password (optional)") },
                            visualTransformation = hideMask, trailingIcon = eye)
                        if (pw.isNotEmpty()) OutlinedTextField(value = pw2, onValueChange = { pw2 = it }, singleLine = true,
                            label = { Text("Repeat password") }, isError = pw2.isNotEmpty() && pw2 != pw,
                            visualTransformation = hideMask)
                        if (pw.isNotEmpty()) Text("Without this password the backup can't be opened.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                confirmButton = {
                    TextButton(enabled = !busy && (pw.isEmpty() || pw == pw2), onClick = {
                        busy = true
                        scope.launch {
                            val json = store.exportJson()
                            exportContent = if (pw.isEmpty()) json
                            else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { BackupCrypto.encrypt(json, pw) }
                            busy = false
                            askExport = false
                            exporter.launch(if (pw.isEmpty()) "batwallet-backup.json" else "batwallet-backup-locked.json")
                        }
                    }) { Text(if (busy) "…" else "Export") }
                },
                dismissButton = { TextButton(onClick = { askExport = false }, enabled = !busy) { Text("Cancel") } },
            )
        }
        lockedImport?.let { text ->
            var pw by remember { mutableStateOf("") }
            var wrong by remember { mutableStateOf(false) }
            var busy by remember { mutableStateOf(false) }
            var showPw by remember { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = { if (!busy) lockedImport = null },
                title = { Text("Backup password") },
                text = {
                    OutlinedTextField(value = pw, onValueChange = { pw = it; wrong = false }, singleLine = true, label = { Text("Password") },
                        isError = wrong, supportingText = { if (wrong) Text("Wrong password") },
                        visualTransformation = if (showPw) androidx.compose.ui.text.input.VisualTransformation.None
                            else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        trailingIcon = { TextButton(onClick = { showPw = !showPw }) { Text(if (showPw) "Hide" else "Show") } })
                },
                confirmButton = {
                    TextButton(enabled = pw.isNotEmpty() && !busy, onClick = {
                        busy = true
                        scope.launch {
                            val plain = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                                runCatching { BackupCrypto.decrypt(text, pw) }.getOrNull()
                            }
                            busy = false
                            if (plain == null) wrong = true else { lockedImport = null; restore(plain) }
                        }
                    }) { Text(if (busy) "…" else "Open") }
                },
                dismissButton = { TextButton(onClick = { lockedImport = null }, enabled = !busy) { Text("Cancel") } },
            )
        }
        SettingsSection("Support") {
        // Updates come from the GitHub Releases page; the APK is downloaded in the browser and installed from there.
        var knownNewer by remember { mutableStateOf(knownUpdate(context)) }
        SettingGroup(
            "Updates",
            knownNewer?.let { tr("Version") + " $it " + tr("is available") } ?: (tr("Version") + " " + BatApp.appVersion(context)),
            open == "updates", { toggle("updates") },
        ) {
            // Opening the row is the check: no second tap needed.
            var checking by remember { mutableStateOf(true) }
            var result by remember { mutableStateOf<Release?>(null) }
            var checked by remember { mutableStateOf(false) }
            LaunchedEffect(checking) {
                if (checking) {
                    result = latestRelease()?.also { rememberRelease(context, it); knownNewer = knownUpdate(context) }
                    checked = true; checking = false
                }
            }
            val latest = result
            when {
                checking -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Checking…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                !checked -> {}
                latest == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Couldn't reach GitHub. Try again later.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    TextButton(onClick = { checking = true }) { Text("Retry") }
                }
                isNewerVersion(latest.version, BatApp.appVersion(context)) -> {
                    Text(tr("Version") + " ${latest.version} " + tr("is available"), fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp))
                    // What changed, right here instead of on GitHub.
                    if (latest.notes.isNotBlank()) {
                        ReleaseNotes(latest.notes, Modifier.padding(vertical = 8.dp).heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()))
                    }
                    // Downloaded and installed by the app (progress in its own dialog); the APK is deleted afterwards.
                    val busy = SelfUpdate.state !is SelfUpdate.State.Idle && SelfUpdate.state !is SelfUpdate.State.Failed
                    OutlinedButton(enabled = !busy, onClick = {
                        val apk = latest.apkUrl
                        when {
                            !SelfUpdate.trusted(apk) -> openInBrowser(context, latest.pageUrl)
                            !SelfUpdate.canInstall(context) -> {
                                android.widget.Toast.makeText(context, tr("Allow Batwallet to install updates, then tap Update again"),
                                    android.widget.Toast.LENGTH_LONG).show()
                                SelfUpdate.openInstallPermission(context)
                            }
                            else -> SelfUpdate.start(context, apk!!, latest.version)
                        }
                    }) { Text("Update") }
                }
                else -> {
                    Text("You have the latest version.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    // This version's notes: the latest release is usually this one, otherwise ask GitHub for it.
                    var showNotes by remember { mutableStateOf(false) }
                    var notes by remember { mutableStateOf<String?>(null) }
                    val current = BatApp.appVersion(context)
                    if (!showNotes) {
                        TextButton(onClick = { showNotes = true }, contentPadding = PaddingValues(0.dp)) {
                            Text(tr("What's new in") + " " + current)
                        }
                    } else {
                        LaunchedEffect(Unit) {
                            notes = if (latest.version == current && latest.notes.isNotBlank()) latest.notes
                            else releaseNotes(current) ?: ""
                        }
                        when (val n = notes) {
                            null -> CircularProgressIndicator(Modifier.padding(top = 8.dp).size(16.dp), strokeWidth = 2.dp)
                            "" -> Text("Couldn't reach GitHub. Try again later.", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error)
                            else -> ReleaseNotes(n, Modifier.padding(vertical = 8.dp).heightIn(max = 320.dp)
                                .verticalScroll(rememberScrollState()))
                        }
                    }
                }
            }
        }
        SettingGroup(
            "Report a problem",
            tr(if (crash != null) "The app crashed last time" else "GitHub"),
            open == "report", { toggle("report") },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                OutlinedButton(onClick = { reportOnGitHub(context, crash) }) { Text("Report on GitHub") }
                if (crash != null) TextButton(onClick = { BatApp.clearCrash(context); crash = null }) { Text("Dismiss") }
            }
        }

        }
        Spacer(Modifier.height(28.dp))
        Text(
            tr("Made with ♥︎ by MalluWayne & Claude"),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "github.com/$GITHUB_REPO",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { openInBrowser(context, GITHUB_URL) }
                .padding(vertical = 6.dp),
        )
        Text(
            tr("Version") + " " + BatApp.appVersion(context) + " · " + tr("Read-only. It never asks for keys and can't move funds."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }

}

private val LANGUAGES = listOf("en" to "English", "ru" to "Русский", "hi" to "हिन्दी")

/** A titled card holding a few Settings rows. */
@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Text(tr(title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 6.dp, top = 22.dp, bottom = 8.dp))
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) { Column(Modifier.padding(vertical = 4.dp)) { content() } }
}

/** One Settings row: icon, title, current value and ›. Tapping opens its options in a sheet from the bottom. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingGroup(title: String, summary: String, expanded: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            when (title) {
                "Notifications" -> Icon(Icons.Filled.Notifications, null, Modifier.size(20.dp))
                "Security" -> Icon(Icons.Filled.Lock, null, Modifier.size(20.dp))
                "Backup" -> Icon(Icons.Filled.Share, null, Modifier.size(20.dp))
                "Etherscan API key" -> Icon(Icons.Filled.Build, null, Modifier.size(20.dp))
                "Report a problem" -> Icon(Icons.Filled.Info, null, Modifier.size(20.dp))
                "Updates" -> Icon(Icons.Filled.Refresh, null, Modifier.size(20.dp))
                else -> Text(
                    when (title) { "Appearance" -> "◐"; "Language" -> "Aa"; "Currency" -> "$"; else -> "↗" },
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    fontSize = if (title == "Appearance") androidx.compose.ui.unit.TextUnit(20f, androidx.compose.ui.unit.TextUnitType.Sp) else androidx.compose.ui.unit.TextUnit.Unspecified,
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Text(tr(title), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp))
        Text("  ›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (expanded) {
        ModalBottomSheet(onDismissRequest = onToggle) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 36.dp)) {
                Text(tr(title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 12.dp))
                content()
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(tr(title))
            if (subtitle != null) {
                Text(tr(subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun Label(text: String) {
    Text(tr(text), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun ChipRow(options: List<Pair<Int, String>>, selected: Int, onPick: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (v, label) -> FilterChip(selected = selected == v, onClick = { onPick(v) }, label = { Text(tr(label)) }) }
    }
}


/** Asks which network a 0x address is on (the same address works on every EVM network). */
@Composable
private fun NetworkPicker(onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Which network?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("This address works on several networks. Pick one, or check them all.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                (listOf<String?>(null) + EVM_PICK).forEach { n ->
                    Text(
                        n ?: "All networks",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (n == null) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(n) }
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
/** Copies an address (without any network tag) and confirms with a short toast. */
fun copyAddress(context: Context, address: String) {
    val cm = context.getSystemService(android.content.ClipboardManager::class.java)
    cm.setPrimaryClip(android.content.ClipData.newPlainText("Address", rawAddress(address)))
    android.widget.Toast.makeText(context, "Address copied", android.widget.Toast.LENGTH_SHORT).show()
}

/** Pages opened on top (history, a transaction) slide in gently instead of popping up. */
@Composable
private fun SlideIn(content: @Composable () -> Unit) {
    val shown = remember { androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true } }
    androidx.compose.animation.AnimatedVisibility(
        visibleState = shown,
        enter = androidx.compose.animation.fadeIn(tween(220)) +
            androidx.compose.animation.slideInHorizontally(tween(280)) { it / 8 },
    ) { content() }
}

/**
 * Note shown above a wallet's balance when it isn't one person's money. [warning] (a very busy business / bot
 * address) is red with a bold title so it can't be missed; exchange wallets get a calmer grey note.
 */
@Composable
private fun WalletNote(text: String, warning: Boolean) {
    val accent = if (warning) OutRed else MaterialTheme.colorScheme.onSurfaceVariant
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (warning) OutRed.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(if (warning) Icons.Filled.Warning else Icons.Filled.Info, contentDescription = null, tint = accent,
                modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(if (warning) "Not a personal wallet" else "Exchange wallet", fontWeight = FontWeight.Bold,
                    color = if (warning) OutRed else MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(2.dp))
                Text(text, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
