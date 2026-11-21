package com.expenses.app.sms

import android.app.Application
import com.expenses.app.ingest.SmsTimes
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * From the review of f8cf0b9. A small timeline model of IncomingSmsWorker: one read per SMS broadcast, first run
 * [START] ms after it, retries 10, 20, 30, 40, 50 s apart; each run marks every unread inbox message read and stops
 * when the rule says its SMS arrived. The f8cf0b9 rule is kept here as it was in the app; with it the model gave the
 * same 12 runs as the real worker (IncomingSmsWorkerAdversarialTest) and the emulator (work 74177043 and 606a5f28).
 * The rule in the app now is the real [IncomingSmsWorker.isTheSms] ([app]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ArrivalRuleModelTest {
    /** One SMS: broadcast time, when the SMS app wrote it, its inbox date and date_sent, the broadcast's PDU times. */
    data class Sms(val name: String, val broadcast: Long, val written: Long, val date: Long, val dateSent: Long?, val pdu: Set<Long>, val hasRead: Boolean = true)

    /** A rule: (messages this run read first, messages read before, broadcast time, PDU times, attempt) -> arrived. */
    fun interface Rule { fun arrived(first: List<Sms>, before: List<Sms>, at: Long, pdu: Set<Long>, attempt: Int): Boolean }

    data class Outcome(val runs: Int, val unread: List<String>)

    private fun simulate(messages: List<Sms>, rule: Rule): Outcome {
        val readBy = HashMap<String, Int>()
        data class Run(val time: Long, val worker: Int, val attempt: Int)
        val queue = ArrayList<Run>()
        messages.forEachIndexed { i, m -> if (m.hasRead) queue += Run(m.broadcast + START, i, 0) }
        var runs = 0
        while (queue.isNotEmpty()) {
            val run = queue.minBy { it.time }
            queue.remove(run)
            runs++
            val inbox = messages.filter { it.written <= run.time }
            val first = inbox.filter { it.name !in readBy }
            first.forEach { readBy[it.name] = run.worker }
            val before = inbox.filter { it !in first }
            val own = messages[run.worker]
            val done = rule.arrived(first, before, own.broadcast, own.pdu, run.attempt)
            if (!done && run.attempt < IncomingSmsWorker.MAX_ATTEMPTS) {
                queue += Run(run.time + 10_000L * (run.attempt + 1), run.worker, run.attempt + 1)
            }
        }
        return Outcome(runs, messages.filter { it.name !in readBy }.map { it.name })
    }

    private val a0d0e6b = Rule { first, _, at, _, _ -> first.any { it.date >= at - MARGIN } }

    /** f8cf0b9: when both sides have a service-centre time, it alone decides. */
    private val f8cf0b9 = Rule { first, _, at, pdu, _ ->
        first.any { if (it.dateSent != null && pdu.isNotEmpty()) it.dateSent in pdu else it.date >= at - MARGIN }
    }

    /** The rule in the app: the real [IncomingSmsWorker.isTheSms]. */
    private val app = Rule { first, _, at, pdu, _ ->
        first.any {
            IncomingSmsWorker.isTheSms(
                SmsTimes(Instant.ofEpochMilli(it.date), it.dateSent?.let(Instant::ofEpochMilli)),
                Instant.ofEpochMilli(at),
                pdu.map(Instant::ofEpochMilli).toSet(),
            )
        }
    }
    /** Proposed: a matching date_sent is enough, and so is the two-minute rule (a0d0e6b) when it does not match. */
    private val proposed = Rule { first, _, at, pdu, _ ->
        first.any { (it.dateSent != null && it.dateSent in pdu) || it.date >= at - MARGIN }
    }

    /**
     * Option D, for comparison: f8cf0b9, and from the first retry on also a message read before whose date_sent is
     * one of the broadcast's (the SMS another read took first).
     */
    private val optionD = Rule { first, before, at, pdu, attempt ->
        f8cf0b9.arrived(first, before, at, pdu, attempt) || (attempt > 0 && before.any { it.dateSent != null && it.dateSent in pdu })
    }

    /** An SMS whose broadcast is at [t] ms, written [write] ms later, stamped by the service centre at [sc] ms. */
    private fun sms(name: String, t: Long, sc: Long, write: Long = WRITE, hasRead: Boolean = true, appSent: Long? = sc, date: Long = t + write - 100) =
        Sms(name, t, t + write, date, appSent, setOf(sc), hasRead)

    // Scenarios, times in ms.
    private val crossedPair = listOf(sms("P", 0, 0), sms("Q", 1_250, 2_000))
    private val noReadOfItsOwn = listOf(sms("U", -30_000, -30_000, hasRead = false), sms("X", 0, 0))
    private val sameSecondTriple = listOf(sms("A", 0, 1_000), sms("B", 100, 1_000), sms("C", 220, 1_000))
    private val sameSecondSecondWrittenLate = listOf(sms("A", 0, 0), sms("B", 500, 0, write = 15_000))
    private val lone = listOf(sms("L", 0, 0))
    private val appRecordsArrivalAsDateSent = listOf(sms("L", 0, 0, appSent = 50))
    private val inboxDateBeforeTheMargin = listOf(sms("E", 0, -180_000, date = -180_000))
    private val anotherReadTookIt = listOf(sms("A", 0, 0), sms("B", 80, 1_000))
    private val pairWhenAppRecordsArrival = listOf(sms("P", 0, 0, appSent = 50), sms("Q", 1_250, 2_000, appSent = 1_300))

    @Test fun theModelMatchesTheRealWorkerAndTheEmulator() {
        assertEquals(Outcome(12, emptyList()), simulate(crossedPair, f8cf0b9))
        assertEquals(Outcome(3, emptyList()), simulate(crossedPair, a0d0e6b))
        assertEquals(Outcome(3, emptyList()), simulate(crossedPair, app))
    }

    @Test fun compareTheRules() {
        val scenarios = linkedMapOf(
            "crossed pair, 1.25 s apart" to crossedPair,
            "unread SMS with no read of its own, 30 s before" to noReadOfItsOwn,
            "three SMS in one second" to sameSecondTriple,
            "same second, second SMS written 15 s late" to sameSecondSecondWrittenLate,
            "lone SMS" to lone,
            "SMS app records another date_sent" to appRecordsArrivalAsDateSent,
            "crossed pair, SMS app records another date_sent" to pairWhenAppRecordsArrival,
            "inbox date 3 min before the broadcast" to inboxDateBeforeTheMargin,
            "pair 0.08 s apart, one read takes both" to anotherReadTookIt,
        )
        val rules = linkedMapOf("a0d0e6b" to a0d0e6b, "f8cf0b9" to f8cf0b9, "proposed" to proposed, "optionD" to optionD, "app" to app)
        val table = StringBuilder()
        for ((name, messages) in scenarios) {
            table.append(name.padEnd(52))
            for ((ruleName, rule) in rules) {
                val o = simulate(messages, rule)
                table.append("  $ruleName: ${o.runs} runs, unread ${o.unread}".padEnd(34))
            }
            table.append('\n')
        }
        println(table)
    }

    @Test fun theProposedRuleIsNeverWorseThanA0d0e6b() {
        for (messages in listOf(crossedPair, sameSecondTriple, sameSecondSecondWrittenLate, lone, appRecordsArrivalAsDateSent, pairWhenAppRecordsArrival, inboxDateBeforeTheMargin, anotherReadTookIt)) {
            val proposedOutcome = simulate(messages, proposed)
            assertTrue(proposedOutcome.unread.isEmpty())
            assertTrue(proposedOutcome.runs <= simulate(messages, a0d0e6b).runs)
            assertEquals(proposedOutcome, simulate(messages, app))
        }
        // The case f8cf0b9 closes stays open, as in a0d0e6b: X waits for the next SMS or app start.
        assertEquals(listOf("X"), simulate(noReadOfItsOwn, proposed).unread)
        assertEquals(listOf("X"), simulate(noReadOfItsOwn, app).unread)
        assertEquals(emptyList<String>(), simulate(noReadOfItsOwn, f8cf0b9).unread)
        // The one gain of date_sent stays: an inbox date 3 min before the broadcast takes 2 runs, not 6.
        assertEquals(Outcome(2, emptyList()), simulate(inboxDateBeforeTheMargin, app))
        assertEquals(Outcome(6, emptyList()), simulate(inboxDateBeforeTheMargin, a0d0e6b))
    }

    private companion object {
        const val START = 40L
        const val WRITE = 150L
        const val MARGIN = 120_000L
    }
}
