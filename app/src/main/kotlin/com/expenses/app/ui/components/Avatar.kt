package com.expenses.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.expenses.app.ui.components.core.AppText
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.Radius

/** Colour of the circle behind the initial in an [Avatar]. The names are the options of the Figma property `Color`. */
enum class AvatarColor {
    /** `color/brand/primary`. The default in the design. */
    Green,

    /** `color/accent/yellow`. */
    Yellow,

    /** `color/accent/pink`. */
    Pink,

    /** `color/accent/purple`. */
    Purple,

    /** `color/accent/blue`. */
    Blue,

    /** `color/accent/orange`. */
    Orange,
}

/** The person's own colour: their avatar on Home and You, and the "You" row of Split it (Arjun in the design). */
val SelfAvatarColor: AvatarColor = AvatarColor.Purple

/**
 * The colours of other people, in the order they are given out: the first friend is green, the second pink, the third
 * blue (the design draws Rahul green, Priya pink and Kiran blue), then orange and yellow, then green again. Purple is
 * left out: it is the person's own ([SelfAvatarColor]).
 */
private val PersonAvatarColors: List<AvatarColor> =
    listOf(AvatarColor.Green, AvatarColor.Pink, AvatarColor.Blue, AvatarColor.Orange, AvatarColor.Yellow)

/**
 * The colours of named groups, in the order they are given out: the oldest group is green, the next yellow, the third
 * blue (the design draws Flat 302 green, Goa trip yellow and Office lunch blue on 02-everyday/20, 44 and 85), then
 * pink and orange, then green again.
 */
private val GroupAvatarColors: List<AvatarColor> =
    listOf(AvatarColor.Green, AvatarColor.Yellow, AvatarColor.Blue, AvatarColor.Pink, AvatarColor.Orange)

/** The colour of a place or group the rule has not seen: the design's default. Never [SelfAvatarColor]. */
private val UnknownAvatarColor: AvatarColor = AvatarColor.Green

/**
 * One place in a group, as the colour rule sees it.
 *
 * @param memberId the place (the member's id). It stays the same when a friend joins the app in it and when that join
 * is undone.
 * @param personKeys who holds the place, across groups: the person's key first, then any other key the same person
 * is known by. Two places that share a key are one person.
 * @param isSelf true for the person's own place.
 */
data class AvatarPlace(val memberId: String, val personKeys: List<String>, val isSelf: Boolean = false)

/**
 * One group, as the colour rule sees it.
 *
 * @param id the group.
 * @param named true for a named group ("Flat 302"); false for a split with one friend, which is drawn with that
 * friend's avatar.
 * @param places the group's places, in the order they are stored.
 */
data class AvatarGroup(val id: String, val named: Boolean, val places: List<AvatarPlace>)

/**
 * The colour behind every initial, for everyone and every group on this phone at one moment. It is the one source of
 * avatar colours: every screen reads the same snapshot, so a person or a group has the same colour on Groups, the
 * Group page, Split it, Split. Sorted., Settle up, Mark as paid, Money received and All square.
 *
 * The rule ([of]):
 * - The person themself is always [SelfAvatarColor].
 * - Everyone else gets a colour by the order they first appear in: groups oldest first, places in stored order. The
 *   first person is green, then pink, blue, orange, yellow, and green again, so the first five people all differ.
 * - The colour belongs to the place where the person was first seen: a friend who joins the app in that place, and a
 *   join that is undone, keep it. Someone already seen in an earlier place has that place's colour everywhere.
 * - Named groups get a colour by age: the oldest is green, then yellow, blue, pink, orange, and green again. A split
 *   with one friend has that friend's colour.
 *
 * Nobody else is ever given [SelfAvatarColor].
 */
@Immutable
class AvatarColors private constructor(
    private val members: Map<String, AvatarColor>,
    private val people: Map<String, AvatarColor>,
    private val groups: Map<String, AvatarColor>,
) {
    /** The colour of the place [memberId]; the person's own places are [SelfAvatarColor]. */
    fun ofMember(memberId: String): AvatarColor = members[memberId] ?: UnknownAvatarColor

    /**
     * The colour of the person known by [personKey] (one of [AvatarPlace.personKeys]). A key that only the person
     * themself is known by gives [SelfAvatarColor].
     */
    fun ofPerson(personKey: String): AvatarColor = people[personKey] ?: UnknownAvatarColor

    /** The colour of the group [groupId]. */
    fun ofGroup(groupId: String): AvatarColor = groups[groupId] ?: UnknownAvatarColor

    override fun equals(other: Any?): Boolean =
        other is AvatarColors && members == other.members && people == other.people && groups == other.groups

    override fun hashCode(): Int = 31 * (31 * members.hashCode() + people.hashCode()) + groups.hashCode()

    /** Makes the snapshots. */
    companion object {
        /** No groups: every colour is the design's default. */
        val None: AvatarColors = AvatarColors(emptyMap(), emptyMap(), emptyMap())

        /**
         * The colours for [groups], which must be oldest first (creation time, then id), each with its places in
         * stored order. See [AvatarColors] for the rule.
         */
        fun of(groups: List<AvatarGroup>): AvatarColors {
            val slots = HashMap<String, Int>()
            val selfKeys = HashSet<String>()
            val members = HashMap<String, AvatarColor>()
            val groupColors = HashMap<String, AvatarColor>()
            var nextPerson = 0
            var nextGroup = 0
            for (group in groups) {
                var friend: AvatarColor? = null
                for (place in group.places) {
                    if (place.isSelf) {
                        members[place.memberId] = SelfAvatarColor
                        selfKeys += place.personKeys
                        continue
                    }
                    // Someone seen before keeps the slot of their first place; a new person takes the next one.
                    val slot = place.personKeys.firstNotNullOfOrNull { slots[it] } ?: nextPerson++
                    place.personKeys.forEach { key -> slots.getOrPut(key) { slot } }
                    val color = PersonAvatarColors[slot % PersonAvatarColors.size]
                    members[place.memberId] = color
                    if (friend == null) friend = color
                }
                groupColors[group.id] = when {
                    group.named -> GroupAvatarColors[nextGroup++ % GroupAvatarColors.size]
                    else -> friend ?: UnknownAvatarColor
                }
            }
            val people = HashMap<String, AvatarColor>()
            slots.forEach { (key, slot) -> people[key] = PersonAvatarColors[slot % PersonAvatarColors.size] }
            // A key someone else is known by too stays theirs: nobody else is ever drawn in the person's own colour.
            selfKeys.forEach { key -> people.getOrPut(key) { SelfAvatarColor } }
            return AvatarColors(members, people, groupColors)
        }
    }
}

/** Diameter of the avatar as drawn in the component. The initial is in the Title text style at this size. */
private val AvatarDesignSize = 40.dp

/**
 * A round avatar: one initial letter centred on a coloured circle. It has no border and no raised edge.
 *
 * The avatar is decoration by default: screen readers skip it, because the name of the person or group is written
 * next to it. Pass [contentDescription] when it stands alone.
 *
 * @param initial the letter to show, normally the first letter of the name in upper case, for example "R".
 * @param modifier layout changes from the caller, applied to the circle.
 * @param color colour of the circle; see [AvatarColor]. The letter is `color/text/on-brand` on every colour.
 * @param size diameter of the circle. Lists use the default 40dp. The design also uses the same avatar enlarged
 * (56dp on You, 64dp on Settle up); the letter grows with the circle exactly as it does there, and never goes
 * below the Title size.
 * @param contentDescription what screen readers say for the avatar, for example the person's name, or null when
 * the name is already written next to it.
 */
@Composable
fun Avatar(
    initial: String,
    modifier: Modifier = Modifier,
    color: AvatarColor = AvatarColor.Green,
    size: Dp = AvatarDesignSize,
    contentDescription: String? = null,
) {
    Box(
        modifier = modifier
            .size(size)
            .background(fillOf(color), RoundedCornerShape(Radius.full))
            .clearAndSetSemantics {
                if (contentDescription != null) this.contentDescription = contentDescription
            },
        contentAlignment = Alignment.Center,
    ) {
        // Unbounded and not clipped: at a very large font setting the line of text is taller than the circle,
        // and the letter must stay whole and centred instead of being cut off.
        AppText(
            text = initial,
            style = initialStyle(size),
            modifier = Modifier.wrapContentSize(unbounded = true),
            color = AppTheme.colors.textOnBrand,
            maxLines = 1,
            overflow = TextOverflow.Visible,
        )
    }
}

/** The circle colour token for each [AvatarColor]. */
@Composable
@ReadOnlyComposable
private fun fillOf(color: AvatarColor): Color = when (color) {
    AvatarColor.Green -> AppTheme.colors.brandPrimary
    AvatarColor.Yellow -> AppTheme.colors.accentYellow
    AvatarColor.Pink -> AppTheme.colors.accentPink
    AvatarColor.Purple -> AppTheme.colors.accentPurple
    AvatarColor.Blue -> AppTheme.colors.accentBlue
    AvatarColor.Orange -> AppTheme.colors.accentOrange
}

/** The Title text style, enlarged by the same factor as the circle when the avatar is bigger than 40dp. */
@Composable
@ReadOnlyComposable
private fun initialStyle(size: Dp): TextStyle {
    val title = AppTheme.type.title
    val scale = size / AvatarDesignSize
    if (scale <= 1f) return title
    return title.copy(fontSize = title.fontSize * scale, lineHeight = title.lineHeight * scale)
}
