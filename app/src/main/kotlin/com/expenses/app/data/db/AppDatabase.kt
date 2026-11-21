package com.expenses.app.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.expenses.app.data.model.Account
import com.expenses.app.data.model.CardBill
import com.expenses.app.data.model.CategoryRule
import com.expenses.app.data.model.Expense
import com.expenses.app.data.model.ReadSms
import com.expenses.app.data.model.ReviewItem
import com.expenses.app.groups.data.ExpenseShareEntity
import com.expenses.app.groups.data.GroupDao
import com.expenses.app.groups.data.GroupEntity
import com.expenses.app.groups.data.GroupExpenseEntity
import com.expenses.app.groups.data.GroupExtrasDao
import com.expenses.app.groups.data.ImportEntity
import com.expenses.app.groups.data.LinkEntity
import com.expenses.app.groups.data.LinkPriorEntity
import com.expenses.app.groups.data.MemberEntity
import com.expenses.app.groups.data.OutboxDao
import com.expenses.app.groups.data.OutboxEntry
import com.expenses.app.groups.data.PendingUpiEntity
import com.expenses.app.groups.data.RejectedChangeEntity
import com.expenses.app.groups.data.SettlementEntity
import com.expenses.app.groups.data.SplitRuleEntity
import java.time.Instant
import java.time.LocalDate

/**
 * The app's one database, kept only on this phone (backups are off in the manifest). Its schema of every version is
 * exported to `app/schemas`; a change to any entity needs a new version and a migration, tested in
 * `DatabaseMigrationTest`.
 *
 * Versions:
 * - 1: personal tracking (accounts, expenses, card bills, review items, read SMS, category rules).
 * - 2: splitting (docs/server.md 9.3): the `grp_*` tables and `expenses.groupExpenseId`. Added tables and a nullable
 *   column only, so Room's automatic migration keeps every personal row as it was.
 * - 3: undo a join by link and the owner label (server migration 0002): `grp_groups.ownerMemberId`,
 *   `grp_members.joinedAt`, `undoUntil` and `placeName`; and `grp_rejected`, refused changes the Group page shows as
 *   "Not saved". Nullable columns and a new table only: automatic migration.
 * - 4: `grp_link_priors`, how a payment was before a split linked it, so an undone join puts it back
 *   (docs/server.md 9.4). A new table only: automatic migration.
 * - 5: `grp_imports`, the Splitwise imports made on this phone (docs/splitwise-import.md 5.1), and the outbox kind
 *   `IMPORT` (stored by name, so no column changes). A new table only: automatic migration.
 * - 6: Family (docs/family.md 6.1): `expenses.familyExpenseId` (unique index), `category_rules.scope`, and the group
 *   kind `FAMILY` (stored by name). Nullable columns and an index only: automatic migration, new columns null.
 * - 7: card bill reminders (docs/card-bills.md D8): `card_bills.remindedAt`. A nullable column only: automatic
 *   migration, every stored bill not reminded yet.
 */
@Database(
    entities = [
        Account::class, Expense::class, CardBill::class, ReviewItem::class, ReadSms::class, CategoryRule::class,
        GroupEntity::class, MemberEntity::class, GroupExpenseEntity::class, ExpenseShareEntity::class,
        SettlementEntity::class, OutboxEntry::class, LinkEntity::class, SplitRuleEntity::class,
        PendingUpiEntity::class, RejectedChangeEntity::class, LinkPriorEntity::class, ImportEntity::class,
    ],
    version = 7,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
    ],
)
@TypeConverters(TimeConverters::class)
abstract class AppDatabase : RoomDatabase() {
    /** Accounts and cards. */
    abstract fun accountDao(): AccountDao

    /** Expenses and payments. */
    abstract fun expenseDao(): ExpenseDao

    /** Credit card bills. */
    abstract fun cardBillDao(): CardBillDao

    /** Needs review items. */
    abstract fun reviewDao(): ReviewDao

    /** Ids of SMS already read. */
    abstract fun readSmsDao(): ReadSmsDao

    /** The person's "always this category" rules. */
    abstract fun categoryRuleDao(): CategoryRuleDao

    /** Groups, members, group expenses and settlements. */
    abstract fun groupDao(): GroupDao

    /** Group changes waiting to be sent. */
    abstract fun outboxDao(): OutboxDao

    /** Pay links, split rules, pending UPI payments and Splitwise imports. */
    abstract fun groupExtrasDao(): GroupExtrasDao

    /** Opening the database. */
    companion object {
        /** File name of the database in the app's private storage. */
        const val FILE_NAME: String = "expenses.db"

        /** Opens the database file in the app's private storage. Call once per process. */
        fun open(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, FILE_NAME).build()
    }
}

/** Stores [Instant] as epoch milliseconds and [LocalDate] as epoch days. */
class TimeConverters {
    /** Epoch milliseconds to an instant. */
    @TypeConverter
    fun toInstant(millis: Long?): Instant? = millis?.let(Instant::ofEpochMilli)

    /** An instant to epoch milliseconds. */
    @TypeConverter
    fun fromInstant(instant: Instant?): Long? = instant?.toEpochMilli()

    /** Epoch days to a date. */
    @TypeConverter
    fun toLocalDate(epochDay: Long?): LocalDate? = epochDay?.let(LocalDate::ofEpochDay)

    /** A date to epoch days. */
    @TypeConverter
    fun fromLocalDate(date: LocalDate?): Long? = date?.toEpochDay()
}
