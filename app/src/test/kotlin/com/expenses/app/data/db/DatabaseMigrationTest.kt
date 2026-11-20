package com.expenses.app.data.db

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.expenses.app.data.category.Category
import com.expenses.app.data.model.CategoryRule
import com.expenses.app.data.model.ExpenseScope
import com.expenses.app.data.model.ExpenseSource
import com.expenses.app.data.model.ExpenseStatus
import com.expenses.app.groups.data.GroupEntity
import com.expenses.app.groups.data.GroupKind
import com.expenses.app.groups.data.ImportEntity
import com.expenses.app.groups.data.ImportState
import com.expenses.app.groups.data.LinkPriorEntity
import com.expenses.app.groups.data.SyncState
import java.io.File
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Test installs have version 1 to 5 databases: the moves to version 2 (splitting), 3 (undo a join, owner label,
 * refused changes), 4 (link priors), 5 (Splitwise imports) and 6 (Family) must keep every row as it was.
 *
 * The version 1 database is built from its exported schema (app/schemas, exactly what Room made for version 1), then
 * opened by the app's [AppDatabase]: Room runs the migrations and then checks every table, column, index and foreign
 * key against the current version, and fails the open if anything differs. Room's MigrationTestHelper does the same but reads
 * the schemas from test assets, which Robolectric does not see.
 */
@RunWith(RobolectricTestRunner::class)
class DatabaseMigrationTest {
    private val context = RuntimeEnvironment.getApplication()
    private val name = "migration-test.db"

    @After fun delete() {
        context.deleteDatabase(name)
    }

    /** Makes a database file of [version] from its exported schema, with Room's identity table. */
    private fun createFromSchema(version: Int, rows: SQLiteDatabase.() -> Unit) {
        val folder = File(System.getProperty("room.schemas") ?: "schemas", AppDatabase::class.java.name)
        val schema = Json.parseToJsonElement(File(folder, "$version.json").readText()).jsonObject["database"]!!.jsonObject
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            for (entity in schema["entities"]!!.jsonArray.map { it.jsonObject }) {
                val table = entity.text("tableName")
                db.execSQL(entity.text("createSql").replace("\${TABLE_NAME}", table))
                entity["indices"]?.jsonArray?.forEach { db.execSQL(it.jsonObject.text("createSql").replace("\${TABLE_NAME}", table)) }
            }
            schema["setupQueries"]!!.jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
            db.version = version
            db.rows()
        }
    }

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    private fun SQLiteDatabase.version1Rows() {
        execSQL(
            "INSERT INTO accounts (id, bank, type, last4, upiId, displayName, tracked, firstSeenAt) " +
                "VALUES (1, 'HDFC Bank', 'CREDIT_CARD', '4521', NULL, 'Work card', 1, 1700000000000)",
        )
        execSQL(
            "INSERT INTO expenses (id, amountMinor, currency, direction, counterparty, counterpartyVpa, category, occurredAt, " +
                "source, status, paidWith, accountId, referenceId, smsId, smsSender, smsReceivedAt, originalMessage, note, " +
                "myShareMinor, scope, lowConfidence) VALUES (7, 45000, 'INR', 'DEBIT', 'Swiggy', NULL, 'FOOD', 1763290000000, " +
                "'SMS', 'ADDED', 'CARD', 1, '123456789012', 42, 'VM-HDFCBK', 1763290000000, 'Rs.450 spent on card 4521', " +
                "'team lunch', 45000, 'ME', 0)",
        )
        execSQL(
            "INSERT INTO card_bills (id, accountId, bank, cardLast4, totalDueMinor, minimumDueMinor, dueDate, receivedAt, smsId) " +
                "VALUES (3, 1, 'HDFC Bank', '4521', 1250000, 62500, 20420, 1763290000000, 43)",
        )
        execSQL("INSERT INTO category_rules (merchantKey, category) VALUES ('swiggy', 'FOOD')")
        execSQL("INSERT INTO read_sms (smsId, receivedAt, sender, readAt, outcome) VALUES (42, 1763290000000, 'VM-HDFCBK', 1763290001000, 'PAYMENT')")
        execSQL("INSERT INTO review_items (id, smsId, sender, body, receivedAt, hint, resolved) VALUES (5, 44, 'AD-SBIINB', 'text', 1763290000000, NULL, 0)")
    }

    private fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name).allowMainThreadQueries().build()

    @Test fun everyVersion1RowSurvivesTheMoveToVersion2() = runTest {
        createFromSchema(1) { version1Rows() }
        val db = open()
        try {
            val old = db.expenseDao().get(7)!!
            assertEquals(45_000L, old.amountMinor)
            assertEquals("Swiggy", old.counterparty)
            assertEquals("team lunch", old.note)
            assertEquals("Rs.450 spent on card 4521", old.originalMessage)
            assertEquals(45_000L, old.myShareMinor)
            assertEquals(1L, old.accountId)
            assertEquals(Category.FOOD, old.category)
            assertEquals(ExpenseSource.SMS, old.source)
            assertEquals(ExpenseStatus.ADDED, old.status)
            assertNull(old.groupExpenseId)
            assertEquals("Work card", db.accountDao().get(1)!!.displayName)
            assertEquals(Category.FOOD, db.categoryRuleDao().get("swiggy")!!.category)
            assertEquals(1, db.reviewDao().get(5)?.let { 1 } ?: 0)
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM card_bills").use { assertTrue(it.moveToFirst() && it.getInt(0) == 1) }
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM read_sms").use { assertTrue(it.moveToFirst() && it.getInt(0) == 1) }
        } finally {
            db.close()
        }
    }

    @Test fun theMigratedDatabaseTakesNewRowsAndGroups() = runTest {
        createFromSchema(1) { version1Rows() }
        val db = open()
        try {
            val old = db.expenseDao().get(7)!!
            // New rows get ids after the old ones; the link to a group expense is unique.
            val added = db.expenseDao().insert(old.copy(id = 0, smsId = 99, groupExpenseId = "e0000000-0000-4000-8000-000000000001"))
            assertTrue(added > 7)
            assertEquals(added, db.expenseDao().findByGroupExpense("e0000000-0000-4000-8000-000000000001")!!.id)
            db.groupDao().upsertGroup(
                GroupEntity("b0000000-0000-4000-8000-000000000001", GroupKind.GROUP, "Flat 302", null, "m", 0, 0, false, SyncState.SYNCED, Instant.EPOCH),
            )
            assertEquals(1, db.groupDao().allGroups().size)
        } finally {
            db.close()
        }
    }

    @Test fun version2GroupsKeepTheirRowsInVersion3() = runTest {
        createFromSchema(2) {
            version1Rows()
            execSQL(
                "INSERT INTO grp_groups (id, kind, name, ownerUserId, myMemberId, version, latestVersion, loadFailed, syncState, createdAt) " +
                    "VALUES ('b0000000-0000-4000-8000-000000000001', 'GROUP', 'Flat 302', 'u1', 'm1', 4, 4, 0, 'SYNCED', 1700000000000)",
            )
            execSQL(
                "INSERT INTO grp_members (id, groupId, userId, name, phone, upiId, onApp, removed, version) " +
                    "VALUES ('m2', 'b0000000-0000-4000-8000-000000000001', NULL, 'Priya', '+919000000001', NULL, 0, 0, 2)",
            )
        }
        val db = open()
        try {
            val group = db.groupDao().getGroup("b0000000-0000-4000-8000-000000000001")!!
            assertEquals("Flat 302", group.name)
            assertEquals(4L, group.version)
            assertNull(group.ownerMemberId)
            val member = db.groupDao().getMember("m2")!!
            assertEquals("Priya", member.name)
            assertEquals("+919000000001", member.phone)
            assertNull(member.joinedAt)
            assertNull(member.undoUntil)
            assertNull(member.placeName)
            assertEquals(45_000L, db.expenseDao().get(7)!!.amountMinor)
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM grp_rejected").use { assertTrue(it.moveToFirst() && it.getInt(0) == 0) }
        } finally {
            db.close()
        }
    }

    @Test fun version3RowsStayInVersion4AndLinkPriorsGoWithTheirGroup() = runTest {
        createFromSchema(3) {
            version1Rows()
            execSQL(
                "INSERT INTO grp_groups (id, kind, name, ownerUserId, ownerMemberId, myMemberId, version, latestVersion, loadFailed, syncState, createdAt) " +
                    "VALUES ('b0000000-0000-4000-8000-000000000001', 'GROUP', 'Flat 302', 'u1', 'm1', 'm1', 4, 4, 0, 'SYNCED', 1700000000000)",
            )
            execSQL(
                "INSERT INTO grp_members (id, groupId, userId, name, phone, upiId, onApp, removed, version, joinedAt, undoUntil, placeName) " +
                    "VALUES ('m2', 'b0000000-0000-4000-8000-000000000001', 'u2', 'Vikram', NULL, NULL, 1, 0, 3, 1700000000000, 1700604800000, 'Priya')",
            )
        }
        val db = open()
        try {
            assertEquals("Flat 302", db.groupDao().getGroup("b0000000-0000-4000-8000-000000000001")!!.name)
            assertEquals("Priya", db.groupDao().getMember("m2")!!.placeName)
            assertNull(db.groupDao().getLinkPrior("f1"))
            db.groupDao().upsertLinkPrior(LinkPriorEntity("f1", "b0000000-0000-4000-8000-000000000001", 7, ExpenseStatus.DETECTED))
            assertEquals(ExpenseStatus.DETECTED, db.groupDao().getLinkPrior("f1")!!.status)
            db.groupDao().deleteGroup("b0000000-0000-4000-8000-000000000001")
            assertNull(db.groupDao().getLinkPrior("f1"))
        } finally {
            db.close()
        }
    }

    @Test fun version4RowsStayInVersion5AndImportsAreKeptApartFromGroups() = runTest {
        createFromSchema(4) {
            version1Rows()
            execSQL(
                "INSERT INTO grp_groups (id, kind, name, ownerUserId, ownerMemberId, myMemberId, version, latestVersion, loadFailed, syncState, createdAt) " +
                    "VALUES ('b0000000-0000-4000-8000-000000000001', 'GROUP', 'Flat 302', 'u1', 'm1', 'm1', 4, 4, 0, 'SYNCED', 1700000000000)",
            )
            execSQL(
                "INSERT INTO grp_outbox (idempotencyKey, groupId, entityKind, entityId, op, method, path, bodyJson, restoreJson, createdAt, attempts, failures, lastErrorCode) " +
                    "VALUES ('k1', 'b0000000-0000-4000-8000-000000000001', 'GROUP', 'b0000000-0000-4000-8000-000000000001', 'UPDATE', 'PATCH', '/v1/groups/b0000000-0000-4000-8000-000000000001', '{}', NULL, 1700000000000, 0, 0, NULL)",
            )
            execSQL("INSERT INTO grp_link_priors (groupExpenseId, groupId, expenseId, status) VALUES ('f1', 'b0000000-0000-4000-8000-000000000001', 7, 'DETECTED')")
        }
        val db = open()
        try {
            assertEquals("Flat 302", db.groupDao().getGroup("b0000000-0000-4000-8000-000000000001")!!.name)
            assertEquals(ExpenseStatus.DETECTED, db.groupDao().getLinkPrior("f1")!!.status)
            assertEquals(1, db.outboxDao().all().size)
            assertEquals(45_000L, db.expenseDao().get(7)!!.amountMinor)
            val extras = db.groupExtrasDao()
            assertNull(extras.importOf("b0000000-0000-4000-8000-000000000001"))
            val row = ImportEntity(
                groupId = "b0000000-0000-4000-8000-000000000001", fileHash = "ab".repeat(32), groupName = "Flat 302", expenseCount = 46,
                itemCount = 48, sentItems = 0, state = ImportState.SENDING, createdAt = Instant.EPOCH,
            )
            extras.upsertImport(row)
            extras.addSentItems(row.groupId, 50)
            assertEquals(48, extras.importOf(row.groupId)!!.sentItems)
            assertEquals(row.groupId, extras.latestImportByHash("ab".repeat(32))!!.groupId)
            // No foreign key: a failed import's row outlives its group.
            extras.setImportState(row.groupId, ImportState.FAILED)
            db.groupDao().deleteGroup(row.groupId)
            assertEquals(ImportState.FAILED, extras.importOf(row.groupId)!!.state)
            assertNull(extras.latestImportByHash("ab".repeat(32)))
        } finally {
            db.close()
        }
    }

    @Test fun version5RowsStayInVersion6WithTheNewFamilyColumnsEmpty() = runTest {
        createFromSchema(5) {
            version1Rows()
            execSQL(
                "INSERT INTO grp_groups (id, kind, name, ownerUserId, ownerMemberId, myMemberId, version, latestVersion, loadFailed, syncState, createdAt) " +
                    "VALUES ('b0000000-0000-4000-8000-000000000001', 'GROUP', 'Flat 302', 'u1', 'm1', 'm1', 4, 4, 0, 'SYNCED', 1700000000000)",
            )
            execSQL(
                "INSERT INTO grp_members (id, groupId, userId, name, phone, upiId, onApp, removed, version, joinedAt, undoUntil, placeName) " +
                    "VALUES ('m1', 'b0000000-0000-4000-8000-000000000001', 'u1', 'Arjun', NULL, NULL, 1, 0, 1, NULL, NULL, NULL)",
            )
            execSQL(
                "INSERT INTO grp_expenses (id, groupId, description, category, amountMinor, occurredOn, paidByMemberId, splitMethod, auto, " +
                    "createdByMemberId, updatedByMemberId, updatedAt, deleted, version, syncState) VALUES ('f1', " +
                    "'b0000000-0000-4000-8000-000000000001', 'Swiggy', 'FOOD', 45000, 20408, 'm1', 'EQUAL', 0, 'm1', 'm1', 1700000000000, 0, 4, 'SYNCED')",
            )
            execSQL("INSERT INTO grp_expense_shares (expenseId, memberId, shareMinor, input) VALUES ('f1', 'm1', 45000, NULL)")
            execSQL(
                "INSERT INTO grp_imports (groupId, fileHash, groupName, expenseCount, itemCount, sentItems, state, createdAt) " +
                    "VALUES ('b0000000-0000-4000-8000-000000000001', 'hash', 'Flat 302', 46, 48, 48, 'DONE', 1700000000000)",
            )
            execSQL("UPDATE expenses SET groupExpenseId = 'f1', myShareMinor = 15000, scope = 'FAMILY' WHERE id = 7")
        }
        val db = open()
        try {
            val old = db.expenseDao().get(7)!!
            assertEquals(45_000L, old.amountMinor)
            assertEquals(15_000L, old.myShareMinor)
            assertEquals("f1", old.groupExpenseId)
            assertEquals(ExpenseScope.FAMILY, old.scope)
            assertEquals("team lunch", old.note)
            assertNull(old.familyExpenseId)
            val rule = db.categoryRuleDao().get("swiggy")!!
            assertEquals(Category.FOOD, rule.category)
            assertNull(rule.scope)
            assertEquals("Flat 302", db.groupDao().getGroup("b0000000-0000-4000-8000-000000000001")!!.name)
            assertEquals(45_000L, db.groupDao().getExpenseWithShares("f1")!!.shares.single().shareMinor)
            assertEquals(ImportState.DONE, db.groupExtrasDao().importOf("b0000000-0000-4000-8000-000000000001")!!.state)
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM card_bills").use { assertTrue(it.moveToFirst() && it.getInt(0) == 1) }
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM read_sms").use { assertTrue(it.moveToFirst() && it.getInt(0) == 1) }

            // The new column takes a link, one row per family expense; a rule takes a scope; a family is stored by name.
            db.expenseDao().update(old.copy(groupExpenseId = null, myShareMinor = 45_000, familyExpenseId = "x1"))
            assertEquals(7L, db.expenseDao().findByFamilyExpense("x1")!!.id)
            val copy = old.copy(id = 0, smsId = 99, groupExpenseId = null, familyExpenseId = "x1")
            assertTrue(runCatching { db.expenseDao().insert(copy) }.exceptionOrNull() is SQLiteConstraintException)
            db.categoryRuleDao().upsert(CategoryRule("swiggy", Category.FOOD, ExpenseScope.FAMILY))
            assertEquals(ExpenseScope.FAMILY, db.categoryRuleDao().get("swiggy")!!.scope)
            db.groupDao().upsertGroup(
                GroupEntity("b0000000-0000-4000-8000-000000000031", GroupKind.FAMILY, "Home", "u1", "m9", 1, 1, false, SyncState.SYNCED, Instant.EPOCH),
            )
            assertEquals(GroupKind.FAMILY, db.groupDao().familyGroup()!!.kind)
        } finally {
            db.close()
        }
    }

    @Test fun anEmptyVersion1DatabaseMigrates() = runTest {
        createFromSchema(1) {}
        val db = open()
        try {
            assertEquals(0, db.groupDao().allGroups().size)
            assertEquals(0, db.outboxDao().all().size)
        } finally {
            db.close()
        }
    }
}
