package com.expenses.app.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.expenses.app.ui.components.core.AppText
import com.expenses.app.ui.theme.AppTheme
import com.expenses.app.ui.theme.Space

/** What the amount in a [BalanceRow] means. It only sets the colour of the amount. */
enum class BalanceRowDirection {
    /** The person or group owes the user money. The amount is green. */
    OwesYou,

    /** The user owes the person or group money. The amount is red. */
    YouOwe,

    /** Nothing is owed either way. The amount is grey. */
    Settled,

    /**
     * Not a debt, just an amount, for example what one family member spent. The amount is in the normal text
     * colour.
     */
    Plain,
}

/**
 * One person's or group's balance in a list: a round avatar, a name with a detail line, and the amount on the
 * right. The row is flat (no raised edge), fills the width it is given and grows taller when the name or detail
 * wraps.
 *
 * The direction changes only the colour of the amount, so the words must carry it too: put the sign in [amount]
 * ("+₹1,200", "-₹800", "₹0") and say it in [detail] ("owes you", "you owe", "all settled").
 *
 * @param name the person or group, for example "Rahul" or "Flat 302".
 * @param detail second line under the name, for example "owes you" or "3 people · owes you". Wraps when it is long.
 * @param amount the amount, already formatted and with its sign, for example "+₹1,200". Always shown whole on
 * one line.
 * @param initial the letter in the avatar, for example "R". It is not always the first letter of [name]: the
 * user's own row is named "You" and shows the initial of their real name.
 * @param modifier layout changes from the caller, applied to the whole row.
 * @param direction who owes whom; see [BalanceRowDirection].
 * @param avatarColor colour of the avatar circle; see [AvatarColor].
 * @param onClick called when the row is tapped. Leave null for a row that cannot be tapped.
 */
@Composable
fun BalanceRow(
    name: String,
    detail: String,
    amount: String,
    initial: String,
    modifier: Modifier = Modifier,
    direction: BalanceRowDirection = BalanceRowDirection.OwesYou,
    avatarColor: AvatarColor = AvatarColor.Green,
    onClick: (() -> Unit)? = null,
) {
    FlatRow(
        title = name,
        subtitle = detail,
        leading = { Avatar(initial = initial, color = avatarColor) },
        modifier = modifier
            .flatRowSurface(AppTheme.colors.bgSurface)
            .rowClick(onClick)
            .padding(Space.s12),
        trailing = {
            AppText(text = amount, style = AppTheme.type.amountM, color = amountColor(direction), maxLines = 1)
        },
    )
}

/** Colour of the amount for each direction. */
@Composable
private fun amountColor(direction: BalanceRowDirection): Color = when (direction) {
    BalanceRowDirection.OwesYou -> AppTheme.colors.statusPositive
    BalanceRowDirection.YouOwe -> AppTheme.colors.statusNegative
    BalanceRowDirection.Settled -> AppTheme.colors.textTertiary
    BalanceRowDirection.Plain -> AppTheme.colors.textPrimary
}
