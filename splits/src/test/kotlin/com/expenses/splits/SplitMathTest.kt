package com.expenses.splits

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SplitMathTest {
    @Test fun equalGivesTheOddPaiseToTheLowestIds() {
        val shares = SplitMath.shares(SplitMethod.EQUAL, 100, mapOf("c" to null, "a" to null, "b" to null))
        assertEquals(listOf("a", "b", "c"), shares.keys.toList())
        assertEquals(listOf(34L, 33L, 33L), shares.values.toList())
    }

    @Test fun equalOfAnAmountThatDividesGivesTheSameToEveryone() {
        val shares = SplitMath.shares(SplitMethod.EQUAL, 45_000, mapOf("x" to null, "y" to null, "z" to null))
        assertEquals(listOf(15_000L, 15_000L, 15_000L), shares.values.toList())
    }

    @Test fun amountsAreTakenAsTyped() {
        val shares = SplitMath.shares(SplitMethod.AMOUNTS, 1_000, mapOf("b" to 400L, "a" to 600L))
        assertEquals(mapOf("a" to 600L, "b" to 400L), shares)
    }

    @Test fun percentGivesLeftoverPaiseToTheLargestRemainders() {
        // 1001 × 3333 = 3,336,333 → 333 r 6333; same for b; c: 1001 × 3334 = 3,337,334 → 333 r 7334.
        // Σ = 999, so 2 paise go to the largest remainders: c (7334), then a (6333, lower id than b).
        val shares = SplitMath.shares(SplitMethod.PERCENT, 1_001, mapOf("a" to 3_333L, "b" to 3_333L, "c" to 3_334L))
        assertEquals(mapOf("a" to 334L, "b" to 333L, "c" to 334L), shares)
        assertEquals(1_001L, shares.values.sum())
    }

    @Test fun sharesSplitByWeight() {
        // 100 × 2 / 3 = 66 r 2 and 100 × 1 / 3 = 33 r 1: one paisa left, to a.
        val shares = SplitMath.shares(SplitMethod.SHARES, 100, mapOf("a" to 2L, "b" to 1L))
        assertEquals(mapOf("a" to 67L, "b" to 33L), shares)
    }

    @Test fun sharesTiesGoToTheLowerId() {
        val shares = SplitMath.shares(SplitMethod.SHARES, 10, mapOf("b" to 1L, "a" to 1L, "c" to 1L))
        assertEquals(mapOf("a" to 4L, "b" to 3L, "c" to 3L), shares)
    }

    @Test fun theLargestAmountDoesNotOverflow() {
        val shares = SplitMath.shares(
            SplitMethod.SHARES,
            SplitMath.MAX_AMOUNT_MINOR,
            (1..50).associate { "m%02d".format(it) to 1_000L },
        )
        assertEquals(SplitMath.MAX_AMOUNT_MINOR, shares.values.sum())
    }

    @Test fun checkFindsWhatTheServerRefuses() {
        assertEquals(SplitProblem.AMOUNTS_DO_NOT_ADD_UP, SplitMath.check(SplitMethod.AMOUNTS, 100, mapOf("a" to 40L)))
        assertEquals(SplitProblem.PERCENTS_DO_NOT_ADD_UP, SplitMath.check(SplitMethod.PERCENT, 100, mapOf("a" to 9_000L)))
        assertEquals(SplitProblem.INPUT_OUT_OF_RANGE, SplitMath.check(SplitMethod.SHARES, 100, mapOf("a" to 0L)))
        assertEquals(SplitProblem.INPUT_OUT_OF_RANGE, SplitMath.check(SplitMethod.SHARES, 100, mapOf("a" to 1_001L)))
        assertEquals(SplitProblem.INPUT_NOT_EXPECTED, SplitMath.check(SplitMethod.EQUAL, 100, mapOf("a" to 1L)))
        assertEquals(SplitProblem.AMOUNT_OUT_OF_RANGE, SplitMath.check(SplitMethod.EQUAL, 0, mapOf("a" to null)))
        assertEquals(SplitProblem.NO_PARTICIPANTS, SplitMath.check(SplitMethod.EQUAL, 100, emptyMap()))
        assertNull(SplitMath.check(SplitMethod.AMOUNTS, 100, mapOf("a" to 100L, "b" to 0L)))
    }

    @Test fun leftToAssignCountsBlankFieldsAsZero() {
        assertEquals(5_000L, SplitMath.leftToAssign(45_000, listOf(20_000L, null, 20_000L)))
        assertEquals(-1L, SplitMath.leftToAssign(100, listOf(101L)))
    }
}

class BalancesTest {
    @Test fun netsAddUpToZero() {
        val nets = Balances.nets(
            expenses = listOf(
                BalanceExpense("a", 45_000, mapOf("a" to 15_000L, "b" to 15_000L, "c" to 15_000L)),
                BalanceExpense("b", 9_000, mapOf("a" to 4_500L, "b" to 4_500L)),
            ),
            settlements = listOf(BalanceSettlement(from = "c", to = "a", amountMinor = 5_000)),
        )
        // a: 45,000 paid - 15,000 - 4,500 shares - 5,000 received back; c: -15,000 share + 5,000 paid back.
        assertEquals(mapOf("a" to 20_500L, "b" to -10_500L, "c" to -10_000L), nets)
        assertEquals(0L, nets.values.sum())
    }

    @Test fun simplifyPaysTheLargestCreditorFirst() {
        val transfers = Balances.simplify(mapOf("a" to 25_500L, "b" to -10_500L, "c" to -15_000L))
        assertEquals(
            listOf(Transfer("c", "a", 15_000), Transfer("b", "a", 10_500)),
            transfers,
        )
    }

    @Test fun simplifyBreaksTiesByLowerId() {
        val transfers = Balances.simplify(mapOf("d" to -100L, "c" to -100L, "b" to 100L, "a" to 100L))
        assertEquals(listOf(Transfer("c", "a", 100), Transfer("d", "b", 100)), transfers)
    }

    @Test fun settledGroupsHaveNoTransfers() {
        assertEquals(emptyList<Transfer>(), Balances.simplify(mapOf("a" to 0L, "b" to 0L)))
    }
}

class DirectIdsTest {
    @Test fun uuidV5MatchesTheRfcExample() {
        val dns = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8")
        assertEquals("886313e1-3b8a-5372-9b90-0c9aee199e5d", DirectIds.uuidV5(dns, "python.org").toString())
    }

    @Test fun directIdsDoNotDependOnOrder() {
        val a = "0b7c1d2e-0000-4000-8000-000000000001"
        val b = "9f8e7d6c-0000-4000-8000-000000000002"
        // Expected values made with Python's uuid.uuid5.
        assertEquals("6e3114ef-102c-5e7f-b96f-da68b89ef8a7", DirectIds.groupId(a, b))
        assertEquals(DirectIds.groupId(a, b), DirectIds.groupId(b, a))
        val group = DirectIds.groupId(a, b)
        assertEquals("41da8195-5953-5cf9-9b3b-33fabd008a7d", DirectIds.memberId(group, a))
        assertEquals("5d9c059c-47ef-5fc3-a268-ed97160d62d4", DirectIds.memberId(group, b))
    }
}
