package com.example.walletlookup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Checks for the history and safety-check fixes: Bitcoin Cash addresses, the activity check, matching balance changes
 * to transfers, merging sources without gaps, and what an address's code means.
 */
class InsightsFixesTest {
    private val hour = 3_600_000L
    private val now = 1_800_000_000_000L

    private fun t(incoming: Boolean, amount: String, time: Long, symbol: String = "USDT") =
        Transfer(incoming, BigDecimal(amount), null, time, null, null, null, null, symbol)

    // ---------- Bitcoin Cash: legacy address → cashaddr ----------

    @Test fun legacyAddressesBecomeTheirCashAddr() {
        assertEquals("bitcoincash:qpm2qsznhks23z7629mms6s4cwef74vcwvy22gdx6a", legacyToCashAddr("1BpEi6DfDAUFd7GtittLSdBeYJvcoaVggu"))
        assertEquals("bitcoincash:qr95sy3j9xwd2ap32xkykttr4cvcu7as4y0qverfuy", legacyToCashAddr("1KXrWXciRDZUpQwQmuM1DbwsKDLYAYsVLR"))
        // A 3… (script) address gets the "p" type.
        assertEquals("bitcoincash:ppm2qsznhks23z7629mms6s4cwef74vcwvn0h829pq", legacyToCashAddr("3CWFddi6m4ndiGyKqzYvsFYagqDLPVMTzC"))
    }

    @Test fun anythingElseIsNoLegacyAddress() {
        assertNull(legacyToCashAddr("1BpEi6DfDAUFd7GtittLSdBeYJvcoaVggv")) // one character off: checksum fails
        assertNull(legacyToCashAddr("bitcoincash:qpm2qsznhks23z7629mms6s4cwef74vcwvy22gdx6a"))
        assertNull(legacyToCashAddr("qpm2qsznhks23z7629mms6s4cwef74vcwvy22gdx6a"))
        assertNull(legacyToCashAddr("bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"))
        assertNull(legacyToCashAddr("LdP8Qox1VAhCzLJNqrr74YovaWYyNBUWvL")) // Litecoin: another version byte
        assertNull(legacyToCashAddr(""))
    }

    // ---------- Activity check ----------

    @Test fun usedWalletWithoutRecentTransfersIsNoWarning() {
        // Balances show funds on BSC, but the history (only recent days) found nothing.
        val c = activityCheck(listOf(History("BSC")), setOf("BSC"))!!
        assertEquals(Level.INFO, c.level)
        assertEquals("No recent transfers found", c.title)
        assertFalse(c.failed)
    }

    @Test fun historyCutShortCantSayUnused() {
        val more = activityCheck(listOf(History("Tron", canLoadMore = true)))!!
        assertEquals(Level.INFO, more.level)
        val noted = activityCheck(listOf(History("BSC", note = "Checked back to 1 Oct 2026.")))!!
        assertEquals(Level.INFO, noted.level)
    }

    @Test fun noActivityAnywhereIsStillAWarning() {
        // A complete, empty history and no balances anywhere.
        assertEquals(Level.WARN, activityCheck(listOf(History("Tron")), emptySet())!!.level)
        // A 0x address with no activity: history() asks nothing and comes back empty.
        assertEquals(Level.WARN, activityCheck(emptyList(), emptySet())!!.level)
    }

    @Test fun networksWithoutAHistorySourceGiveNoActivityCheck() {
        assertNull(activityCheck(listOf(History("VeChain", supported = false), History("Ethereum Classic", supported = false)),
            setOf("VeChain", "Ethereum Classic")))
        assertNull(activityCheck(emptyList(), setOf("VeChain")))
    }

    // ---------- Matching a balance change to its transfer ----------

    @Test fun oldTransferOfTheSameAmountIsNotTaken() {
        val weeksOld = t(true, "50", now - 20 * 24 * hour)
        assertTrue(pickTransfers(listOf(weeksOld), BigDecimal("50"), now).isEmpty())
        // Within 3 hours before: taken. Unknown time (0): still allowed.
        val recent = t(true, "50", now - 2 * hour)
        assertEquals(listOf(recent), pickTransfers(listOf(weeksOld, recent), BigDecimal("50"), now))
        val untimed = t(true, "50", 0)
        assertEquals(listOf(untimed), pickTransfers(listOf(weeksOld, untimed), BigDecimal("50"), now))
    }

    @Test fun coinSentMatchesAChangeThatIncludesTheFee() {
        val sent = t(false, "0.5", now - hour, "ETH")
        assertEquals(listOf(sent), pickTransfers(listOf(sent), BigDecimal("-0.5021"), now))
        // Too big a difference to be a fee.
        assertTrue(pickTransfers(listOf(sent), BigDecimal("-0.6"), now).isEmpty())
        // Stablecoins don't pay their fee in the same token.
        assertTrue(pickTransfers(listOf(t(false, "0.5", now - hour)), BigDecimal("-0.5021"), now).isEmpty())
        // Money in never carries our fee.
        assertTrue(pickTransfers(listOf(t(true, "0.5", now - hour, "ETH")), BigDecimal("0.5021"), now).isEmpty())
    }

    @Test fun feeMatchPrefersTheClosestTime() {
        val earlier = t(false, "1", now - 2 * hour, "BNB")
        val closer = t(false, "1", now - 10 * 60_000L, "BNB")
        assertEquals(listOf(closer), pickTransfers(listOf(earlier, closer), BigDecimal("-1.0001"), now))
    }

    // ---------- Merging sources ----------

    @Test fun fixedSourceDoesNotHideTheOthersOlderTransfers() {
        // A source that always gives its last 3 (fixed maximum, can't grow), back to time 300; a deeper one to 100.
        val capped = Part(listOf(t(true, "1", 500), t(true, "2", 400), t(true, "3", 300)), bounded = true, reach = 300)
        val deep = Part(listOf(t(true, "4", 450, "BNB"), t(true, "5", 200, "BNB"), t(true, "6", 100, "BNB")))
        val (items, more) = mergeParts(listOf(capped, deep), 25)
        // Cutting at 300 would hide the deeper source's older transfers for good (Load more can't extend the capped one).
        assertEquals(listOf(500L, 450L, 400L, 300L, 200L, 100L), items.map { it.time })
        assertFalse(more)
    }

    @Test fun shortReachingSourceDoesNotHideTheOthersTransfers() {
        // A USDT log scan that has only reached back to 300 so far, and a month of BNB from another source: all of the
        // BNB stays (it used to be cut at 300), and Load more is offered for the scan.
        val growing = Part(listOf(t(true, "1", 500), t(true, "2", 300)), bounded = true, reach = 300, grows = true)
        val deep = Part(listOf(t(true, "4", 450, "BNB"), t(true, "5", 200, "BNB")))
        val (items, more) = mergeParts(listOf(growing, deep), 25)
        assertEquals(listOf(500L, 450L, 300L, 200L), items.map { it.time })
        assertTrue(more)
    }

    @Test fun growingSourceOffersLoadMore() {
        val growing = sourcePage(listOf(t(true, "1", 500), t(true, "2", 300)), count = 2, asked = 2, oldest = 300)
        assertTrue(growing.bounded)
        assertTrue(mergeParts(listOf(growing, Part(listOf(t(true, "3", 100, "TRX")))), 25).second)
        // A full page from a source with a fixed maximum: no Load more.
        val fixed = sourcePage(listOf(t(true, "1", 500)), count = 50, asked = 50, oldest = 300, cap = 50)
        assertTrue(fixed.bounded)
        assertFalse(fixed.grows)
        assertFalse(mergeParts(listOf(fixed), 25).second)
        // A short page is complete.
        val complete = sourcePage(listOf(t(true, "1", 500)), count = 1, asked = 25, oldest = 500)
        assertFalse(complete.bounded)
        assertFalse(mergeParts(listOf(complete), 25).second)
    }

    @Test fun loadMoreCountsThePageBeforeSpamIsLeftOut() {
        // 25 entries came back, all filtered out as spam: there may still be real ones further back.
        val page = sourcePage(emptyList(), count = 25, asked = 25, oldest = 300)
        assertTrue(mergeParts(listOf(page), 25).second)
    }

    @Test fun moreThanTheLimitIsTrimmedAndOffersMore() {
        val all = Part((1..30).map { t(true, "$it", it * 1_000L) })
        val (items, more) = mergeParts(listOf(all), 25)
        assertEquals(25, items.size)
        assertEquals(30_000L, items.first().time)
        assertTrue(more)
    }

    // ---------- Contract check ----------

    @Test fun codeKinds() {
        assertEquals(CodeKind.NONE, codeKind("0x"))
        assertEquals(CodeKind.NONE, codeKind(""))
        assertEquals(CodeKind.DELEGATED, codeKind("0xef0100" + "63c0c19a282a1b52b07dd5a65b58948a07dae32b"))
        assertEquals(CodeKind.DELEGATED, codeKind("0xEF0100" + "63C0C19A282A1B52B07DD5A65B58948A07DAE32B"))
        assertEquals(CodeKind.CONTRACT, codeKind("0x6080604052348015600f57600080fd5b50"))
        // Not exactly a 20-byte address after the marker: a contract.
        assertEquals(CodeKind.CONTRACT, codeKind("0xef0100" + "63c0c19a282a1b52b07dd5a65b58948a07dae3"))
    }
}
