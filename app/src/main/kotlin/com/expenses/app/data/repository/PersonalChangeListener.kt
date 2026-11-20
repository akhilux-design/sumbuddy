package com.expenses.app.data.repository

import com.expenses.app.data.model.Expense

/**
 * Told about every write of [ExpenseRepository] that can change whether a payment is shared, or what is shared of it
 * (docs/family.md 7.3 and 7.4). The app's one is [com.expenses.app.groups.data.FamilyLinker]. Both functions run
 * inside the repository's transaction, so a payment and its shared copy change together or not at all.
 */
interface PersonalChangeListener {
    /** The rows [ids] were just written (added, ignored, edited, back to waiting, typed in). */
    suspend fun onChanged(ids: Collection<Long>)

    /** The rows [rows] are about to be deleted. */
    suspend fun onDeleting(rows: List<Expense>)

    /** A listener that does nothing: the default before splitting is set up, and in tests of personal data only. */
    companion object {
        /** Does nothing. */
        val None: PersonalChangeListener = object : PersonalChangeListener {
            override suspend fun onChanged(ids: Collection<Long>) = Unit

            override suspend fun onDeleting(rows: List<Expense>) = Unit
        }
    }
}
