package com.expenses.app.groups.match

import com.expenses.app.groups.data.GroupKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which friend money received may be from (docs/server.md 9.5). Plain data: no database. */
class CreditMatchingTest {
    private fun transfer(group: String, member: String, name: String, amount: Long, key: String, upi: String? = null) =
        OwedTransfer(group, GroupKind.GROUP, "Group $group", "me-$group", member, name, key, upi, amount)

    private val rahul = OwingFriend(
        "rahul",
        "Rahul Mehta",
        listOf(transfer("g1", "r1", "Rahul Mehta", 120_000, "rahul", "rahul@okhdfc"), transfer("g2", "r2", "Rahul Mehta", 80_000, "rahul")),
    )
    private val priya = OwingFriend("priya", "Priya", listOf(transfer("g1", "p1", "Priya", 120_000, "priya")))

    @Test fun theFirstNameIsAWordOfTheSmsName() {
        assertTrue(CreditMatching.nameMatches(rahul, "RAHUL M", null))
        assertTrue(CreditMatching.nameMatches(rahul, "Mr Rahul Mehta", null))
        assertFalse(CreditMatching.nameMatches(rahul, "RAHULKUMAR", null))
        assertFalse(CreditMatching.nameMatches(rahul, "MEHTA ENTERPRISES", null))
        assertFalse(CreditMatching.nameMatches(rahul, null, null))
    }

    @Test fun theFriendsUpiIdOrItsNamePartMatches() {
        assertTrue(CreditMatching.nameMatches(rahul, null, "RAHUL@OKHDFC"))
        assertTrue(CreditMatching.nameMatches(rahul, "UPI CREDIT", "rahul.mehta@okaxis"))
        assertFalse(CreditMatching.nameMatches(rahul, "UPI CREDIT", "9000000001@ybl"))
    }

    @Test fun everythingOwedOrOneGroupsAmountMakesACandidate() {
        val all = CreditMatching.match(200_000, "Rahul Mehta", null, listOf(priya, rahul))
        assertEquals(CreditMatch.Clear(rahul), all)
        val one = CreditMatching.match(80_000, "Rahul", null, listOf(priya, rahul)) as CreditMatch.Clear
        assertEquals(listOf("g2"), one.friend.transfers.map { it.groupId })
        assertEquals(CreditMatch.None, CreditMatching.match(50_000, "Rahul", null, listOf(priya, rahul)))
        assertEquals(CreditMatch.None, CreditMatching.match(0, "Rahul", null, listOf(priya, rahul)))
    }

    @Test fun twoCandidatesClearOnlyTheOneNamed() {
        val named = CreditMatching.match(120_000, "PRIYA SHARMA", null, listOf(priya, rahul))
        assertEquals(CreditMatch.Clear(priya), named)
        val unclear = CreditMatching.match(120_000, "IMPS 1234", null, listOf(priya, rahul))
        assertTrue(unclear is CreditMatch.Ask)
    }
}
