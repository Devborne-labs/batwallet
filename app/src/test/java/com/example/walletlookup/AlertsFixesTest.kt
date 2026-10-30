package com.example.walletlookup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigDecimal

/**
 * Checks for the payment-alert fixes (coin fees vs real coin sends, how often missing details are searched), the
 * stored-data limits, the folder order from a backup, and backup encryption rounds. Pure JVM: no Android classes.
 */
class AlertsFixesTest {
    /** Prices for the tests: BNB at $600, ETH at $3,000; everything else unknown. */
    private val usd: (BigDecimal, String) -> BigDecimal? = { amount, symbol ->
        when (symbol.uppercase()) {
            "USDT", "USDC" -> amount
            "BNB" -> amount.multiply(BigDecimal("600"))
            "ETH" -> amount.multiply(BigDecimal("3000"))
            else -> null
        }
    }

    private fun d(s: String) = BigDecimal(s)

    // ---- Fees: only fee-sized coin drops next to a stablecoin send ----

    @Test fun smallCoinDropNextToStablecoinSendIsItsFee() {
        val changes = listOf("USDT|BSC" to d("-50"), "BNB|BSC" to d("-0.0001")) // $0.06
        assertEquals(setOf("BNB|BSC"), feeKeys(changes, emptySet(), usd))
    }

    @Test fun realCoinSendNextToStablecoinSendIsKept() {
        val changes = listOf("USDT|BSC" to d("-50"), "BNB|BSC" to d("-0.5")) // $300: a payment, not a fee
        assertTrue(feeKeys(changes, emptySet(), usd).isEmpty())
    }

    @Test fun coinDropWithoutAKnownPriceIsNotHidden() {
        val changes = listOf("USDT|Sonic" to d("-50"), "S|Sonic" to d("-0.01"))
        assertTrue(feeKeys(changes, emptySet(), usd).isEmpty())
    }

    @Test fun coinDropMatchedToARealTransferIsNotAFee() {
        val changes = listOf("USDT|BSC" to d("-50"), "BNB|BSC" to d("-0.0001"))
        assertTrue(feeKeys(changes, setOf("BNB|BSC"), usd).isEmpty())
    }

    @Test fun feeNeedsAStablecoinSendOnTheSameNetwork() {
        // Stablecoin received (not sent), or sent on another network: the coin drop stays.
        assertTrue(feeKeys(listOf("USDT|BSC" to d("50"), "BNB|BSC" to d("-0.0001")), emptySet(), usd).isEmpty())
        assertTrue(feeKeys(listOf("USDT|Ethereum" to d("-50"), "BNB|BSC" to d("-0.0001")), emptySet(), usd).isEmpty())
        // A coin increase is never a fee.
        assertTrue(feeKeys(listOf("USDT|BSC" to d("-50"), "BNB|BSC" to d("0.0001")), emptySet(), usd).isEmpty())
    }

    @Test fun feeSizedMeansUnderOneDollar() {
        assertTrue(isFeeSized(d("-0.0016"), "BNB", usd)) // $0.96
        assertFalse(isFeeSized(d("-0.002"), "BNB", usd)) // $1.20
        assertFalse(isFeeSized(d("-0.5"), "USDT", usd)) // stablecoins are never fees
        assertFalse(isFeeSized(d("-0.0001"), "XYZ", usd)) // no price
    }

    // ---- The one-time cleanup of fee entries saved by older versions ----

    private val hour = 3_600_000L

    private fun seen(sym: String, net: String, amt: String, t: Long, hash: String? = null) = Seen(sym, net, d(amt), t, hash)

    @Test fun oldFeeEntryIsRemovedButRealCoinSendsStay() {
        val stable = seen("USDT", "BSC", "-50", 10 * hour)
        val fee = seen("BNB", "BSC", "-0.0001", 10 * hour + 60_000)
        val realSend = seen("BNB", "BSC", "-0.5", 10 * hour + 120_000)
        val withHash = seen("BNB", "BSC", "-0.0001", 10 * hour + 180_000, hash = "0xabc")
        val kept = withoutOldFees(listOf(stable, fee, realSend, withHash), usd)
        assertEquals(listOf(stable, realSend, withHash), kept)
    }

    @Test fun coinDropFarFromAStablecoinSendIsKept() {
        val stable = seen("USDT", "BSC", "-50", 10 * hour)
        val later = seen("BNB", "BSC", "-0.0001", 13 * hour)
        assertEquals(listOf(stable, later), withoutOldFees(listOf(stable, later), usd))
    }

    // ---- How often missing details are searched ----

    @Test fun searchEveryTwoMinutesForHalfAnHourThenHourly() {
        assertEquals(2 * 60_000L, fillGapMs(0))
        assertEquals(2 * 60_000L, fillGapMs(29 * 60_000L))
        assertEquals(3_600_000L, fillGapMs(30 * 60_000L))
        assertEquals(3_600_000L, fillGapMs(20 * hour))
    }

    // ---- Folder order from a backup ----

    @Test fun backupFolderOrderMergesAfterYours() {
        assertEquals(listOf("Binance", "Trust", "Ledger"), mergeGroupOrder(listOf("Binance", "Trust"), listOf("Ledger", "Trust")))
        // A fresh install takes the backup's order as it is.
        assertEquals(listOf("Ledger", "Trust"), mergeGroupOrder(emptyList(), listOf("Ledger", "Trust", "Ledger")))
        assertEquals(listOf("A"), mergeGroupOrder(listOf("A"), emptyList()))
    }

    // ---- Safety-check entries kept ----

    @Test fun safetyChecksKeepSavedWalletsAndTheNewestOthers() {
        val times = (1..60).associate { "safety:addr$it" to it.toLong() } + ("safety:mine" to 0L)
        val drop = safetyKeysToDrop(times, keep = setOf("safety:mine"), limit = 50)
        assertEquals((1..10).map { "safety:addr$it" }.toSet(), drop) // the 10 oldest others
        assertFalse("safety:mine" in drop) // a saved wallet's check stays, however old
        assertTrue(safetyKeysToDrop(times, keep = setOf("safety:mine"), limit = 60).isEmpty())
    }

    // ---- Backup encryption rounds ----

    @Test fun backupRoundsComeFromTheFileWithinBounds() {
        assertEquals(600_000, BackupCrypto.ROUNDS)
        assertEquals(200_000, BackupCrypto.roundsFor(0)) // older backups that don't say
        assertEquals(200_000, BackupCrypto.roundsFor(200_000))
        assertEquals(600_000, BackupCrypto.roundsFor(600_000))
        assertEquals(100_000, BackupCrypto.roundsFor(5))
        assertEquals(2_000_000, BackupCrypto.roundsFor(Int.MAX_VALUE))
        assertEquals(200_000, BackupCrypto.roundsFor(-1))
    }

    @Test fun backupOpensOnlyWithTheSameRoundsAndPassword() {
        val salt = ByteArray(16) { it.toByte() }
        val iv = ByteArray(12) { (it * 7).toByte() }
        val plain = "{\"wallets\":[]}".toByteArray()
        val sealed = BackupCrypto.sealBytes(plain, "secret", salt, iv, 1_000)
        assertArrayEquals(plain, BackupCrypto.openBytes(sealed, "secret", salt, iv, 1_000))
        for ((pw, rounds) in listOf("secret" to 2_000, "wrong" to 1_000)) {
            try {
                BackupCrypto.openBytes(sealed, pw, salt, iv, rounds)
                fail("opened with $pw / $rounds")
            } catch (_: javax.crypto.AEADBadTagException) {
                // expected: a wrong key is detected, never half opened
            }
        }
    }
}
