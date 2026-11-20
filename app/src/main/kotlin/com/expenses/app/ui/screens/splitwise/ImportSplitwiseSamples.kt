package com.expenses.app.ui.screens.splitwise

import com.expenses.splitwise.Refusal
import java.time.LocalDate

/**
 * The states of Import from Splitwise and Group imported as the design draws them (design/spec/screens/02-everyday/28
 * to 31 and 133 to 141), for the debug previews and the screen tests.
 */
object ImportSplitwiseSamples {
    /** The file name the design uses. */
    const val FILE: String = "flat-302-export.csv"

    private const val GROUP_ID = "b0000000-0000-4000-8000-000000000302"

    /** The three people of 29 with their balances. */
    val people: List<PersonOption> = listOf(
        PersonOption("Arjun Mehta", 235_000),
        PersonOption("Priya Nair", -115_000),
        PersonOption("Kiran Rao", -120_000),
    )

    /** 28: no file chosen yet. */
    val noFile: ImportUiState = ImportUiState.NoFile

    /** 135: the file is being read. */
    val reading: ImportUiState = ImportUiState.Reading(FILE)

    /** 29: the file, "Flat 302", and Arjun Mehta preselected. */
    val chosen: ImportUiState.Chosen =
        ImportUiState.Chosen(FILE, expenseCount = 46, people = people, selected = 0, groupName = "Flat 302", leftOut = 0)

    /** 133: no name matched the person's, so nobody is chosen and Import is off. */
    val whichOne: ImportUiState.Chosen = chosen.copy(selected = null)

    /** 134: three expenses in other currencies stay out. */
    val otherCurrencies: ImportUiState.Chosen = chosen.copy(leftOut = 3)

    /** Not drawn: the file name gave no group name, so the field says so and Import is off. */
    val nameBlank: ImportUiState.Chosen = chosen.copy(groupName = "")

    /** 30: a file that is not a Splitwise export. */
    val wrongFile: ImportUiState = ImportUiState.CannotImport("expenses-2026.pdf", Refusal.NOT_SPLITWISE)

    /** 136, and its alternatives drawn once: [FILE] cannot come across for [reason]. */
    fun cannotImport(reason: Refusal = Refusal.DOES_NOT_ADD_UP): ImportUiState = ImportUiState.CannotImport(FILE, reason)

    /** 137: the same file came across on 18 Nov. */
    val alreadyHere: ImportUiState = ImportUiState.AlreadyHere(FILE, 46, 3, GROUP_ID, "Flat 302", LocalDate.of(2026, 11, 18))

    /** 138: 20 of 46 sent. */
    val importing: ImportUiState.Importing = ImportUiState.Importing(GROUP_ID, "Flat 302", sent = 20, total = 46, banner = ImportBanner.None)

    /** 139: the same, offline. */
    val importingOffline: ImportUiState.Importing = importing.copy(banner = ImportBanner.Offline)

    /** 139's alternative: a part failed on the server and is tried again. */
    val importingProblem: ImportUiState.Importing = importing.copy(banner = ImportBanner.Problem)

    /** 140: a part was refused. */
    val failed: ImportUiState = ImportUiState.Failed("Flat 302")

    /** 31: Flat 302 is in, with links for Priya and Kiran. */
    val imported: GroupImportedUiState.Shown = GroupImportedUiState.Shown("Flat 302", expenseCount = 46, friendNames = listOf("Priya", "Kiran"))

    /** 141: the same, offline. */
    val importedOffline: GroupImportedUiState.Shown = imported.copy(banner = ImportBanner.Offline)

    /** 141's alternative: the links could not be made. */
    val importedProblem: GroupImportedUiState.Shown = imported.copy(banner = ImportBanner.Problem)

    /** 31's note for one friend: "Invite link", "Priya", "Share invite link". */
    val importedOneFriend: GroupImportedUiState.Shown = imported.copy(friendNames = listOf("Priya"))

    /** 31's note for four or more friends: "Priya, Kiran and 3 more". */
    val importedMany: GroupImportedUiState.Shown = imported.copy(friendNames = listOf("Priya", "Kiran", "Meera", "Rahul", "Anil"))
}
