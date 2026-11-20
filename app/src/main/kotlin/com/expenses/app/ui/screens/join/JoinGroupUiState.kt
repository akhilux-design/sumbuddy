package com.expenses.app.ui.screens.join

import androidx.compose.runtime.Immutable
import com.expenses.app.groups.data.JoinPreview
import com.expenses.app.ui.screens.signin.ActionProblem
import java.time.YearMonth

/** What Join group shows. */
sealed interface JoinGroupUiState {
    /** The group is being looked up: the loading shapes of 02-everyday/22. */
    data object Loading : JoinGroupUiState

    /**
     * The group of the link (02-everyday/32): who added the person, the group and what the friend's place owes.
     *
     * @param preview what the server said about the group.
     * @param month the current month, for "{k} expenses in {Month}".
     * @param joining true while "Join group" runs; taps wait for it.
     * @param problem the banner at the top of the body when the join did not happen, or null.
     * @param problemCount how many times a join was refused with [problem], so each refused tap plays `haptic/reject`,
     * also when the banner was already there.
     */
    @Immutable
    data class Ready(
        val preview: JoinPreview,
        val month: YearMonth,
        val joining: Boolean = false,
        val problem: ActionProblem? = null,
        val problemCount: Int = 0,
    ) : JoinGroupUiState

    /**
     * The group could not be looked up (the wording of 02-everyday/23, with Try again).
     *
     * @param offline true when no answer came ("Check your connection and try again. Nothing is lost.").
     */
    data class CouldNotLoad(val offline: Boolean) : JoinGroupUiState

    /** The link was turned off, used by someone else, or ran out (02-everyday/50). */
    data object LinkNoLongerWorks : JoinGroupUiState

    /**
     * The link is a family's (Join Family, 06-family/01; docs/family.md 1.4): who invited the person and to which
     * family, what is shared, and "Join Family".
     *
     * @param preview what the server said about the family (no amounts).
     * @param earlierCount how many of this phone's Family expenses joining shares; above 0 the list card says so (13).
     * @param joining true while "Join Family" runs; taps and Back wait for it.
     * @param problem the banner at the top of the body when the join did not happen (14, 15), or null.
     * @param problemCount how many times a join was refused with [problem], so each refused tap plays `haptic/reject`.
     */
    @Immutable
    data class ReadyFamily(
        val preview: JoinPreview,
        val earlierCount: Int = 0,
        val joining: Boolean = false,
        val problem: ActionProblem? = null,
        val problemCount: Int = 0,
    ) : JoinGroupUiState

    /** The person is already in a family, so a family link cannot be joined (06-family/16): "You're already in a family". */
    data object AlreadyInFamily : JoinGroupUiState
}

/** What the person does on Join group. */
sealed interface JoinGroupEvent {
    /** "Join group", or "Join Family". */
    data object Join : JoinGroupEvent

    /** "Not now", "OK" or Back: leave, nothing joined. */
    data object Leave : JoinGroupEvent

    /** "Try again" after the group could not be looked up. */
    data object TryAgain : JoinGroupEvent
}

/** What Join group asks its route to do. */
sealed interface JoinGroupEffect {
    /**
     * Open the group's page in place of Join group: joined, or the person was in it already. The page waits for the
     * group to arrive on this phone itself.
     */
    data class OpenGroup(val groupId: String) : JoinGroupEffect

    /**
     * Open Home on the Family side in place of Join Family: joined (the family month loads there), or the person was in
     * it already.
     */
    data object OpenFamily : JoinGroupEffect
}
