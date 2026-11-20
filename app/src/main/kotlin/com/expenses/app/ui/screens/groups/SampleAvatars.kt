package com.expenses.app.ui.screens.groups

import com.expenses.app.ui.components.AvatarColor
import com.expenses.app.ui.components.AvatarColors
import com.expenses.app.ui.components.AvatarGroup
import com.expenses.app.ui.components.AvatarPlace

/**
 * The colours of the design's sample people and groups, given out by the app's own rule ([AvatarColors.of]) and not
 * set by hand, so the previews show what the app would show for the same groups: Flat 302 was made first with Rahul,
 * Priya and Kiran, then Goa trip with Dev and Meera as well, then Office lunch.
 *
 * One sample comes out differently from the drawing, which the rule cannot give: Meera is yellow (42 draws her
 * purple, which is the person's own colour).
 */
object SampleAvatars {
    private const val ME = "arjun"
    private const val FLAT_302 = "flat-302"
    private const val GOA_TRIP = "goa-trip"
    private const val OFFICE_LUNCH = "office-lunch"

    private val me = AvatarPlace(memberId = ME, personKeys = listOf(ME), isSelf = true)

    private fun friend(id: String) = AvatarPlace(memberId = id, personKeys = listOf(id))

    /** The sample groups, oldest first, each with its people in the order they were added. */
    private val colors: AvatarColors = AvatarColors.of(
        listOf(
            AvatarGroup(FLAT_302, named = true, places = listOf(me, friend("rahul"), friend("priya"), friend("kiran"))),
            AvatarGroup(GOA_TRIP, named = true, places = listOf(me, friend("rahul"), friend("kiran"), friend("dev"), friend("meera"))),
            AvatarGroup(OFFICE_LUNCH, named = true, places = listOf(me)),
        ),
    )

    /** Arjun, the person (the design's purple). */
    val arjun: AvatarColor = colors.ofMember(ME)

    /** Rahul (green in the design). */
    val rahul: AvatarColor = colors.ofPerson("rahul")

    /** Priya (pink in the design). */
    val priya: AvatarColor = colors.ofPerson("priya")

    /** Kiran (blue in the design). */
    val kiran: AvatarColor = colors.ofPerson("kiran")

    /** Dev (orange in the design). */
    val dev: AvatarColor = colors.ofPerson("dev")

    /** Meera: yellow by the rule; 42 draws her purple. */
    val meera: AvatarColor = colors.ofPerson("meera")

    /** Flat 302 (green in the design). */
    val flat302: AvatarColor = colors.ofGroup(FLAT_302)

    /** Goa trip (yellow in the design). */
    val goaTrip: AvatarColor = colors.ofGroup(GOA_TRIP)

    /** Office lunch (blue in the design). */
    val officeLunch: AvatarColor = colors.ofGroup(OFFICE_LUNCH)
}
