package com.expenses.app.ui.screens.splitwise

import androidx.compose.runtime.Immutable
import com.expenses.app.groups.data.GroupText
import com.expenses.splitwise.Refusal
import java.time.LocalDate

/**
 * What Import from Splitwise shows (docs/splitwise-import.md 5.7). Each state is one design frame of
 * `design/spec/screens/02-everyday/`.
 */
@Immutable
sealed interface ImportUiState {
    /** 28: no file chosen yet. */
    data object NoFile : ImportUiState

    /**
     * 135: the picked file [fileName] is being read on the phone. [fileName] is empty for the moment between the
     * picker returning and the provider giving the name; the name's line keeps its place meanwhile.
     */
    data class Reading(val fileName: String) : ImportUiState

    /**
     * 29, 133 and 134: a file that can come across.
     *
     * @param fileName the picked file's name.
     * @param expenseCount its rupee expense rows ("46 expenses").
     * @param people everyone in the file, in its column order, with their rupee balance.
     * @param selected the column the person said is them, or null while nobody is chosen (133).
     * @param groupName the Group name field, from the file name at first (docs/splitwise-import.md 1.5).
     * @param leftOut rows in other currencies that stay out (134); 0 for none.
     * @param importing true while the group is being written to the phone, so a second tap does nothing.
     */
    data class Chosen(
        val fileName: String,
        val expenseCount: Int,
        val people: List<PersonOption>,
        val selected: Int?,
        val groupName: String,
        val leftOut: Int,
        val importing: Boolean = false,
    ) : ImportUiState {
        /** The group name as it would be stored; empty when nothing usable is typed. */
        val cleanName: String get() = GroupText.clean(groupName, GroupText.MAX_GROUP_NAME)

        /** True when Import can be used: someone is chosen, the name is not blank, and nothing runs yet. */
        val canImport: Boolean get() = selected != null && cleanName.isNotEmpty() && !importing
    }

    /** 30 and 136: the file cannot come across, for [reason]. */
    data class CannotImport(val fileName: String, val reason: Refusal) : ImportUiState

    /**
     * 137: the same file came across before as [groupName] on [on] and that group is still here.
     *
     * @param expenseCount and [peopleCount] describe the picked file, as on 29.
     */
    data class AlreadyHere(
        val fileName: String,
        val expenseCount: Int,
        val peopleCount: Int,
        val groupId: String,
        val groupName: String,
        val on: LocalDate,
    ) : ImportUiState

    /**
     * 138 and 139: the group is on this phone and its parts are being sent.
     *
     * @param sent items the server has.
     * @param total items to send.
     * @param banner Offline (139), Problem (139's alternative) or none.
     */
    data class Importing(val groupId: String, val groupName: String, val sent: Int, val total: Int, val banner: ImportBanner) : ImportUiState

    /** 140: a part was refused, so none of [groupName] was kept. */
    data class Failed(val groupName: String) : ImportUiState
}

/**
 * One Option Card of "Which one is you?".
 *
 * @param name the person's name in the file.
 * @param balanceMinor their rupee balance in the file: above 0 is owed ("Is owed ₹2,350"), below 0 owes.
 */
@Immutable
data class PersonOption(val name: String, val balanceMinor: Long)

/** The banner at the top of Importing (139) and Group imported (141). */
enum class ImportBanner {
    /** No banner. */
    None,

    /** "You're offline": it finishes by itself once back online. */
    Offline,

    /** "Could not do that now": something failed on the server and is tried again. */
    Problem,
}

/** Everything the person can do on Import from Splitwise. */
sealed interface ImportEvent {
    /** Choose file, Choose another file, Choose a different file: open the system's file picker. */
    data object ChooseFile : ImportEvent

    /** The Group name field changed. */
    data class NameChanged(val name: String) : ImportEvent

    /** The Option Card of column [index] was chosen. */
    data class PersonSelected(val index: Int) : ImportEvent

    /** "Import {name}". */
    data object Import : ImportEvent

    /** "Open {group}" on Importing (138) or Already here (137). */
    data object OpenGroup : ImportEvent

    /** "Close" on Import failed (140). */
    data object Close : ImportEvent

    /** The top bar's back button or the system Back. */
    data object Back : ImportEvent
}

/** One-shot messages from Import from Splitwise to its screen. */
sealed interface ImportEffect {
    /** Open the system's file picker. */
    data object PickFile : ImportEffect

    /** Open group [groupId]'s page on the Groups tab. */
    data class OpenGroup(val groupId: String) : ImportEffect

    /** The import finished: open Group imported for [groupId]. */
    data class OpenImported(val groupId: String) : ImportEffect

    /** Leave the screen. */
    data object Leave : ImportEffect

    /** The session is gone: open Sign in. */
    data object SignIn : ImportEffect
}
