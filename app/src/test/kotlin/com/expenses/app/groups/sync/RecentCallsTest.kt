package com.expenses.app.groups.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the app posted and removed a moment ago is believed over the system's list, which is behind for that moment
 * ([RecentCalls]; docs/server.md 9.7 "One bundle").
 */
class RecentCallsTest {
    private var now = 5_000L
    private val calls = RecentCalls { now }
    private val nothing = emptySet<Int>()

    @Test fun aNotificationJustPostedCountsBeforeTheSystemListsIt() {
        calls.posted(1)

        assertEquals(setOf(1), calls.correct(nothing))
        assertEquals(setOf(1, 2), calls.correct(setOf(2)))
    }

    @Test fun onceTheSystemListedItTheListIsRight() {
        calls.posted(1)
        assertEquals(setOf(1), calls.correct(setOf(1)))

        // Gone right after, without a call of the app's own.
        assertEquals(nothing, calls.correct(nothing))
    }

    @Test fun aNotificationNeverListedCountsForAMomentOnly() {
        calls.posted(1)
        now += RecentCalls.SETTLE_MS
        assertEquals(setOf(1), calls.correct(nothing))

        // The system would have listed it by now: it is not there.
        now += 1
        assertEquals(nothing, calls.correct(nothing))
    }

    @Test fun aNotificationJustRemovedIsGoneWhileTheSystemStillListsIt() {
        calls.cancelled(1)
        assertEquals(setOf(2), calls.correct(setOf(1, 2)))

        // Still listed after the moment: it is there again (posted from somewhere else), so the list is right.
        now += RecentCalls.SETTLE_MS + 1
        assertEquals(setOf(1, 2), calls.correct(setOf(1, 2)))
    }

    @Test fun postedAgainAfterBeingRemovedItCounts() {
        calls.cancelled(1)
        calls.posted(1)

        assertEquals(setOf(1), calls.correct(nothing))
    }

    @Test fun removedRightAfterBeingPostedItIsGone() {
        calls.posted(1)
        calls.cancelled(1)

        // Whether the system has applied neither call, or only the first.
        assertEquals(nothing, calls.correct(nothing))
        assertEquals(nothing, calls.correct(setOf(1)))
    }
}
