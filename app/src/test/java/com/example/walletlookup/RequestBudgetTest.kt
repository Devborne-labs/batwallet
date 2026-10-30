package com.example.walletlookup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.SocketTimeoutException

/** Background searches run inside a time budget that every request honours (see withRequestBudget). */
class RequestBudgetTest {
    @Test fun outsideABudgetRequestsKeepTheNormalTimeouts() {
        assertEquals(15_000 to 20_000, requestTimeouts())
    }

    @Test fun insideABudgetRequestsUseShortTimeouts() = runBlocking {
        val inside = withRequestBudget(60_000) { requestTimeouts() }
        assertEquals(4_000 to 6_000, inside)
        // And back to normal after it.
        assertEquals(15_000 to 20_000, requestTimeouts())
    }

    @Test fun timeoutsNeverReachPastTheDeadline() = runBlocking {
        val inside = withRequestBudget(1_000) { requestTimeouts() }!!
        assertTrue(inside.first <= 1_000 && inside.second <= 1_000)
    }

    @Test fun noRequestStartsOnceTheBudgetIsSpent() = runBlocking {
        withRequestBudget(50) {
            delay(150) // the deadline has passed, though the extra second's grace hasn't
            try {
                requestTimeouts()
                fail("a request started after its deadline")
            } catch (e: SocketTimeoutException) {
                // A request's own timeout reads as "took too long" to the person, like any slow service.
                assertEquals("The service took too long to answer.", failureReason(e))
            }
        }
        Unit
    }

    @Test fun theBudgetGoesAlongIntoRequestsOnOtherThreads() = runBlocking {
        val (onIo, inParallel) = withRequestBudget(60_000) {
            val io = withContext(Dispatchers.IO) { requestTimeouts() }
            // As history() does: parallel requests launched from its own scope, which carries the budget.
            val parallel = coroutineScope { async(Dispatchers.IO) { requestTimeouts() }.await() }
            io to parallel
        }!!
        assertEquals(4_000 to 6_000, onIo)
        assertEquals(4_000 to 6_000, inParallel)
    }

    @Test fun workThatRunsPastItsBudgetEndsWithNothing() = runBlocking {
        assertNull(withRequestBudget(50) { delay(5_000); "too late" })
    }

    @Test fun aRequestToANodeThatTricklesItsAnswerIsCutOffAtTheDeadline() = runBlocking {
        // A server that answers one byte every 300 ms and never finishes: each read gets something before its own
        // timeout, so only the deadline itself can end the request.
        val server = java.net.ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress())
        val feeder = Thread {
            runCatching {
                while (true) {
                    val client = server.accept()
                    Thread {
                        runCatching {
                            val out = client.getOutputStream()
                            val head = "HTTP/1.1 200 OK\r\nContent-Length: 100000\r\n\r\n"
                            for (ch in head + "x".repeat(100_000)) { out.write(ch.code); out.flush(); Thread.sleep(300) }
                        }
                    }.apply { isDaemon = true }.start()
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            val started = System.currentTimeMillis()
            var outcome: Throwable? = null
            withRequestBudget(1_000) {
                withContext(Dispatchers.IO) {
                    try {
                        http("http://127.0.0.1:${server.localPort}/")
                    } catch (e: Exception) {
                        outcome = e
                    }
                }
            }
            val took = System.currentTimeMillis() - started
            assertTrue("ended with $outcome", outcome is OutOfTime)
            assertTrue("took $took ms", took < 2_500)
        } finally {
            server.close()
            feeder.interrupt()
        }
    }

    @Test fun aBudgetCanBeHandedToAPlainThread() = runBlocking {
        val seen = withRequestBudget(60_000) {
            val budget = currentRequestBudget()
            var onThread: Pair<Int, Int>? = null
            val t = Thread { onThread = inRequestBudget(budget) { requestTimeouts() } }
            t.start(); t.join()
            onThread
        }
        assertEquals(4_000 to 6_000, seen)
        // Without it, a pool thread would use the normal timeouts.
        var plain: Pair<Int, Int>? = null
        val t = Thread { plain = inRequestBudget(null) { requestTimeouts() } }
        t.start(); t.join()
        assertEquals(15_000 to 20_000, plain)
    }

    @Test fun timeLeftIsMeasuredInsideABudgetOnly() = runBlocking {
        assertEquals(Long.MAX_VALUE, requestTimeLeft())
        val left = withRequestBudget(10_000) { requestTimeLeft() }!!
        assertTrue(left in 1..10_000)
    }
}
