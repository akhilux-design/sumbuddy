# Architecture

The contract the feature code is written against. Names and signatures here match the code; when one changes,
change this file in the same commit.

## Modules and packages

| Module / package | What lives there |
|---|---|
| `:parser` (`com.expenses.parser`) | Pure Kotlin SMS parser. `SmsParser().parse(SmsMessage): ParseResult`, `Duplicates.keepBankCopies(List<ReadPayment>)`. See `parser/README.md`. |
| `:splits` (`com.expenses.splits`) | Pure Kotlin split maths shared with the server (docs/server.md 6): `SplitMethod`, `SplitMath.shares(method, amountMinor, inputs)`, `SplitMath.check`, `SplitMath.leftToAssign`, `Balances.nets`, `Balances.simplify`, `DirectIds.groupId` / `memberId` (UUID v5). Tests run the shared vectors (`shared/vectors`, or `server/test/vectors` while the server keeps them there). |
| `:splitwise` (`com.expenses.splitwise`) | Pure Kotlin reader of a Splitwise export for Import from Splitwise (docs/splitwise-import.md 4). No Android dependencies; it depends on `:splits` only, and the file never leaves the phone. `SplitwiseCsv.parse(bytes, truncated): SplitwiseResult` (`Ok(SplitwiseFile)` or `Refused(Refusal)`; limits `MAX_BYTES` 5 MB, `MAX_PEOPLE` 50, `MAX_ROWS` 5,000) and `SplitwiseCsv.fileHash(bytes)` (SHA-256, lowercase hex); `SplitwiseFile` (`people`, `expenses: List<SwExpense>` with `SwPart`s, `payments: List<SwPayment>`, `balancesPaise`, rows left out by currency); `ImportParts` (`items`, `clampDate`, `chunk`: at most 50 items and 30,000 bytes a part); `SplitwiseCategories.map`; `SplitwiseText.clean` (the server's text rules), `GroupNameFromFile.of`, `SplitwisePeople.preselect`. |
| `com.expenses.app.groups.*` | Splitting: API client, sign-in and session, group tables, sync, push. See docs/server.md 9.1 for the package list; only group data reaches the server. `groups.split.AutoSplitter` and `groups.match.SettlementMatcher` are the two `NewPaymentsHook`s (9.4 recurring rule, 9.5 matching); `groups.data.RejectedChanges` keeps refused changes for "Not saved"; `groups.sync.NetworkStatus` says whether the phone is online. The hooks take `signedIn: suspend () -> Boolean`, not the `AuthRepository`, so tests pass a plain function. `groups.splitwise` turns a `SplitwiseFile` into what one import writes (`ImportPlanner`: the group, its members, expenses, settlements and the request parts; `ImportBodies`; `ImportCategories`); `GroupsRepository.importSplitwise`, `importedFile`, `observeImport` and `observeImported` are its data API (docs/splitwise-import.md 5.3). |
| `com.expenses.app.ui.screens.grouppage`, `.split`, `.settle`, `.join` | Group page and settings, the split flow, settling up, Join group. Each registers its routes from its own `*Nav.kt` (docs/groups-build-plan.md lists who owns which file). |
| `com.expenses.app.ui.screens.splitwise` | Import from Splitwise and Group imported (design 02-everyday/28 to 31 and 133 to 141; docs/splitwise-import.md 5.6, 5.7): `ImportSplitwiseScreen` / `ImportSplitwiseViewModel` / `ImportUiState`, `GroupImportedScreen` / `GroupImportedViewModel`, `PickedFile` and `readPicked` (the picked document, read once into memory), and `SplitwiseNav.kt`. |
| `com.expenses.app.ui.screens.signin` | Sign in, Sign in (could not sign in), Your name (after sign-in), the Google button, and `SignInNav.kt`. The debug-only `DevSignInEntry` lives in `src/debug` (an empty one in `src/release`). |
| `com.expenses.app` | `ExpensesApplication` (makes the `AppContainer`), `MainActivity` (single activity, notification deep link). |
| `com.expenses.app.di` | `AppContainer`, `AppDispatchers`, `appViewModelFactory`, `appViewModel`, `CreationExtras.appContainer()`. |
| `com.expenses.app.data.model` | Room entities and read models: `Account`, `Expense`, `CardBill`, `ReviewItem`, `ReadSms`, `CategoryRule`, enums, `ExpenseWithAccount`, `DayExpenses`, `CategoryTotal`, `ReadCounts`, `FoundAccount`. |
| `com.expenses.app.data.category` | `Category` enum and `CategoryGuesser`. |
| `com.expenses.app.data.db` | `AppDatabase`, `TimeConverters`, DAOs (`Daos.kt`). Only repositories use DAOs. |
| `com.expenses.app.data.repository` | `ExpenseRepository`, `AccountRepository`, `CardBillRepository`, `ReviewRepository`, `ReadSmsRepository`, `TransactionRunner`. |
| `com.expenses.app.data.settings` | `SettingsRepository`, `AppSettings`, `TrackingMode` (DataStore Preferences); `ReviewSessionStore` (the current run of Review payments, ids only: see "Review payments and bulk Add"). |
| `com.expenses.app.ingest` | Ingestion interfaces only: `SmsInbox`, `InboxSms`, `IngestPipeline`, `IngestResult`, `NewPaymentsHook` (what runs after new SMS were read, before "Payment detected" notifications) and `runNewPaymentsHooks` (in order; each hook sees only what earlier hooks did not handle, so a repayment the matcher took is never also split). Implementations go here too. |
| `com.expenses.app.sms` | The Android side of ingestion; SMS text passes through here and is never logged or sent. `AndroidSmsInbox` (the phone's inbox, read page by page), `SmsIngestPipeline` (the `IngestPipeline`: parse, de-duplicate, store), `SmsScanner` and `ScanWorker` (the onboarding scan and "add older payments"), `SmsReceivedReceiver` and `IncomingSmsWorker` (a new SMS, and catching up from the last scan), `SmsWork` (the quiet notification the two workers show while reading, where expedited work runs as a foreground service), `PaymentNotifications` and `PaymentActionReceiver` ("Payment detected" with Add, Split and Ignore), `ParserRulesCleanup` (once per version of the parser's rules, removes untouched rows read from SMS that the current rules no longer count; see its KDoc). |
| `com.expenses.app.ui.theme`, `ui.components`, `ui.icons`, `ui.format` | Design tokens, shared components, icons, `formatRupees`, `PayeeWording` ("₹450 at Swiggy", "₹350 to Ramesh", "₹350 for Auto to station"). Reuse; never re-implement a component inline. `PageFrame` is the frame of a full screen (top bar, scrolling body, footer); `ChoiceDialog` the design's confirm dialog (icon, title, body, two choice pills); `BottomSheet` the design's sheet; `Banner` the banner at the top of a body (02-everyday/03 offline, 40 someone joined with Undo, 41 join undone); `PlaceholderCard` and `PlaceholderRow` the grey loading shapes of 22 (Groups tab and Group page). |
| `com.expenses.app.ui.nav` | Routes (`Routes.kt`), `AppNavHost`, `openTab`, `finishOnboarding`, `mainTab`, `isCurrent`, `leave`, `rememberNavigateOnce`; `AppLink` and `openLink` (screens opened from notifications and pay links), `PayLinks`; `SignInGate` (Sign in first, then the screen). |
| `com.expenses.app.ui.time` | `minuteTicks(clock)` and `Flow<Instant>.months(clock)`: "now" every minute, so screens that show the month or "5 min ago" stay right while open. |
| `com.expenses.app.ui.state` | `SavedStateHandle.keepForm(scope, form) { }`: keeps typed form fields across process death. |
| `com.expenses.app.ui.shell` | `AppRoot`, `AppRootViewModel`, `MainScaffold`. |
| `com.expenses.app.ui.screens.<feature>` | One package per feature: screen composables, `UiState`, `Event`, `ViewModel`, samples, and the feature's `*Nav.kt`. |

Gradle: Room 2.8.5 with KSP (schema exported to `app/schemas`; database version 6, see `AppDatabase`), DataStore
Preferences, WorkManager, OkHttp, Credential Manager with Google ID, Firebase Messaging (no google-services plugin),
Navigation
Compose with kotlinx.serialization routes. Unit tests use JUnit 4, kotlinx-coroutines-test and Robolectric (for
Room in memory). Versions only in `gradle/libs.versions.toml`.

## Data model

All amounts are `Long` minor units (paise for INR). Times are `java.time.Instant` (stored as epoch millis), dates
`LocalDate` (stored as epoch days). Enums are stored by name: never rename an enum constant. `Direction`,
`InstrumentType` and `Channel` are the parser's enums.

### `Account` (table `accounts`)

| Field | Type | Notes |
|---|---|---|
| `id` | `Long` | auto |
| `bank` | `String?` | parser's bank display name ("HDFC Bank") |
| `type` | `AccountType` | `BANK_ACCOUNT, CREDIT_CARD, DEBIT_CARD, WALLET, UPI_ID, OTHER`; `AccountType.from(InstrumentType)` |
| `last4` | `String?` | as printed |
| `upiId` | `String?` | the person's own UPI ID for `UPI_ID` accounts |
| `displayName` | `String?` | person's rename; null shows `bank` |
| `tracked` | `Boolean` | default true; switched off hides its DETECTED payments |
| `firstSeenAt` | `Instant` | |

Index `(bank, type, last4)`. Matching treats null = null.

### `Expense` (table `expenses`)

| Field | Type | Notes |
|---|---|---|
| `id` | `Long` | auto |
| `amountMinor` | `Long` | whole payment, positive |
| `currency` | `String` | default `"INR"` (`Expense.INR`) |
| `direction` | `Direction` | `DEBIT` money out, `CREDIT` money in |
| `counterparty` | `String?` | "Swiggy" |
| `counterpartyVpa` | `String?` | other side's UPI address |
| `category` | `Category` | |
| `occurredAt` | `Instant` | printed date, else SMS time, else entry time |
| `source` | `ExpenseSource` | `SMS`, `MANUAL`, `GROUP` (a mirror of a group expense someone else paid; docs/server.md 9.4) |
| `status` | `ExpenseStatus` | `DETECTED`, `ADDED`, `IGNORED` |
| `paidWith` | `PaidWith` | `CASH, UPI, CARD, BANK_TRANSFER, WALLET, ATM, OTHER`; `PaidWith.from(Channel)` |
| `accountId` | `Long?` | FK `accounts.id`, `SET NULL` on delete |
| `referenceId` | `String?` | UPI ref / RRN |
| `smsId` | `Long?` | SMS provider `_id`; unique together with `smsReceivedAt` (the provider reuses the id of a deleted newest message) |
| `smsSender` | `String?` | |
| `smsReceivedAt` | `Instant?` | for the 15 minute duplicate window |
| `originalMessage` | `String?` | SMS text for Expense detail's "original message" (on phone only); null once IGNORED and for payments of an untracked account |
| `note` | `String?` | |
| `myShareMinor` | `Long` | defaults to `amountMinor`; the person's share for later splitting |
| `scope` | `ExpenseScope` | `ME` (default), `FAMILY` |
| `lowConfidence` | `Boolean` | parser confidence was LOW: ask the person to check |
| `groupExpenseId` | `String?` | the group expense this row is linked to (version 2); unique. The month rule is unchanged: a split payment counts `myShareMinor` = the person's share |
| `familyExpenseId` | `String?` | the family expense this payment is shared as (version 6; docs/family.md 7); only the payer's own row; unique. Set exactly while the row is shared: `FAMILY`, `ADDED`, a rupee debit, not split, and a family is on the phone. The month rule is unchanged: it counts in full (`myShareMinor = amountMinor`) |

Indices: `(status, occurredAt)`, `(occurredAt)`, `(accountId)`, `(direction, amountMinor, smsReceivedAt)`,
`(referenceId)`, unique `(smsId, smsReceivedAt)`, `(smsReceivedAt)`, unique `(groupExpenseId)`, unique
`(familyExpenseId)`.

The group tables (`grp_*`, version 2) are listed in docs/server.md 9.3. Version 1 to 2 is Room's automatic migration
(new tables and one nullable column); version 2 to 3 adds `grp_groups.ownerMemberId`, `grp_members.joinedAt`,
`undoUntil` and `placeName`, and the table `grp_rejected` (automatic too); version 3 to 4 adds the table
`grp_link_priors` (how a payment was before a split linked it, docs/server.md 9.4); version 4 to 5 adds the table
`grp_imports` (the Splitwise imports made on this phone, docs/splitwise-import.md 5.1) and the outbox kind `IMPORT`,
which is stored by name and so changes no column; version 5 to 6 (Family, docs/family.md 6.1) adds
`expenses.familyExpenseId` with its unique index, `category_rules.scope`, and the group kind `FAMILY` (stored by
name); version 6 to 7 (card bill reminders, docs/card-bills.md D8) adds `card_bills.remindedAt`. All six are
`AutoMigration`s (`AutoMigration(from = 6, to = 7)` is the newest), tested by `DatabaseMigrationTest` from the
exported schemas (`app/schemas/.../1.json` to `7.json`). The database is at version 7.

Read models: `ExpenseWithAccount(expense: Expense, account: Account?)`,
`DayExpenses(date: LocalDate, expenses: List<ExpenseWithAccount>, spentMinor: Long)`,
`CategoryTotal(category: Category, count: Int, totalMinor: Long)`, `FamilyLink(id: Long, familyExpenseId: String)`.

### `CardBill` (table `card_bills`)

`id`, `accountId: Long?` (FK, cascade), `bank: String?`, `cardLast4: String?`, `totalDueMinor: Long`,
`minimumDueMinor: Long?`, `dueDate: LocalDate?`, `receivedAt: Instant`, `smsId: Long?`, `remindedAt: Instant?`
(version 7: when the automatic reminder was posted; a bill is reminded at most once); unique `(smsId, receivedAt)`.
Never spending.

### `ReviewItem` (table `review_items`)

`id`, `smsId: Long`, `sender: String`, `body: String`, `receivedAt: Instant`, `hint: String?`,
`resolved: Boolean`; unique `(smsId, receivedAt)`. `body` is cleared to `""` when resolved.

### `ReadSms` (table `read_sms`)

`smsId: Long`, `receivedAt: Instant`, `sender: String` (primary key: all three), `readAt: Instant`,
`outcome: ReadOutcome` (`PAYMENT, CARD_BILL, REVIEW, DUPLICATE, SKIPPED`). No text. A message is named by
`SmsKey(smsId, receivedAt, sender)`: the SMS provider gives the id of a deleted newest message to the next one, and
new ids after an SMS restore, so the id alone is not enough. `ReadCounts(byOutcome)` gives `total`, `used`
(PAYMENT + CARD_BILL + REVIEW) and `notStored`.

### `CategoryRule` (table `category_rules`)

`merchantKey: String` (primary key, `CategoryGuesser.merchantKey(name)`), `category: Category`, `scope: ExpenseScope?`
(version 6; null: the rule says nothing about Just me or Family). Made by "Do this every time" on Add to expenses,
which remembers the scope chosen there ("Swiggy is always Food · Family"); a new payment to that merchant waits with
the scope pre-selected (`SmsIngestPipeline` sets it from `guessScope`), and is never added without a tap
(docs/family.md 8.5).

### `Category`

`enum class Category(val icon: AppIcon, @StringRes val label: Int, @StringRes val examples: Int)`, in Choose
category order: `FOOD` (Food), `GROCERIES` (Cart), `TRAVEL` (Car), `RENT_AND_BILLS` (Home), `SHOPPING` (Bag),
`FUN` (Film), `OTHER` (Plus). Strings in `strings_category.xml`.

`CategoryGuesser.guess(counterparty: String?, vpa: String? = null): Category` matches whole-word keywords in the
order Groceries, Fun, Food, Travel, Rent and bills, Shopping (so "Swiggy Instamart" is Groceries and "Amazon Prime
Video" is Fun), then tries the VPA, else `OTHER`.

## Month rule

A month's spending (Home "Spent in November", Expense added "November so far", Month view) is the sum of
`myShareMinor` over expenses with `status == ADDED`, `direction == DEBIT`, `currency == "INR"` and `occurredAt`
in the month in India time (`AppContainer.ZONE`, the zone of `AppContainer.clock` and of the parser, so a date-only
SMS falls in the same month whatever zone the phone is set to). DETECTED and IGNORED never count. Credits
(refunds, cashback, money received) are listed (latest, by day) but never count and never reduce the total.
FAMILY expenses still count in the person's own month. Foreign currency spends are listed but not counted.

The family month (Home's Family side with a family, Month view (Family); docs/family.md rule 5) is a different sum:
every family expense of the month that is not deleted, at its full amount, by its India date (`grp_expenses`
of the `FAMILY` group, `FamilyRepository.observeMonth`). The person's own family expenses also count in their own
month as above; a partner's never become a personal row.

## Repository APIs

Reads return `Flow` (re-emits on change); writes are main-safe `suspend` functions. Never call DAOs from features.

### `ExpenseRepository`

```kotlin
fun observeMonthTotal(month: YearMonth): Flow<Long>
fun observeMonth(month: YearMonth): Flow<List<ExpenseWithAccount>>          // ADDED, debits and credits (not credits of untracked accounts), newest first
fun observeByDay(month: YearMonth): Flow<List<DayExpenses>>                 // newest day first
fun observeCategoryTotals(month: YearMonth): Flow<List<CategoryTotal>>      // counted spending, largest first
fun observeLatest(limit: Int): Flow<List<ExpenseWithAccount>>               // ADDED, newest first, not credits of untracked accounts
fun observeIgnored(limit: Int): Flow<List<ExpenseWithAccount>>              // IGNORED, newest first
fun observeDetected(): Flow<List<ExpenseWithAccount>>                       // DETECTED of tracked/unknown accounts, newest first
fun observeWaiting(): Flow<List<ExpenseWithAccount>>                        // DETECTED debits of tracked/unknown accounts, oldest first (Home card, Review payments)
fun observe(id: Long): Flow<ExpenseWithAccount?>
suspend fun get(id: Long): Expense?
suspend fun insert(expense: Expense): Long                                  // throws SQLiteConstraintException on a repeated smsId
suspend fun add(id: Long, category: Category, scope: ExpenseScope = ME, note: String? = null, alwaysThisCategory: Boolean = false)
suspend fun ignore(id: Long)                                                // IGNORED, and originalMessage cleared
suspend fun undoAdd(id: Long)                                               // back to DETECTED
suspend fun addWaiting(ids: Collection<Long>): List<Long>                   // one transaction; each keeps its category; only rows still DETECTED debits; returns ids added
suspend fun ignoreWaiting(ids: Collection<Long>): List<Long>                // one transaction; IGNORED and originalMessage cleared; returns ids ignored
suspend fun backToWaiting(ids: Collection<Long>): List<Long>                // Undo on Review payments: decided SMS payments back to DETECTED; returns ids restored
suspend fun setCategory(id: Long, category: Category)
suspend fun update(expense: Expense)
suspend fun delete(id: Long)
suspend fun addManual(amountMinor: Long, counterparty: String?, category: Category, occurredAt: Instant = now,
                      paidWith: PaidWith = CASH, note: String? = null, scope: ExpenseScope = ME): Long
suspend fun findPossibleCopies(direction: Direction, amountMinor: Long, currency: String, receivedAt: Instant,
                               referenceId: String?, window: Duration = 15 min): List<Expense>
suspend fun guessCategory(counterparty: String?, vpa: String? = null): Category   // rules first, then CategoryGuesser
suspend fun rememberCategory(counterparty: String, category: Category, scope: ExpenseScope? = null)  // null keeps the rule's scope
suspend fun guessScope(counterparty: String?): ExpenseScope                 // the rule's scope, else ME (pre-selected only)
suspend fun deleteSmsBefore(before: Instant)                                // onboarding scan restarted from a later day
```

`add(..., alwaysThisCategory = true)` remembers the category and the chosen scope. Every write that can change
whether a payment is shared in the family month (`add`, `addWaiting`, `ignoreWaiting`, `backToWaiting`, `ignore`,
`undoAdd`, `setCategory`, `update`, `addManual`) tells `PersonalChangeListener.onChanged(ids)` inside its own
transaction, and `delete` and `deleteSmsBefore` tell `onDeleting(rows)` before rows go. The app's listener is
`FamilyLinker` (constructor parameter `personalChanges: () -> PersonalChangeListener`, default no-op).

### Family (`groups/data`, docs/family.md 6 to 8)

A family is a group of kind `GroupKind.FAMILY` in the `grp_*` tables. `FamilyLinker` keeps the family month in step
with the person's own payments (eligibility, the fields sent, entry points, refusals; run inside the caller's
transaction); `MirrorWriter.sync` hands family expenses to `FamilyLinker.applyStored`; `SyncEngine` reconciles before
each push and after each pull; `LocalGroupData.removeGroup` unlinks a family's rows (they keep the Family tag) and
`switchAccount` unlinks every row; sign-out keeps the links. `GroupsRepository` never shows a family (Groups, Friends,
totals, split labels, known people, stored groups, balances), and `avatarColorsOf` puts a family after every group so
it changes no other colour (its placeholder takes none).

```kotlin
// FamilyRepository (AppContainer.family)
fun observeFamily(): Flow<FamilyInfo?>                    // null: no family; FamilyInfo.state WAITING / ALONE / TOGETHER, load
fun observeMonth(month: YearMonth): Flow<FamilyMonth?>    // null: no family; totals, people (me first, ₹0 rows), top 2 categories, latest 5, all rows
fun observeShareableEarlier(): Flow<Int>                  // "Your 12 Family expenses on this phone are shared too."
suspend fun create(name: String): FamilyResult<String>    // online; Ok(groupId), Offline, SignedOut, AlreadyInFamily, Failed(code)
suspend fun inviteLink(): FamilyResult<String>            // online; re-adds the placeholder if none; 7-day link renewed after 3 days
suspend fun join(token: String, familyName: String): FamilyResult<String>  // online; the family shows LOADING at once
suspend fun rename(name: String)                          // queued
suspend fun removePartner(memberId: String): ActionResult // online; owner only
suspend fun cancelInvite(memberId: String): ActionResult  // online
suspend fun leave(): ActionResult                         // online; rows unlinked, keep the Family tag
```

`AppContainer.homeView: MutableStateFlow<HomeView>` is the side of Home's Me / Family toggle, app-scoped so other
screens can open Home on the Family side.

### `AccountRepository`

```kotlin
fun observeAll(): Flow<List<Account>>                                       // oldest first
fun observe(id: Long): Flow<Account?>
suspend fun get(id: Long): Account?
suspend fun findOrCreate(bank: String?, type: AccountType, last4: String?, upiId: String? = null): FoundAccount
    // CREDIT_CARD with a known bank and last4 upgrades a stored OTHER account of that bank and last4 in place
    // (same id, so payments, bills and labels follow); a known type is never changed back
    // not when that bank also has a BANK_ACCOUNT or DEBIT_CARD with the same last4: then a new CREDIT_CARD is made
suspend fun setTracked(id: Long, tracked: Boolean)
suspend fun rename(id: Long, name: String?)                                 // blank or null goes back to the bank name
suspend fun deleteUnused()                                                  // accounts no payment or bill refers to
```

### `CardBillRepository`

```kotlin
suspend fun upsert(bill: CardBill): Long        // replaces the bill of the same bank + cardLast4 + dueDate; a bill with
                                                // no due date, or with neither bank nor card, is always a new row. A
                                                // replaced bill keeps the earlier SMS's receivedAt and smsId, and
                                                // remindedAt; amounts and account come from the later SMS, in
                                                // whichever order they are stored (D7)
suspend fun get(id: Long): CardBill?
fun observe(id: Long): Flow<CardBill?>
suspend fun all(): List<CardBill>
suspend fun setRemindedAt(id: Long, at: Instant)
suspend fun deleteBefore(before: Instant)
fun observeAll(): Flow<List<CardBill>>
fun observeDueFrom(from: LocalDate): Flow<List<CardBill>>
fun observeForAccount(accountId: Long): Flow<List<CardBill>>
```

### `ReviewRepository`

```kotlin
fun observeOpen(): Flow<List<ReviewItem>>
fun observeOpenCount(): Flow<Int>
suspend fun get(id: Long): ReviewItem?
suspend fun add(item: ReviewItem): Long
suspend fun resolve(id: Long)                   // resolved = true, body cleared
suspend fun deleteBefore(before: Instant)
```

### `ReadSmsRepository`

```kotlin
suspend fun alreadyRead(messages: Collection<SmsKey>): Set<SmsKey>   // id, arrival time and sender must all match
suspend fun markRead(entries: List<ReadSms>)    // first outcome wins
suspend fun forgetBefore(before: Instant)
fun observeCounts(): Flow<ReadCounts>
```

### `TransactionRunner`

`suspend fun <T> run(block: suspend () -> T): T` runs repository writes as one Room transaction (nestable).

### `SettingsRepository`

```kotlin
val settings: Flow<AppSettings>
suspend fun setUserName(name: String)           // trimmed; blank removes it
suspend fun setTrackingMode(mode: TrackingMode) // SMS or MANUAL
suspend fun setTrackingStart(at: Instant)
suspend fun setLastScan(at: Instant)
suspend fun setThemeFollowsSystem(follows: Boolean)
suspend fun setRemindersOn(on: Boolean)
suspend fun markSmsAsked()                      // the person said no to Android's SMS dialog
suspend fun completeOnboarding()
```

`AppSettings(userName: String?, onboardingDone: Boolean, trackingMode: TrackingMode?, trackingStartAt: Instant?,
lastScanAt: Instant?, themeFollowsSystem: Boolean, remindersOn: Boolean, smsAskedBefore: Boolean)`. `remindersOn` (default true, set with
`setRemindersOn(on)`) turns the card bill reminder on and off (`CardBillReminders`, below). Tracking start: "Start fresh" = the moment chosen; "This
month" = first day of the month 00:00 local; "Last 3 months" = first day of the month two months back; "From a
date" = that date 00:00 local.

## Dependencies: `AppContainer`

Made once in `ExpensesApplication.onCreate()`. Properties: `clock: Clock` (`Clock.system(AppContainer.ZONE)`, India
time), `dispatchers: AppDispatchers(io, default, main)`, `appScope: CoroutineScope` (process lifetime, SupervisorJob + default), `parser: SmsParser`, `database`,
`transactions`, `expenses`, `accounts`, `cardBills`, `reviews`, `readSms`, `settings`, `smsInbox: PagedSmsInbox`
(the phone's inbox, read page by page) and `ingestPipeline: IngestPipeline`. Splitting adds `httpClient`, `api:
GroupsApi`, `auth: AuthRepository`, `sync: SyncScheduler`, `outbox`, `syncEngine`, `liveRoom`, `groupNotifier`, `push:
PushSetup`, `groups: GroupsRepository`, `rejectedChanges: RejectedChanges`, `network: NetworkStatus`, `autoSplitter`,
`settlementMatcher`, `newPaymentsHooks` (both, run by `IncomingSmsWorker`), `reviewSession: ReviewSessionStore`,
Family's `familyLinker: FamilyLinker`, `family: FamilyRepository` and `homeView: MutableStateFlow<HomeView>`, and
`onForeground()` (called by `MainActivity.onStart`: syncs when signed in, at most every 30 s). `ExpensesApplication` starts push (only with a Firebase project) and the 12-hour sync when
signed in.

ViewModels get dependencies through the factory, never from a global:

```kotlin
@Composable
fun AddToExpensesRoute(...) {
    val viewModel = appViewModel { app ->
        AddToExpensesViewModel(createSavedStateHandle().toRoute<AddToExpenses>().expenseId, app.expenses, app.clock)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    AddToExpensesScreen(state = state, onEvent = viewModel::onEvent)
}
```

Each screen: a stateless `XScreen(state: XUiState, onEvent: (XEvent) -> Unit, ...)` and an `XViewModel` exposing
`val state: StateFlow<XUiState>`. Take `Clock` from the container for "now" and the time zone; never call
`Instant.now()` or `ZoneId.systemDefault()` directly, so tests can fix time.

## Ingestion interfaces (`com.expenses.app.ingest`)

```kotlin
data class InboxSms(val providerId: Long, val message: SmsMessage)

interface SmsInbox {
    suspend fun read(since: Instant): List<InboxSms>     // inbox messages received at or after since, oldest first
}

interface IngestPipeline {
    suspend fun ingest(messages: List<InboxSms>): IngestResult
}

data class IngestResult(
    val detected: List<Expense>, val credits: List<Expense>, val newAccounts: List<Account>,
    val cardBills: List<CardBill>, val reviewItems: List<ReviewItem>,
    val duplicates: Int, val skipped: Int, val alreadyRead: Int,
)
```

`SmsInbox`: returns an empty list without READ_SMS; does its own I/O off the main thread; never logs or stores text.

`IngestPipeline.ingest` contract (also in KDoc):

1. Drop messages whose `SmsKey(providerId, receivedAt, sender)` is in `ReadSmsRepository.alreadyRead` (counted in
   `alreadyRead`).
2. Parse each with `SmsParser`. `Skipped` is never stored: `ReadSms(outcome = SKIPPED)` only.
3. Payments: `Duplicates.keepBankCopies` over the whole call, then against stored payments with
   `ExpenseRepository.findPossibleCopies` and the same rules (bank SMS wins; two bank SMS are one payment only with
   the same reference). A stored row with the same sender and the same arrival time is the same message read again
   under a new provider id, and is a copy too. A dropped copy is `ReadSms(DUPLICATE)`. If the stored copy came from a non-bank sender and a
   new bank SMS reports the same payment, update the stored row in place with the bank SMS's fields, keeping its id,
   status, category, note and scope.
4. DEBIT → `Expense(status = DETECTED, source = SMS)`, never ADDED. CREDIT → `status = ADDED` (listed, never counted).
   Category from `ExpenseRepository.guessCategory`. Fill `smsId`, `smsSender`, `smsReceivedAt`, `originalMessage`,
   `referenceId`, `counterpartyVpa`, `paidWith = PaidWith.from(channel)`, `lowConfidence = confidence == LOW`,
   `occurredAt = occurredAtMillis ?: receivedAtMillis`.
5. Account from `AccountRepository.findOrCreate(bank, AccountType.from(instrumentType), instrumentLast4)`. Payments
   of untracked accounts are still stored, without `originalMessage`; `observeDetected` hides them, and
   `observeLatest` / `observeMonth` hide their credits.
6. `CardBill` → `CardBillRepository.upsert` (account found or created as `CREDIT_CARD`), `ReadSms(CARD_BILL)`.
   A card first stored as `OTHER` (an HDFC spend that only said "Card 4521") becomes `CREDIT_CARD` when a bill, a
   payment towards the card or a typed spend later names the same bank and last digits as a credit card, unless the
   bank also has a bank account or debit card with those digits (the `OTHER` account may hold its payments): then a
   new credit card is made and the `OTHER` account stays as it is.
7. `Unreadable` → `ReviewRepository.add(ReviewItem(...body = text))`, `ReadSms(REVIEW)`.
8. Each message's writes and its `ReadSms` row go in one `TransactionRunner.run { }`.

Payments found by the onboarding scan of past months start as DETECTED like any other; when more than one waits,
Home's payment card offers "Review all N", which opens Review payments (swipe review and Add all, below).

### Review payments and bulk Add

- The stack is `observeWaiting()`: DETECTED debits of tracked or unknown accounts, oldest first. Money in is never
  in it; payments of an account switched off are left out, as on Home.
- A swipe or button calls `addWaiting(listOf(id))` or `ignoreWaiting(listOf(id))`; Add all calls `addWaiting` with
  every id on the stack, in one transaction. Each payment keeps the category it shows (the suggested one, or the one
  chosen on the card: Choose category without a draft stores it while the payment stays DETECTED). Rows that stopped
  waiting meanwhile (handled from a notification, for example) are left alone and dropped from the decision.
- Undo (on the stack and on All sorted) takes back the last decision with `backToWaiting(ids)`: after Add all, every
  payment it added.
- The run (its payments, decisions and splits, ids only, never SMS text) is kept in `ReviewSessionStore`, so Undo
  still works after Android stopped the app and All sorted counts every payment of the run, not only this visit.
  Payments decided elsewhere meanwhile drop out of the run. "Split it instead" on a card opens Split it; Done on
  Split. Sorted. comes back here, and the split counts in All sorted as added with the person's share (Review's Undo
  never undoes a split: Undo on Split. Sorted. does). The run records whether All sorted was reached; it ends on Done,
  on Back from All sorted, or on the next visit that finds a waiting payment that is not part of it (a run left on All
  sorted another way, by a link or notification, still offers Undo while nothing new waits). Only the person's own
  action (a decision, Undo, "Split it instead") reopens a sorted run, not a payment that arrives while All sorted
  shows.
- Privacy choice for Undo of Ignore: the SMS text is dropped the moment a payment is ignored, as everywhere else, and
  Undo brings the payment back to waiting without it (Expense detail then shows no original message). The text is
  not kept, on disk or in memory, in case of an Undo: the ViewModel's undo snapshots are copies with
  `originalMessage = null`. A payment Undo brings back is also missing its notification: it is not posted again.
- Notifications: nothing extra. `ExpensesApplication` already cancels the notification of every payment that leaves
  `observeDetected()` through `PaymentNotifications.cancelHandled`, so payments added or ignored here lose theirs.
- Duplicates: decided rows stay in the table with their `smsReceivedAt` and `referenceId`, so the 15 minute
  duplicate rules see them as before.

## Navigation

Type-safe routes in `ui/nav/Routes.kt` (`@Serializable`). `AppNavHost` puts the graphs together; each feature
registers its destinations from one file via an extension on `NavGraphBuilder` taking the `NavController`:

| Feature file | Function | Routes |
|---|---|---|
| `ui/screens/onboarding/OnboardingNav.kt` | `onboardingGraph(navController)` | graph `OnboardingGraph` (start `Welcome`): `Welcome`, `YourName`, `PrivacyPromise`, `SmsPermission`, `NotificationPermission`, `HowFarBack`, `Scanning`, `NothingFound`, `ConfirmAccounts` |
| `ui/screens/home/HomeNav.kt` | `homeDestinations(navController)` | `Home` (tab) |
| `ui/screens/groups/GroupsNav.kt` | `groupsDestinations(navController)` | `Groups` (tab), `NewGroup(people, friends)`, `AddFriend(groupId?)` |
| `ui/screens/grouppage/GroupPageNav.kt` | `groupPageDestinations(navController)` | `GroupPage(groupId)`, `GroupSettings(groupId)` |
| `ui/screens/split/SplitNav.kt` | `splitDestinations(navController)` | `SplitPayment(expenseId)`, `SplitTyped(draft)`, `ChoosePeople(selectedGroupId?)`, `SplitAdded(groupExpenseId)`, `RecurringSplitOffer(expenseId)` |
| `ui/screens/settle/SettleNav.kt` | `settleDestinations(navController)` | `SettleUp(groupId, memberId)`, `MarkAsPaid(groupId, memberId)`, `PaymentMatch(creditExpenseId)`, `BalanceCleared(groupId, memberId, amountMinor)`, `RemindOnWhatsApp(groupId, memberId)` |
| `ui/screens/join/JoinNav.kt` | `joinDestinations(navController)` | `JoinGroup(token)` |
| `ui/screens/splitwise/SplitwiseNav.kt` | `splitwiseDestinations(navController)` | `ImportSplitwise` (signed in only, through `SignInGate`; from New group on the Groups tab and from You > More), `GroupImported(groupId)` (opens in place of Import from Splitwise once every part is sent) |
| `ui/screens/activity/ActivityNav.kt` | `activityDestinations(navController)` | `Activity` (tab), `MonthView(yearMonth: String)`, `NeedsReview` |
| `ui/screens/you/YouNav.kt` | `youDestinations(navController)` | `You` (tab), `AccountsAndCards`, `WhatWeHaveRead`, `TrackingStartDate`, `TurnOnSmsTracking`, `YouAccount` |
| `ui/screens/payment/PaymentNav.kt` | `paymentDestinations(navController)` | `AddToExpenses(expenseId: Long)`, `ChooseCategory(expenseId: Long, draft: String? = null)`, `ExpenseAdded(expenseId: Long)`, `ExpenseDetail(expenseId: Long)`, `AddExpense(groupId: String? = null)` |
| `ui/screens/review/ReviewPaymentsNav.kt` | `reviewDestinations(navController)` | `ReviewPayments` (from "Review all N" on Home) |
| `ui/screens/signin/SignInNav.kt` | `signInDestinations(navController)` | `SignIn(fromWelcome: Boolean = false, forJoin: Boolean = false, forFamily: Boolean = false)`, `SignInName(fromWelcome: Boolean = false)` |
| `ui/screens/family/FamilyNav.kt` | `familyDestinations(navController)` | `SetUpFamily` and `FamilySettings(sendLink: Boolean = false)` (signed in only, through `SignInGate` with `SignIn(forFamily = true)`), `FamilyMonthView(yearMonth: String)` (docs/family.md 8.3) |
| `ui/screens/cardbill/CardBillNav.kt` | `cardBillDestinations(navController)` | `CardBillView(billId: Long, remind: Boolean = false)` (docs/card-bills.md 2.5; from Home's CARD BILLS rows and the card bill reminder) |

The four tabs sit in graph `MainGraph` (start `Home`). Register a tab with
`mainTab<Route>(MainTab.X, navController) { padding -> XRoute(contentPadding = padding) }`: it draws `MainScaffold`
with the bottom navigation. Tab to tab has no screen transition: each tab draws its own dock, and `MainScaffold`
slides the pill from the tab shown before (`LocalTabMemory`, provided by `AppRoot`) and fades the content in from 96%.
Helpers: `NavController.openTab(tab)`, `NavController.finishOnboarding()` (navigates to `MainGraph` and removes
onboarding from the back stack), `MainTab.route`, `MonthView.of(YearMonth)` and `MonthView.month`,
`FamilyMonthView.of(YearMonth)`, and `NavController.openFamilyMonth()` (sets `AppContainer.homeView` to Family, closes
the screens above Home and shows it: after Join Family, Set up Family and Leave Family, and for `AppLink.Family`). A
finished family screen is closed without saving it, so the next tap on its tab does not bring it back; only
`AppLink.Family` (`openFamilyMonth(keepScreens = true)`) saves the closed screens, as every other link does.

Screens move by their kind (`ui/nav/ScreenTransitions.kt`; values in `design/spec/motion.md`): a **Push** slides on
the X axis, a **Modal** (Sign in, Add expense, Choose category, New group, Add friend, Import from Splitwise) rises
from below over a still screen, a **Result** (Expense added, Split added, Balance cleared, Group imported) fades in and
grows from 96%, and a **Tab** reached from outside the tabs arrives the same way. A screen drawn inside `SignInGate`
only fades in while signed out. The NavHost's six transition lambdas come from `rememberNavTransitions` in one place;
no destination sets its own. **Adding a route:** add it to `ScreenKind` (the sets in `ScreenTransitions.kt`) when it
is not a plain push, and to the gate set when it is drawn inside `SignInGate`; `ScreenTransitionsTest` fails until
every route in `Routes.kt` has a kind.

Every navigate-on-click goes through `rememberNavigateOnce()` (it runs only while the screen is resumed) and
destinations that a double tap could stack use `launchSingleTop`; Back from a screen uses `navController.leave(entry)`.
`TurnOnSmsTracking` is the Privacy promise shown again before Android's SMS dialog when SMS tracking is turned on
from the Home tip or the You switch (Play's prominent disclosure). `ChooseCategory` with a `draft` stores nothing:
it hands the choice back under `CHOSEN_CATEGORY_KEY` in the previous entry's saved state, for Expense detail's
unsaved edit.

Read route arguments in the ViewModel with `createSavedStateHandle().toRoute<Route>()` (inside `appViewModel { }`)
or in the destination with `entry.toRoute<Route>()`.

Every route has its real screen (the stage 2 `PlaceholderScreen` is gone). Only edit your own `*Nav.kt`, your feature
package and your `strings_<feature>.xml`; `AppNavHost`, `Routes.kt`, `AppLinks.kt`, `SignInGate.kt` and
`Navigation.kt` are shared (add a route only if your feature needs a new one, and say so in your notes).

Start: `AppRoot` reads `AppSettings.onboardingDone` once through `AppRootViewModel` and starts at `OnboardingGraph`
or `MainGraph`. The onboarding feature must call `SettingsRepository.completeOnboarding()` (and set tracking mode,
start and name) before `finishOnboarding()`.

Screens opened from outside are `AppLink`s (`ui/nav/AppLinks.kt`): `Payment(expenseId, split)`, `Group(groupId)`,
`MoneyReceived(creditExpenseId)`, `AllSquare(groupId, memberId, amountMinor)`, `Join(token)`, `Family` (Home with the
Family side chosen; `openLink` sets `AppContainer.homeView` first), `CardBill(billId, remind)` (the card bill reminder's
tap and its "Remind friends"; `MainActivity` cancels that bill's notification when it reads the link). A `Group` link (or any route to `GroupPage` or
`GroupSettings`) whose id is the family on this phone opens Home on the Family side instead: a family is never shown
as a group. A notification builds its
tap intent with `MainActivity.openLinkIntent(context, link)` (or `openExpenseIntent(context, expenseId, split)`), extra
`MainActivity.EXTRA_LINK` (the link as JSON). Each different link also gets different intent data (a digest of the
link, never the token or ids), so two notifications' PendingIntents never overwrite each other whatever request codes
their areas pick; the same link gives the same intent, so its notification updates in place. It
targets the not exported activity alias `.NotificationTarget`; `MainActivity` (exported as the launcher) reads extras
only from intents sent to that alias, so no other app can open a payment's screen. A friend's pay link
`https://<LINK_HOST>/pay/<token>` reaches `MainActivity` through the App Link intent filter (host from `app.linkHost`);
only `/pay/` and a 24 character token are accepted (`PayLinks.tokenOf`), and the token is never logged. A start from
Recents (`FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`) follows no link: its screen was shown when the task began.
`MainActivity` is `singleTop`; `AppRoot` follows the link with `NavController.openLink` once onboarding is done and
Home is on the back stack. Before that a pay link (`AppLink.Join`) waits, also across recreation (the activity's
saved state), so a friend who installed the app from the link lands on Join group after onboarding; other links are
dropped. The screen opens on a fresh stack above Home, so Back from it goes to Home whatever was open
before (the screens above Home are saved as a tab switch saves them). `ExpensesApplication` removes a payment's
notification as soon as it stops being DETECTED, wherever it was added or ignored. On the notification, Add opens Add to
expenses and Split opens Split it (`SplitPayment`); only Ignore is handled in the background, by `PaymentActionReceiver`.

Split: Split on Home's card, the notification, Review payments, Add to expenses ("Split it instead") and Expense detail
("Split this expense") all open `SplitPayment(expenseId)`. It is wrapped in `SignInGate`: signed out, Sign in opens
first; Not now leaves the payment waiting. Plus is not built.

Sign in: onboarding has no sign-in. Welcome's "I already have an account" opens `SignIn(fromWelcome = true)`; after
signing in, a new account sees `SignInName` (name from the account, optional phone and UPI ID) and onboarding goes on
at `PrivacyPromise` (Your name is not asked again). Anywhere else (Groups, Split, You, Join group) a screen
navigates to `SignIn()`; after signing in it is shown again with `SIGNED_IN_KEY` set to true in its own saved state
("Back where they were"). A screen that needs sign-in before it can show anything wraps itself in `SignInGate`
(`SplitPayment`, `SplitTyped`, and `JoinGroup` with `SignIn(forJoin = true)`, which reads "Join a group"; `SetUpFamily`
and `FamilySettings` with `SignIn(forFamily = true)`, which reads "Share a family month"). A family invite link opened
signed out keeps `forJoin`: its kind is known only after sign-in. On Sign in, Not now and Back return with nothing changed; on `SignInName` the person is
signed in already, so Back (the top bar's and the system's alike) goes on as Continue does; while Continue saves, Back
waits, and a save that started finishes even if the screen goes. Google's sheet is offered once by itself when
Sign in opens; debug builds also show "Dev sign-in" (local server). A screen of one group keeps its live connection
with `LiveGroup(app.liveRoom, groupId)`.

## Groups and splitting screens

Built in stage 2 (docs/groups-build-plan.md section 4 has the full task list; docs/server.md 6.4 and 9 the rules).
Each screen is a stateless `XScreen` with an `XViewModel`, registered from its area's `*Nav.kt`.

- **Groups tab** (`GroupsViewModel`, 02-everyday/20, 21, 22, 39, 44): signed out shows 39 with `signin.GoogleButton`;
  the placeholders of 22 show only while a sync runs and nothing is stored yet, or a group the tab has not shown
  complete in this sign-in is still `LOADING`; outside a sync every group shows what the phone has. A friend row
  opens `GroupPage(directGroupId)` when there is a direct split, else it is not tappable. **New group**
  (`NewGroupViewModel`, 26) prefills `NewGroup.peopleKeys` and `NewGroup.newFriends` (friends added on Choose people,
  in no group yet) switched on; **Add friend** (`AddFriendViewModel`, 27) hands an `AddedFriend` back under
  `ADDED_FRIEND_KEY`, or adds the friend to a group and leaves (a group a sync removed meanwhile: it just leaves).
- **Home** (`HomeViewModel`): "Owed to you" / "You owe" tiles while signed in and splitting with someone, in tracking
  mode only (02 manual mode draws none); "Last time you split ..." from the split rule; the offline banner while
  signed in. **Activity** (`ActivityViewModel`): "From splits", the Splits filter and split rows.
- **Group page** (`GroupPageViewModel`, 22-25, 40-43) and **Group settings** (`GroupSettingsViewModel`, 25, 45, 46).
  The placeholders show while the group is `LOADING` (nothing pulled yet). "This month" lists oldest first, as 24 and
  42 draw it. Not saved rows (43): Try again runs `RejectedChanges.retry(id)` as one transaction (read, make again,
  forget), so a double tap makes one expense; it is hidden when the refused change cannot be read, no longer adds up,
  or names a payer or share who left (an edit may keep people the stored expense already has:
  `RejectedChanges.canRetry(change, members, stored)`).
- **Split flow** (`SplitViewModel`, `ChoosePeopleViewModel`, `SplitAddedViewModel`, `SameSplitViewModel`; 09-14, 48):
  Choose people's "Make a group" opens `NewGroup.with(pickedKeys(), pickedNewFriends())`. On Split. Sorted., a direct
  split's Balance Row reads "Rahul" / "owes you" (or "you owe"), as the Groups tab's friend rows do. `AutoSplitter`
  splits payments with an automatic rule. Add expense (`AddExpenseViewModel`) and Expense detail
  (`ExpenseDetailViewModel`) gained Paid by, Split this and the split row.
- **Settling up** (`SettleUpViewModel`, `MarkAsPaidViewModel`, `PaymentMatchViewModel`, `BalanceClearedViewModel`,
  `RemindViewModel`; 03-getting-paid-back/00-03, 05, 07) and `SettlementMatcher` (docs/server.md 9.5). A Send or Copy
  tap on Remind while its link is still being made runs once the link arrives.
- **You, Account, Sign in and Join group** (`YouViewModel`, `YouAccountViewModel`, `SignInViewModel`,
  `SignInNameViewModel`, `JoinGroupViewModel`). On Account, Back (top bar and system) waits while Save, Sign out or
  Delete runs, and each of them finishes even if the screen goes another way (a link opened on a fresh stack).

## Family screens (docs/family.md 1 and 8.3 to 8.6; design 06-family)

In `ui/screens/family/` unless named otherwise; each is a stateless screen with a ViewModel, registered from
`FamilyNav.kt`. Samples for previews and tests are in `FamilySamples`; the debug previews are "family-<frame>"
(`FamilyScreenPreviews.kt`).

- **Home, Family side** (`HomeViewModel` with `FamilyRepository` and the app-scoped `AppContainer.homeView`; Home's
  `HomeFamily.kt` draws the parts). Without a family: the person's own Family rows at their full amount (51) and the
  `FamilyCard.SetUp` card under the Balance Card ("Set up Family" opens `SetUpFamily`). With one:
  `HomeContent.FamilyMonth` (By person, Top categories hidden at none, Latest or the empty card; the placeholder shapes
  while `LoadState.LOADING`), label "Family spent in November", caption `FamilyCaption.Alone` or `Together`, the card
  `FamilyCard.Waiting` or `Invite` (both open `FamilySettings(sendLink = true)`), no payment card and no split tiles.
  Own rows open Expense detail; the partner's are not tappable. The Balance Card opens `FamilyMonthView`. Offline, the
  banner says the family month syncs when back online. The parts shared with Month view (Family) are in
  `FamilyMonthSections.kt`.
- **Set up Family** (`SetUpFamilyViewModel`, 00, 09 to 12): name (blank: error, "Invite partner" off), the facts, and
  "Earlier Family expenses" when `FamilyRepository.observeShareableEarlier()` is above 0. "Invite partner" (online only)
  creates the family, makes the link, opens the share sheet (`FamilyShare`, `Intent.createChooser`) with
  `FamilyInvite.message` and opens Home on the Family side; a created family whose link failed only makes the link on
  the next tap; `AlreadyInFamily` or a family already on the phone opens Home on the Family side. Back waits while it runs.
- **Join Family** (`JoinGroupViewModel` with `FamilyRepository`; `JoinGroupUiState.ReadyFamily` and `AlreadyInFamily`
  drawn by `JoinFamilyScreen` and `AlreadyInFamilyScreen`, 01, 13 to 16): a preview of kind FAMILY. "Join Family" goes
  through `FamilyRepository.join`, then `JoinGroupEffect.OpenFamily` opens Home on the Family side (loading).
- **Family settings** (`FamilySettingsViewModel`, 17 to 24): rename queued (on leaving the field or the screen), the
  partner row (owner: remove confirm; invite out: the invite sheet with Send link again and Cancel invite; none: Invite
  partner), Leave Family with its confirm; online actions show the Offline or Problem banner. `sendLink = true` shares
  the link once when the screen opens. Leave done: `FamilySettingsUiState.Left`, Home on the Family side; the family
  gone otherwise: `Gone`, the screen leaves.
- **Month view (Family)** (`FamilyMonthViewModel`, 27): "Home · November 2026", Family spent and Last month, the month
  buttons, By person, By category, every family expense by day; months move as Month view's do.
- **You**: the FAMILY row (`YouFamily`): Set up Family without a family, Family settings with one. **Sign in**
  `forFamily` (08). **Add to expenses**: "Swiggy is always Food · Family" when Family is chosen (25). **Expense
  detail**: the family delete body when the row is shared (`ExpenseDetails.inFamilyMonth`, 26).

A person across groups is `personKeyOf(member)` (`groups.data`): the user id, else the phone number, else "name:" and
the name in lower case. `FriendBalance.key`, `Person.key`, credit matching and avatar colours all use it, and
`directGroupWith` finds an existing direct split with a friend known only by name. `GroupsRepository.observeLoad` is
`LOADING` only until the first page of a group's first pull is in: a later pull, or one cut off offline, keeps the
stored data on show.

## Threading

- Room DAOs are main-safe (`suspend` and `Flow` run on Room's executors). Repositories add no dispatcher.
- DataStore is main-safe.
- Parsing many messages and reading the SMS provider: `withContext(dispatchers.default / io)`.
- ViewModels collect repository flows and expose `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)`.
- Work that must outlive a screen (a scan after onboarding, the notification receiver) uses WorkManager or
  `AppContainer.appScope`, never `GlobalScope`.

## Reading SMS: workers

- `ScanWorker.startOnboardingScan(context, from)` (unique, REPLACE): first drops everything read from SMS before
  `from` (payments, card bills, review items, `read_sms` rows and accounts nothing refers to), so going back and
  picking a later start leaves nothing from before it. A failed scan ends FAILED; Scanning SMS then offers Try
  again. Only a successful scan up to now stores `lastScanAt`.
- `ScanWorker.startOlderScan(context, newStart, oldStart)` ("Add older payments"): reads `[newStart, oldStart)` and
  moves the tracking start to `newStart` only when it succeeded, even if the screen was closed.
- `IncomingSmsWorker.enqueue(context, arrivedAt)` after each new SMS reads from `lastScanAt` (or the tracking start
  when there is none) less 30 minutes, never before the tracking start. `IncomingSmsWorker.catchUp(context)` (unique,
  KEEP) runs the same read without a new SMS; `ExpensesApplication` queues it when the app starts with SMS tracking
  on and whenever tracking begins.
- Turning SMS tracking on again (`turnSmsTrackingOn`) moves the tracking start and the last scan to now; the time
  it was off can be read with "Add older payments".

## Privacy rules for SMS text

- SMS text is never logged (no `Log`, no `println`, no crash messages with the body), never sent off the phone, and
  never put in notifications beyond amount, merchant and account.
- Text is stored in exactly two places: `ReviewItem.body` while the item is open (cleared on resolve) and
  `Expense.originalMessage` for Expense detail. Nothing else stores text; `ReadSms` stores only ids and outcomes.
- OTPs, promotions and every other `Skipped` message are never stored.
- The database and settings stay on the phone: `android:allowBackup="false"`, `android:fullBackupContent="false"`
  and `res/xml/data_extraction_rules.xml`, which excludes every domain from cloud backup and device-to-device
  transfer (Android 12 and later).
- An IGNORED payment and a payment of an untracked account keep no SMS text.
- Permissions declared in the manifest: `READ_SMS`, `RECEIVE_SMS`, `POST_NOTIFICATIONS`, and for splitting
  `INTERNET` and `ACCESS_NETWORK_STATE`. Receivers and workers are added by the ingestion and notification features.
- Nothing of the SMS reaches the server: the API only takes group expenses the person split (description, category,
  amount, date, shares), and a split of a person-to-person UPI payment is described by its category, never its UPI ID
  or a long number (`GroupText.descriptionFor`). `DtoPrivacyTest` checks the field names of every request body.
- The session (refresh and access tokens, user id, profile) is stored encrypted with an Android Keystore key
  (`KeystoreCipher`, alias `groups_auth_v1`); a new process reuses the stored access token until a minute before it
  expires. Cleartext HTTP only in debug builds, only to `10.0.2.2`.

## Card bills (docs/card-bills.md)

Package `com.expenses.app.cardbill`, made in `AppContainer` (`cardBillReader`, `cardBillReminders`). Everything stays
on the phone; the server gets nothing new.

- `CardBillMath`: the pure rules (2.2): statement date (the earlier of the first SMS day and due date − 15), the cycle
  (the month ending on it), the cycle's split card payments, what each friend owes (`min(shares, pairBalance now)`,
  removed members 0), friends' part, own part (at least ₹0), the bar (at most full), paid (the card's credits after
  the statement up to due + 7 add up to the bill), `BillState`, the current bill of a card, Home's list and the
  reminder time (`fireAt`: 09:00 three days before, never in 21:00 to 09:00).
- `CardBillReader`: `observeBill(id)` (`CardBillDetail`; null for a bill deleted, with no card, or on a switched-off
  card), `observeHomeBills(today)` (`CardBillSummary` rows), `detail(id)`. It combines the bill, its account and the
  other bills of the card, `ExpenseRepository.observeOnAccount(accountId, from, to)`, `GroupsRepository.observeOwedOn`
  (one `OwedPart` per group and friend of the cycle's split payments), the session and the tracking mode.
- `CardBillReminders`: `sync()` enqueues unique work `card_bill_reminder_<billId>` (tag `card_bill_reminder`, REPLACE,
  initial delay to `fireAt`, a `card_bill_fire_at_<millis>` tag so a second sync changes nothing) for each bill due a
  reminder, cancels every other bill's work (a running one is left to finish, never cancelled or replaced), and
  removes the posted notification of a bill now paid, replaced, deleted or switched off. `remind(billId)` is what `CardBillReminderWorker` runs: it checks everything again, waits
  again when it ran early or into quiet hours, posts, and sets `remindedAt`. `sync()` runs at every start and whenever
  the reminders switch, the tracking mode, a card's tracking or the bills change (`ExpensesApplication`, after
  onboarding), and after an SMS read that stored card bills or credits (`IncomingSmsWorker`, `ScanWorker`).
  WorkManager keeps the work across reboots by itself.
- `CardBillNotifications`: channel `card_bills` ("Card bills"), tag `card_bill`, id `billId.hashCode()`; "HDFC
  Millennia bill due in 3 days" / "Friends owe you ₹8,200 of the ₹24,300." with "Remind friends" (`AppLink.CardBill(id,
  remind = true)`) only when friends owe part, or "Pay ₹24,300 by 21 Nov.". Nothing posts without notifications
  allowed.
- Screens: `ui/screens/cardbill/` (`CardBillScreen`, `CardBillViewModel`, `CardBillUiState`, `CardBillNav`,
  `CardBillSamples`). Remind everyone pushes once per group to friends on the app who owe part, with the Group page's
  banners, then opens Remind on WhatsApp for the friend without the app who owes the most. Home shows CARD BILLS
  (`HomeUiState.cardBills`) on the Me side in SMS mode.
- See the code: `openInBrowser` / `openSourceCode` in `ui/nav/ExternalLinks.kt` (`ACTION_VIEW`, `CATEGORY_BROWSABLE`;
  nothing happens with no browser). The address is only in `strings.xml` (`source_code_url`), checked by
  `BuildRulesTest`.
