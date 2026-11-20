package com.expenses.app.groups.sync

import com.expenses.app.groups.G1
import com.expenses.app.groups.GroupsTestEnv
import com.expenses.app.groups.data.EntityKind
import com.expenses.app.groups.data.OutboxDao
import com.expenses.app.groups.data.OutboxEntry
import com.expenses.app.groups.data.OutboxOp
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [Outbox.coalesce] runs at the start of every push. While a Splitwise import waits, the outbox holds up to 100 parts
 * of up to 30 KB each (docs/splitwise-import.md 5.3): merging the unsent expense changes must not load them (review,
 * 2026-11-19: it read every entry with its body, about 3 MB a run).
 */
@RunWith(RobolectricTestRunner::class)
class OutboxCoalesceTest {
    private val env = GroupsTestEnv()

    @After fun close() = env.close()

    /** The real outbox table, noting every entry that is read with its body. */
    private class Recording(private val real: OutboxDao) : OutboxDao by real {
        val loaded = ArrayList<OutboxEntry>()

        override suspend fun all(): List<OutboxEntry> = real.all().also { loaded += it }

        override suspend fun ofKind(kind: EntityKind): List<OutboxEntry> = real.ofKind(kind).also { loaded += it }

        override suspend fun forEntity(entityId: String): List<OutboxEntry> = real.forEntity(entityId).also { loaded += it }

        override suspend fun forGroup(groupId: String): List<OutboxEntry> = real.forGroup(groupId).also { loaded += it }

        override suspend fun get(seq: Long): OutboxEntry? = real.get(seq)?.also { loaded += it }
    }

    private fun text(value: String) = JsonObject(mapOf("description" to JsonPrimitive(value)))

    @Test fun mergingUnsentExpenseChangesLoadsNoImportPart() = runTest {
        val dao = env.db.outboxDao()
        val outbox = Outbox(dao, env.clock)
        val part = JsonObject(mapOf("items" to JsonArray(List(50) { JsonPrimitive("x".repeat(500)) })))
        val path = "/v1/groups/$G1/expenses/e1"
        // A create of one expense, two waiting import parts, then an edit of that expense: nothing was sent yet.
        outbox.append(G1, EntityKind.EXPENSE, "e1", OutboxOp.CREATE, "PUT", path, text("Milk"), restore = null)
        repeat(2) { outbox.append(G1, EntityKind.IMPORT, "part-$it", OutboxOp.CREATE, "POST", "/v1/groups/$G1/import", part, restore = null) }
        outbox.append(G1, EntityKind.EXPENSE, "e1", OutboxOp.UPDATE, "PUT", path, text("Milk and bread"), restore = null)

        val recording = Recording(dao)
        val dropped = Outbox(recording, env.clock).coalesce()

        assertTrue(dropped.isEmpty())
        assertEquals(listOf(EntityKind.EXPENSE, EntityKind.EXPENSE), recording.loaded.map { it.entityKind })
        // The merge is as before: one create with the last body, in the last entry's place.
        val left = dao.all()
        assertEquals(listOf(EntityKind.IMPORT, EntityKind.IMPORT, EntityKind.EXPENSE), left.map { it.entityKind })
        assertEquals(OutboxOp.CREATE, left.last().op)
        assertEquals(text("Milk and bread").toString(), left.last().bodyJson)
    }

    @Test fun aCreateAndItsDeleteNeverSentAreBothDropped() = runTest {
        val dao = env.db.outboxDao()
        val outbox = Outbox(dao, env.clock)
        val path = "/v1/groups/$G1/expenses/e1"
        outbox.append(G1, EntityKind.EXPENSE, "e1", OutboxOp.CREATE, "PUT", path, text("Milk"), restore = null)
        outbox.append(G1, EntityKind.IMPORT, "part-0", OutboxOp.CREATE, "POST", "/v1/groups/$G1/import", text("part"), restore = null)
        outbox.append(G1, EntityKind.EXPENSE, "e1", OutboxOp.DELETE, "DELETE", path, body = null, restore = null)

        assertEquals(setOf("e1"), outbox.coalesce())
        assertEquals(listOf(EntityKind.IMPORT), dao.all().map { it.entityKind })
    }
}
