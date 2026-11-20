package com.expenses.app.data.repository

import com.expenses.app.data.db.ReviewDao
import com.expenses.app.data.model.ReviewItem
import java.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * Messages that look like payments but could not be read for sure ("Needs review"). Their SMS text stays on this
 * phone and is cleared as soon as the item is resolved.
 */
class ReviewRepository(private val items: ReviewDao) {
    /** Open items, newest first. */
    fun observeOpen(): Flow<List<ReviewItem>> = items.observeOpen()

    /** How many items are open. */
    fun observeOpenCount(): Flow<Int> = items.observeOpenCount()

    /** One item, or null. */
    suspend fun get(id: Long): ReviewItem? = items.get(id)

    /** Stores a new item and returns its id. */
    suspend fun add(item: ReviewItem): Long = items.insert(item)

    /** Marks an item resolved (fixed or "Not a spend") and clears its SMS text. */
    suspend fun resolve(id: Long) = items.resolve(id)

    /** Deletes the items whose SMS arrived before [before]. */
    suspend fun deleteBefore(before: Instant) = items.deleteBefore(before)
}
