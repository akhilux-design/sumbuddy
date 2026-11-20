package com.expenses.app.ui.components

import com.expenses.app.ui.screens.groups.SampleAvatars
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The one rule for the colour behind an initial ([AvatarColors.of]): people by first appearance, groups by age. */
class AvatarColorTest {
    private val me = AvatarPlace(memberId = "me", personKeys = listOf("user-me"), isSelf = true)

    /** A friend's place: [memberId], held by the person known by [keys] (the place's own id when none is given). */
    private fun place(memberId: String, vararg keys: String) =
        AvatarPlace(memberId = memberId, personKeys = keys.toList().ifEmpty { listOf(memberId) })

    private fun group(id: String, vararg places: AvatarPlace) = AvatarGroup(id, named = true, places = places.toList())

    private fun direct(id: String, vararg places: AvatarPlace) = AvatarGroup(id, named = false, places = places.toList())

    @Test fun theFirstFivePeopleAllDifferInTheDesignsOrder() {
        val colors = AvatarColors.of(listOf(group("flat", me, place("rahul"), place("priya"), place("kiran"), place("dev"), place("meera"))))

        val five = listOf("rahul", "priya", "kiran", "dev", "meera").map(colors::ofMember)
        assertEquals(listOf(AvatarColor.Green, AvatarColor.Pink, AvatarColor.Blue, AvatarColor.Orange, AvatarColor.Yellow), five)
        assertEquals(5, five.toSet().size)
    }

    @Test fun theSixthPersonStartsAgainAtTheFirstColour() {
        val people = (1..11).map { place("friend-$it") }
        val colors = AvatarColors.of(listOf(group("big", me, *people.toTypedArray())))

        assertEquals(colors.ofMember("friend-1"), colors.ofMember("friend-6"))
        assertEquals(colors.ofMember("friend-1"), colors.ofMember("friend-11"))
        assertEquals(colors.ofMember("friend-2"), colors.ofMember("friend-7"))
    }

    @Test fun peopleAreCountedOverGroupsOldestFirstInStoredOrder() {
        // Kiran is stored before Rahul in the newer group, but Rahul was seen first, in the older one.
        val colors = AvatarColors.of(
            listOf(
                group("flat", me, place("flat-rahul", "rahul"), place("flat-priya", "priya")),
                group("goa", place("goa-kiran", "kiran"), me, place("goa-rahul", "rahul"), place("goa-dev", "dev")),
            ),
        )

        assertEquals(AvatarColor.Green, colors.ofPerson("rahul"))
        assertEquals(AvatarColor.Pink, colors.ofPerson("priya"))
        assertEquals(AvatarColor.Blue, colors.ofPerson("kiran"))
        assertEquals(AvatarColor.Orange, colors.ofPerson("dev"))
    }

    @Test fun theSamePersonInTwoGroupsHasOneColour() {
        val colors = AvatarColors.of(
            listOf(
                group("flat", me, place("flat-rahul", "rahul"), place("flat-priya", "priya")),
                group("goa", me, place("goa-kiran", "kiran"), place("goa-priya", "priya")),
            ),
        )

        assertEquals(AvatarColor.Pink, colors.ofMember("flat-priya"))
        assertEquals(AvatarColor.Pink, colors.ofMember("goa-priya"))
        assertEquals(AvatarColor.Pink, colors.ofPerson("priya"))
        // She takes no second colour: Kiran is the third person.
        assertEquals(AvatarColor.Blue, colors.ofMember("goa-kiran"))
    }

    @Test fun aFriendWhoJoinsTheAppInTheirPlaceKeepsTheColour() {
        fun colors(priyasKey: String) = AvatarColors.of(listOf(group("flat", me, place("m-rahul", "user-rahul"), place("m-priya", priyasKey), place("m-kiran", "name:kiran"))))
        val asFriend = colors("+919000000001")
        // The same place, now held by an app user: the key went from her phone number to her user id.
        val joined = colors("user-priya")

        assertEquals(AvatarColor.Pink, asFriend.ofMember("m-priya"))
        assertEquals(AvatarColor.Pink, joined.ofMember("m-priya"))
        assertEquals(AvatarColor.Pink, joined.ofPerson("user-priya"))
        // Nobody else moved.
        listOf("m-rahul", "m-kiran").forEach { assertEquals(asFriend.ofMember(it), joined.ofMember(it)) }
    }

    @Test fun anUndoneJoinKeepsTheColour() {
        fun colors(holder: String) = AvatarColors.of(listOf(group("flat", me, place("m-rahul", "user-rahul"), place("m-priya", holder))))
        val before = colors("+919000000001")
        val taken = colors("user-vikram")
        val undone = colors("+919000000001")

        assertEquals(before.ofMember("m-priya"), taken.ofMember("m-priya"))
        assertEquals(before.ofMember("m-priya"), undone.ofMember("m-priya"))
        assertEquals(before, undone)
    }

    @Test fun someoneKnownFromAnEarlierPlaceKeepsThatPlacesColourWhenTheyJoinALaterOne() {
        // Priya is on the app in Goa trip and was first seen as a friend, by phone, in Flat 302: her place in Goa
        // trip is known by her user id and her phone number.
        val colors = AvatarColors.of(
            listOf(
                group("flat", me, place("flat-rahul", "user-rahul"), place("flat-priya", "+919000000001")),
                group("goa", me, place("goa-kiran", "user-kiran"), place("goa-priya", "user-priya", "+919000000001")),
            ),
        )

        assertEquals(AvatarColor.Pink, colors.ofMember("goa-priya"))
        assertEquals(AvatarColor.Pink, colors.ofPerson("user-priya"))
        assertEquals(AvatarColor.Blue, colors.ofMember("goa-kiran"))
    }

    @Test fun groupsAreColouredByAge() {
        val groups = (1..6).map { group("group-$it", me) }
        val colors = AvatarColors.of(groups)

        assertEquals(
            listOf(AvatarColor.Green, AvatarColor.Yellow, AvatarColor.Blue, AvatarColor.Pink, AvatarColor.Orange, AvatarColor.Green),
            groups.map { colors.ofGroup(it.id) },
        )
    }

    @Test fun aSplitWithOneFriendHasTheFriendsColourAndTakesNoGroupColour() {
        val colors = AvatarColors.of(
            listOf(
                group("flat", me, place("flat-rahul", "rahul"), place("flat-priya", "priya")),
                direct("with-priya", me, place("direct-priya", "priya")),
                group("goa", me, place("goa-kiran", "kiran")),
            ),
        )

        assertEquals(AvatarColor.Pink, colors.ofGroup("with-priya"))
        assertEquals(AvatarColor.Green, colors.ofGroup("flat"))
        // The second named group, whatever came between.
        assertEquals(AvatarColor.Yellow, colors.ofGroup("goa"))
    }

    @Test fun thePersonIsAlwaysTheirOwnColourAndNobodyElseIs() {
        val people = (1..40).map { place("friend-$it") }
        val groups = listOf(
            group("flat", me, *people.take(20).toTypedArray()),
            direct("with-21", people[20], AvatarPlace("me-direct", listOf("user-me"), isSelf = true)),
            group("goa", *people.drop(21).toTypedArray(), AvatarPlace("me-goa", listOf("user-me"), isSelf = true)),
        )
        val colors = AvatarColors.of(groups)

        listOf("me", "me-direct", "me-goa").forEach { assertEquals(SelfAvatarColor, colors.ofMember(it)) }
        assertEquals(SelfAvatarColor, colors.ofPerson("user-me"))
        people.forEach { assertFalse(colors.ofMember(it.memberId) == SelfAvatarColor) }
        groups.forEach { assertFalse(colors.ofGroup(it.id) == SelfAvatarColor) }
        // Every other colour is in use.
        assertEquals(AvatarColor.entries.toSet() - SelfAvatarColor, people.map { colors.ofMember(it.memberId) }.toSet())
    }

    @Test fun someoneWhoSharesAKeyWithThePersonNeverTakesThePersonsColour() {
        // A friend typed in with the person's own phone number.
        val mine = AvatarPlace("me", listOf("user-me", "+919000000001"), isSelf = true)
        val colors = AvatarColors.of(listOf(group("flat", mine, place("m-twin", "+919000000001"))))

        assertEquals(AvatarColor.Green, colors.ofMember("m-twin"))
        assertEquals(AvatarColor.Green, colors.ofPerson("+919000000001"))
        assertEquals(SelfAvatarColor, colors.ofPerson("user-me"))
        assertEquals(SelfAvatarColor, colors.ofMember("me"))
    }

    @Test fun whatTheRuleHasNotSeenIsTheDesignsDefaultNeverThePersonsColour() {
        val colors = AvatarColors.of(listOf(group("flat", me, place("rahul"))))

        listOf(colors.ofMember("nobody"), colors.ofPerson("nobody"), colors.ofGroup("nowhere"), AvatarColors.None.ofMember("me")).forEach {
            assertEquals(AvatarColor.Green, it)
        }
    }

    @Test fun theDesignsSamplesComeOutAsDrawn() {
        assertEquals(SelfAvatarColor, SampleAvatars.arjun)
        assertEquals(AvatarColor.Green, SampleAvatars.rahul)
        assertEquals(AvatarColor.Pink, SampleAvatars.priya)
        assertEquals(AvatarColor.Blue, SampleAvatars.kiran)
        assertEquals(AvatarColor.Orange, SampleAvatars.dev)
        assertEquals(AvatarColor.Green, SampleAvatars.flat302)
        assertEquals(AvatarColor.Yellow, SampleAvatars.goaTrip)
        assertEquals(AvatarColor.Blue, SampleAvatars.officeLunch)
        // The one the rule cannot give as drawn: 42 draws Meera in the person's own purple.
        assertEquals(AvatarColor.Yellow, SampleAvatars.meera)
    }
}
