# Groups build plan (stage 2)

**History.** Stage 2 is built and merged; this plan is kept as the record of how it was split up and is no longer
followed. The `tools/stage2.sh` and `tools/stage2-owners.txt` scripts it names were removed after the merge. For Import from Splitwise, which this plan left out, `docs/splitwise-import.md`
supersedes it.

Written 2026-11-17 by the Android lead, revised the same day after a review. Stage 2 wires every Groups and split
screen to real data, adds the Sign in entry points and Join group, settling up and the SMS matches, the four
splitting decisions (product.md "Splitting decisions"), every state the designer added on 2026-11-17, and a few small
fixes. Six builders work at the same time, each in a copy of the project (section 0). **No file may be changed by two
areas.**

Read first: `CLAUDE.md`, `docs/product.md` (the "Splitting decisions" are binding), `docs/architecture.md`,
`docs/server.md` sections 4.5, 4.6, 5, 6.4 and 9, `design/spec/README.md` and `design/spec/flow.md`.

## 0. How the work is split and merged

The scaffold is not committed: HEAD (9566539) has none of it, and about 70 of its files, this plan and the new design
screens among them, are new files git does not track. So builders do not use git worktrees, and nobody takes diffs
with git (`git diff` leaves out new files, and `git add -N` counts as staging). `tools/stage2.sh` does it with plain
folder copies and never runs git:

1. **Seed** (the lead, once, before anyone starts): `tools/stage2.sh seed`. It copies the project as it is now into
   `$STAGE2_ROOT/base` (kept as it was, with its file list `base.files`) and into one folder per area,
   `$STAGE2_ROOT/<area>`. `STAGE2_ROOT` defaults to a folder next to the project, named like it with " stage2"
   added. Build output,
   Gradle caches, `node_modules` and `.git` are left out; `local.properties` is copied, so each folder builds as it
   is (checked: a seeded folder compiles).
2. **Build**: each builder works only in their own folder, builds and tests there, and runs no git command (the
   folder has no `.git`).
3. **A shared change during stage 2**: a builder who needs one stops and says so. The lead makes it in the main
   project, then runs `tools/stage2.sh share <path>...`, which copies it into `base` and into every area's folder.
4. **Collect**: `tools/stage2.sh collect <area>` lists the area's new, changed and deleted files against `base`,
   copies them to `$STAGE2_ROOT/out/<area>/files`, writes `changes.diff` to read, and fails when a change is outside
   the area's files. The areas' files are listed in `tools/stage2-owners.txt`, the same as section 4.
5. **Apply**: `tools/stage2.sh apply <area>` collects, then copies the area's files into the main project. Each area
   owns its files outright, so whole files are copied, not patches. It stops, changing nothing, when a file it would
   write was changed in the main project since seeding.

If the owner commits the scaffold first, nothing here changes: the copies are still made the same way.

## 1. Rules for every builder

- Edit only the files your area owns (section 4). The "may also edit" list is empty for every area: if you need a
  change in any other file, stop and say so in your report. Do not work around it by copying shared code.
- Some packages use declarations of another area's package. Keep each of these with the same name and signature
  (you may add a parameter with a default), and say in your report if you changed how one looks or behaves:
  - `activity`: `ActivityItem`, `ActivityItemDetail`, `ActivityItemId`, `ActivityItemRow`,
    `toActivityItem(splitLabels = emptyMap())`, `ActivityDay`, `activityDays`, `groupByDay`, `SectionExtraGap`
    (used by `home` and the lead's `month`).
  - `onboarding`: `hasSmsAccess`, `CollectEffects`, `OnboardingFrame`, `OnboardingBody`, `OnboardingHeadline`,
    `PrivacyPromiseScreen`, `AccountItem` and its helpers, `rememberSmsAccessRequest`, `rememberNotificationRequest`,
    `SmsAccessResult`, `needsNotificationPrompt`, `openAppSettings` (used by `home`, `signin`, `you`).
  - `payment`: `SystemDatePicker`, `PaymentPage`, `PaymentAccount`, `PaymentTime`, `accountText`, `label`,
    `merchantText`, `monthName` (used by `onboarding`, `review`, `you`); `NeedsReviewRoute(onBack)` (used by the
    activity area's `ActivityNav`).
  - `sms.PaymentNotifications` (split): `show(context, expense, account)`, `cancel(context, expenseId)`,
    `cancelHandled(context, waitingIds)`, `createChannel(context)` (used by the lead's `IncomingSmsWorker`,
    `PaymentActionReceiver` and `ExpensesApplication`).
  - `ui.format` (fixes): `PayeeWording` with `amountAndPayee`, and `Expense.payeeWording()` (used by `home`,
    `payment` and `PaymentNotifications`). Fixes may change the rule, not the shape.
  - `signin` (you-join): `GoogleButton(onClick, modifier)`. Groups-home imports it for 39 "Continue with Google";
    nobody redraws it.
  - `you` (you-join): `turnSmsTrackingOn` (used by `home`).
- Keep the constructors the container calls: `RejectedChanges(transactions, groups, outbox, mirrors, expenses,
  scheduler, clock, repository)`, `AutoSplitter(context, groups, expenses, signedIn, clock)`,
  `SettlementMatcher(context, groups, expenses, extras, signedIn, clock)`, `GroupNotifications(context)` (as
  `GroupNotifier`), `ReviewSessionStore(dataStore)`, and `GroupActionReceiver()` (named in the manifest). Each
  `*Nav.kt` keeps its `...Destinations(navController)` function.
- Never edit the shared files in section 3, the Room schema (`app/schemas`, entities, DAOs), `gradle.properties`,
  `~/.gradle`, anything under `server/`, `design/`, `docs/` or `tools/`. Put doc changes you want in your report; the
  lead writes them.
- Do not commit, stage, stash or switch branches, and run no git command in your folder.
- **Names that must not clash when the areas are merged** (two areas that add the same name each build on their own,
  and the merged build fails with "Duplicate resources"):
  - Every string, plural and string array starts with its file's prefix: `strings_grouppage.xml` →
    `grouppage_...`, `strings_split.xml` → `split_...`, `strings_settle.xml` → `settle_...`, `strings_notify.xml` →
    `notify_...`, `strings_join.xml` → `join_...`, and so on. Text that several areas show (the 02/23 wording "Check
    your connection and try again. Nothing is lost.", "Try again") is defined again in each area's own file with its
    own prefix. `BuildRulesTest` checks the prefixes.
  - A new debug preview entry's name starts with your area key and a dash (`group-page-someone-joined`,
    `split-amounts`, `settle-no-upi-id`); names that exist stay as they are. `BuildRulesTest` checks that no two
    entries share a name.
  - In shared packages (`preview.screens`, the test package `com.expenses.app.groups` and others), every top-level
    declaration you add is `private`, except your one `...Previews` list. Test class names start with the class or
    screen they test.
- Match `design/spec` exactly, in both themes: spacing, radius, colour tokens, text styles. Reuse the components in
  `ui/components` (`PageFrame`, `ChoiceDialog`, `BottomSheet`, `Banner`, `PlaceholderCard`, `PlaceholderRow`,
  `InfoRow`, `SettingRow`, `BalanceRow`, `BalanceCard`, `ExpenseRow`, `OptionCard`, `Tag`, `AppInput`, `AppChip`,
  `AppSwitch`, `AppButton`, `StatTile`, `StickerTile`, `EmptyState`). Never invent a new look. 3D icons in rows,
  tiles and empty states; line icons in bars.
- Text at least 14sp, touch targets at least 48dp, state never by colour alone, theme tokens only (no raw colours).
- Copy comes from the design JSON (`design/spec/screens/...json`, the `text` values). Where the design has no copy,
  write plain short sentences, put a `<!-- Not in the design: ... -->` comment on the string, and list it in your
  report. Strings go only in your own `strings_<area>.xml`.
- The app's name appears only in `strings.xml` (`app_name`). Copy that names the app ("Not on SumBuddy", "Get SumBuddy")
  takes it as a `%s` argument.
- SMS text never leaves the phone and is never logged. Tokens, ids, names and amounts are never logged either.
- Plus, Family (`06-family`), Splitwise import (`02-everyday/28` to `31`) and the card bill view
  (`03-getting-paid-back/04`, Home's card bill notification) are out of scope: leave them as they are now. (History:
  Family, Splitwise import and the card bill view were built later; see docs/family.md, docs/splitwise-import.md and
  docs/card-bills.md.)
- **Online-only actions** (leave, delete, undo-join, turn off link, remind, invites, share links) answer an
  `ActionResult` or `ApiResult`. Offline (`ActionResult.Offline`, or `network.online` false before you start): keep
  the screen as it is and show the `Banner` (Notice tone, wifi-off icon) at the top of the body with "You're
  offline" / the 02/23 wording "Check your connection and try again. Nothing is lost." (not designed for these
  screens: mark it). `Failed(code)` that the area does not handle by name: the same banner with "Could not do that
  now" / "Try again in a moment." (not designed). `SignedOut`: open `SignIn()`.
- Build, test and lint must pass in your folder before you report:

  ```bash
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21 ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
  ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
  ```

- Tests are JVM tests: Robolectric with Compose (`createComposeRule`) and Room in memory, MockWebServer through
  `GroupsTestEnv` (`app/src/test/.../groups/GroupsTestEnv.kt`, which you may use but not edit). It gives
  `env.groups`, `env.rejectedChanges`, `env.expenses`, `env.engine`, `env.fake` (the fake server), `env.scheduler`
  (counts sync requests), `env.me` (null for signed out) and `env.signedIn` (for the hooks). Put each test in a new
  file inside your own test folders (section 4).
- Do not run `wrangler` or the local server. The lead walks through the merged app on the emulator against the local
  server. You may capture your own debug previews (`design/tools/capture.sh <preview> dark|light`, run from your
  folder) only while you hold the emulator lock: `mkdir build/emulator.lock` (in the main project)
  succeeds when it is free; `rmdir` it when done, within 10 minutes. Installing your debug build replaces the app on
  the emulator; that is fine.
- Your report: what you built per screen, copy you wrote that the design lacks, open points, and any shared-file
  change you need.

## 2. What the scaffold already did (the base the folders are copied from)

Everything below compiles; unit tests pass (the live-server test is skipped as usual); lint has no error.

**Data and server contract** (lead-owned, use as is):

- DTOs: `GroupDto.ownerMemberId`, `MemberDto.joinedAt`, `undoUntil`, `canUndo` (server migration 0002).
- Room version 3 (automatic migration, tested): `GroupEntity.ownerMemberId`; `MemberEntity.joinedAt`, `undoUntil`,
  `placeName` (the friend's name the place had before someone joined it by link, kept on this phone); table
  `grp_rejected` (`RejectedChangeEntity`): a change the server refused, kept after its rollback.
- `SyncEngine` stores the owner, the join window and `placeName`, and keeps every refused change (except a refused
  group create) in `grp_rejected`. When a pulled member joined, was undone or was removed, it forgets the link this
  phone made for that place (the server turned it off), so "link sent" is never wrong.
- `GroupsApi.undoJoin(groupId, memberId, key)`.
- `GroupsRepository` additions:
  - `undoJoin(groupId, memberId): ActionResult` (online). On success the place is the friend again and this phone's
    link for it is forgotten. Refusals come back as `ActionResult.Failed(code)` and put this phone right at once:
    `undo_expired` and `not_joined_by_link` clear that member's join window (the banner goes now, whatever the
    phone's clock says); `not_owner` pulls the group again; `not_member` and `group_deleted` remove the group here.
  - `turnOffLinks(groupId, memberId): ActionResult` (46 "Turn off link").
  - `observeLinkedMembers(groupId): Flow<Set<String>>` ("link sent"), `observeUnsentCount(): Flow<Int>`.
  - `observeSplitLabels(): Flow<Map<String, SplitLabel>>` and `observeSplitLabel(groupExpenseId)` (group name,
    method, total, my share, payer, not sent).
  - `setSplitRuleAuto(counterparty, auto)`, `forgetSplitRule(counterparty)`.
  - `shareLink` makes a new link, once, when the server says the stored one was turned off (`404 link_not_found`).
  - A new group is owned by its maker locally too.
- `GroupDetail.ownerMemberId`: the owner's member. It is the stored one, or, until a group is pulled again after 0002
  reaches the live server, the active member who is `ownerUserId`. Use it, never `group.ownerMemberId`.
- `GroupDetail.joinsToUndo(now): List<MemberEntity>`: the banner rule of server.md 4.6 (the person owns the group;
  the member is on the app, not removed, not the person; `undoUntil` after now), newest first.
- `FriendBalance.directGroupId`: the direct split with that friend on this phone, or null. No side effect.
- `GroupText.checkUpiId(input): UpiIdInput` (`None`, `Valid(trimmed)`, `Invalid`) and `GroupText.isPayableUpiId`:
  the server's UPI ID rule (4.5), exactly.
- `AuthRepository.saveProfile(name, phone, upiId: Patch<String?> = Unchanged)`: one `PATCH /v1/me`.
- `RejectedChanges` (`app.rejectedChanges`): `observe(groupId)`, `dismiss(id)`; `retry(id)` is a stub for the
  group-page area. It holds the `GroupsRepository` for Try again.
- `ExpenseRepository.findByGroupExpense(groupExpenseId)`: the personal row of a group expense (the person's payment,
  or the mirror of their share), or null.
- `NetworkStatus` (`app.network.online: StateFlow<Boolean>`).
- `SyncScheduler.observeSyncing(): Flow<Boolean>` (a full sync is running).
- `NewPaymentsHook` and `runNewPaymentsHooks` (`ingest`): `IncomingSmsWorker` runs `app.newPaymentsHooks`, the
  settlement matcher and then the auto splitter, after new SMS are read. Each hook sees only the payments earlier
  hooks did not handle, so a repayment the matcher took is never also split as spending. Ids the hooks return get
  no "Payment detected" notification. Both hooks are stubs owned by areas; they take `signedIn: suspend () ->
  Boolean`, not the `AuthRepository`.
- `ReviewSessionStore` (`app.reviewSession`): a DataStore file for the current run of Review payments.
- DAO queries for the stubs: `GroupExtrasDao.allPendingUpi()`, `deletePendingUpi(id)`, `observeLinksOf(groupId)`,
  `setRuleAuto`, `deleteRule`.

**Navigation** (lead-owned):

- New routes in `Routes.kt`: `GroupPage(groupId)`, `GroupSettings(groupId)`, `NewGroup(people)` with
  `CREATED_GROUP_KEY`, `AddFriend(groupId?)` with `ADDED_FRIEND_KEY` and `AddedFriend`, `JoinGroup(token)`,
  `SplitPayment(expenseId)`, `SplitTyped(draft)`, `ChoosePeople(selectedGroupId?)` with `CHOSEN_SPLIT_GROUP_KEY`,
  `SplitAdded(groupExpenseId)`, `RecurringSplitOffer(expenseId)`, `SettleUp(groupId, memberId)`,
  `MarkAsPaid(groupId, memberId)`, `PaymentMatch(creditExpenseId)`, `BalanceCleared(groupId, memberId, amountMinor)`,
  `RemindOnWhatsApp(groupId, memberId)`, `YouAccount`. `SignIn` gained `forJoin`; `AddExpense` became
  `AddExpense(groupId?)`.
- Each new route is registered in its area's `*Nav.kt` with `PlaceholderScreen` (top bar and Back). Replace it.
- `AppLink` (`ui/nav/AppLinks.kt`): `Payment`, `Group`, `MoneyReceived`, `AllSquare`, `Join`. A notification opens one
  with `MainActivity.openLinkIntent(context, link)`. Each different link gets different intent data, so two links
  never share a PendingIntent whatever request codes the areas pick; the same link updates its own. Request codes
  are each area's own business. `NavController.openLink` opens a link on a fresh stack above Home.
- A friend's pay link opened before onboarding is done waits, also across recreation, and Join group opens when
  onboarding is done (a friend who installed the app from the link). Other links are dropped before onboarding.
- App Link: the manifest filter for `https://${linkHost}/pay/*` (from `app.linkHost`); `MainActivity` accepts only a
  24 character token (`PayLinks.tokenOf`) and opens `JoinGroup(token)`.
- `SignInGate(navController, entry, signIn) { content }`: signed out, Sign in opens first; Not now leaves the screen.
  Already around `SplitPayment`, `SplitTyped` and `JoinGroup` (with `SignIn(forJoin = true)`).
- Split entry points already open `SplitPayment(expenseId)`: Home's card, the notification's Split, Review payments'
  "Split it instead", Add to expenses' "Split it instead" and Expense detail's "Split this expense".
- Manifest: `GroupActionReceiver` (not exported) and `<queries>` for UPI apps, WhatsApp, shared text and the phone
  picker. No new permission.

**Shared components**: `PageFrame` (top bar, scrolling body, footer: the frame of every new full screen),
`ChoiceDialog` (the design's confirm dialog: 3D icon, Title, Body/M, two 44-high choice pills with 48dp touch
targets; checked on the emulator against 04-you/03), `BottomSheet` (the design's sheet, as on 37 and 46), `Banner`
(the banners of 03, 40 and 41: Notice tone on yellow-soft, Plain on surface, an optional action such as 40's "Undo";
Home's offline banner uses it), `PlaceholderCard` and `PlaceholderRow` (22's loading shapes; the Groups tab uses
them). Debug previews: `dialog`, `sheet`, `banner`, `placeholders`.

**Rows of split payments everywhere**: `toActivityItem(splitLabels)` turns a payment linked to a group expense into
the shared row ("Travel · your share of ₹558", the group's name under the amount). Month view passes the labels
already; Activity and Home pass them when groups-home wires `observeSplitLabels()`.

**Small fixes already done**:

- Back after the notification's Split (and any notification or link) now goes to Home: the screen opens on a fresh
  stack above Home, and a start from Recents no longer opens the old notification's screen again.
- "₹350 at Auto to station": `PayeeWording` (`ui/format`) gives "at" for shops, "to" for a person paid by UPI and
  "for" for something typed in. Home's payment card, the "Payment detected" notification and Expense added use it.
  The fixes area checks the rule (section 4.6).
- String names: the notification strings now start with `notify_` and the sync strings with `sync_`.

**Server** (lead-owned; local tests only, nothing deployed):

- `PUT .../links/{linkId}` answers `404 link_not_found` for a link that was turned off or ran out, instead of giving
  it out again.
- Leave and remove turn off the links the member made, not only the links for their place (as undo-join does).
- `npm run deploy` refuses while the live database lacks a migration (`scripts/check-remote-migrations.mjs`);
  docs/server-setup.md 4.2 says migrations first, then the Worker, on every deploy.

**Tools**: `tools/stage2.sh` and `tools/stage2-owners.txt` (section 0; since removed). `BuildRulesTest` (string prefixes, unique
preview names).

## 3. Shared files (lead-owned; nobody edits them in stage 2)

`ui/nav/**`, `ui/components/**`, `ui/theme/**`, `ui/icons/**`, `ui/format/Money.kt`, `ui/shell/**`, `ui/time/**`,
`ui/state/**`, `ui/screens/month/**`, `di/**`, `MainActivity.kt`, `ExpensesApplication.kt`, `groups/api/**`,
`groups/auth/**`, `groups/data/**` except `RejectedChanges.kt`, `groups/sync/**` except `GroupNotifications.kt` and
`GroupActionReceiver.kt`, `groups/push/**`, `data/**` except `data/settings/ReviewSessionStore.kt`, `ingest/**`,
`sms/**` except `PaymentNotifications.kt`, `AndroidManifest.xml`, `app/build.gradle.kts`, `app/schemas/**`,
`res/values/strings.xml`, `strings_shell.xml`, `strings_category.xml`, `strings_month.xml`, `strings_sync.xml`,
`themes.xml`, `font_certs.xml`, `src/debug/.../preview/PreviewRegistry.kt`, `PreviewActivity.kt`,
`preview/components/**`, `src/debug/.../groups/auth/**`, `src/release/.../groups/auth/**`, every existing test file
not listed under an area (`GroupsTestEnv.kt`, `BuildRulesTest.kt` included), `parser/`, `splits/`, `server/`,
`design/`, `docs/`, `tools/`.

All paths below are under `app/src/main/kotlin/com/expenses/app/` (code), `app/src/main/res/values/` (strings),
`app/src/debug/kotlin/com/expenses/app/` (previews, debug) or `app/src/test/kotlin/com/expenses/app/` (tests).
`tools/stage2-owners.txt` holds the same lists with full paths.

## 4. Areas

### 4.1 groups-home: Groups tab, New group, Add friend, Home and Activity

**Screens.** `02-everyday/20-groups`, `21-groups-empty`, `22-groups-loading`, `39-groups-not-signed-in`,
`44-groups-you-owe-overall`, `26-new-group`, `27-add-friend`; on Home `00-home` (Owed to you and You owe, "Last time
you split Swiggy with Flat 302."), `03-home-offline`, `33-home-several-waiting` (unchanged); `16-activity` (From
splits, the Splits filter, split rows).

**Owns.** `ui/screens/groups/**`, `ui/screens/home/**`, `ui/screens/activity/**`; `strings_groups.xml`,
`strings_home.xml`, `strings_activity.xml`; debug `preview/screens/GroupsScreenPreviews.kt`,
`HomeScreenPreviews.kt`, `ActivityScreenPreviews.kt`; tests `ui/screens/groups/**`, `ui/screens/home/**`,
`ui/screens/activity/**`.

**May also edit.** Nothing.

**Tasks.**

1. Groups tab from real data (a `GroupsViewModel` in `ui/screens/groups`): signed out (`auth.state`) shows 39;
   signed in with no group while `sync.observeSyncing()` is true, or a group's `load` is `LOADING`, shows 22 (the
   placeholders are already shared components); no group shows 21; else the list (20, 44). Remove the "could not
   load" case from the Groups tab: `GroupsUiState.CouldNotLoad`, its sample, the `screen-groups-error` preview and
   the `groups_error_*` and `groups_back` strings if nothing else uses them. 23 is the Group page's (4.2).
2. The card: overall above 0 "OVERALL YOU ARE OWED" (Tone=Positive), below 0 "OVERALL YOU OWE" (Tone=Negative, 44),
   0 the existing "Overall all settled"; "across N groups" is `OwedTotals.groupCount` (server.md 6.4). Group rows
   "3 people · owes you / you owe / all settled" with the net; tap opens `GroupPage(id)`. Friends from
   `observeFriends()`; a tap opens `GroupPage(friend.directGroupId)` when there is one; with none the row is not
   tappable (not designed: list it). Never call `directGroupWith` from a tap: it makes a group.
3. 39 "Continue with Google" is `signin.GoogleButton` (import it). Call `auth.signInWithGoogle(activity, auto =
   false)` from the ViewModel. Success with `isNew` opens `SignInName()`; `Failed` or `NotConfigured` opens
   `SignIn()` (it shows the error state and, in debug builds, Dev sign-in); Cancelled stays.
4. "+ New" and "New group" open `NewGroup()`. Leave "Coming from Splitwise?" as it is (Splitwise is out of scope).
5. New group (26): name (`GroupText.MAX_GROUP_NAME`), "Add friends" switches over `groups.observeKnownPeople()`
   ("On SumBuddy" / "Not on SumBuddy · gets a WhatsApp link", app name from `app_name`), people prefilled from
   `NewGroup.peopleKeys`, the info row, "Create group" → `groups.createGroup`. After the people, the "Add someone" /
   "By name and phone number" Setting Row of 25 (plus icon) opens `AddFriend()`; the friend who comes back under
   `ADDED_FRIEND_KEY` is added to the list, switched on. With nobody known yet, the list is just that row. 26 has no
   such row: list it as not designed. After Create, set `CREATED_GROUP_KEY` on the previous entry; when that entry is
   the Groups tab, open `GroupPage(id)` in place of New group, else leave. Do not show "Import from Splitwise
   instead".
6. Add friend (27): name, phone (`GroupText.isPhoneOk`), the info row, "Add friend", "Pick from contacts"
   (`Intent.ACTION_PICK` on `ContactsContract.CommonDataKinds.Phone.CONTENT_URI`; read only that row's name and
   number; no contacts permission; nothing else is read or uploaded). `AddFriend(groupId = null)` hands an
   `AddedFriend` back as JSON under `ADDED_FRIEND_KEY` (New group and Choose people both read it); with a group id it
   calls `groups.addMember(groupId, NewPerson.Friend(...))` and leaves.
7. Home: tiles "Owed to you" / "You owe" from `groups.observeTotals()` while signed in and in at least one group or
   direct split (hidden otherwise, as 01 and 02 draw none); a tap opens the Groups tab. The payment card's "Last time
   you split {payee} with {group}." from `groups.splitRuleFor(payee)` and the group's display name. The offline
   banner (03, already the shared `Banner`) while `network.online` is false and the person is signed in. Latest rows
   pass `observeSplitLabels()` to `toActivityItem`, so split payments read "Travel · your share of ₹558" with the
   group's name.
8. Activity: "From splits" = the month's counted share of rows linked to a group expense; the Splits filter (rows
   whose detail is `ActivityItemDetail.Split`); rows pass `observeSplitLabels()` to `toActivityItem`.
9. Fix: a credit that names nobody shows "Money in" twice (title and subtitle, `ActivityRows.kt`). Say it once.

**Copy.** The JSON of the screens above. "OVERALL YOU OWE" is in 44.

**Data.** `auth.state`, `auth.signInWithGoogle`, `groups.observeGroups/observeFriends/observeTotals/
observeKnownPeople/createGroup/addMember/splitRuleFor/observeSplitLabels`, `sync.observeSyncing()`,
`network.online`, `expenses` as today.

**Tests.** Groups ViewModel (each state, the card's sign and "across N", 39's sign-in outcomes, a friend with and
without a direct split); New group (prefill from keys, a friend from Add friend, create, `CREATED_GROUP_KEY`); Add
friend (phone check, both modes); Home ViewModel (tiles shown and hidden, last-split hint, offline banner, a split
row); Activity ViewModel (From splits, Splits filter, split row); a Compose test that a nameless credit shows "Money
in" once.

### 4.2 group-page: Group page and Group settings

**Screens.** `02-everyday/24-group-page`, `23-could-not-load`, `40-group-page-someone-joined`,
`41-group-page-join-undone`, `42-group-page-you-owe`, `43-group-page-change-not-saved`, `25-group-settings`,
`45-group-settings-not-owner`, `46-group-settings-friend-s-link`.

**Owns.** `ui/screens/grouppage/**`, `groups/data/RejectedChanges.kt`; `strings_grouppage.xml`; debug
`preview/screens/GroupPageScreenPreviews.kt`; tests `ui/screens/grouppage/**`, `groups/RejectedChangesTest.kt`.

**May also edit.** Nothing.

**Tasks.**

1. Group page from `groups.observeGroup(id)`, `observeLoad(id)` and `rejectedChanges.observe(id)`, kept live with
   `LiveGroup(app.liveRoom, groupId)` while shown. Its states:
   - `LOADING` with the group known: 22's placeholders (`PlaceholderCard`, then `PlaceholderRow` ×
     `PlaceholderRowCount`) in the `PageFrame` body, under the top bar with the group's name.
   - Not on this phone yet (`observeGroup` gives null). This happens on every Join group, which opens the page before
     the pull. Offline: 23. Online: ask for one sync (`app.sync.requestSync()`) and show the placeholders under an
     empty title. When a sync has run since (`observeSyncing()` went true, then false) and the group is still not
     here, or 15 seconds passed, the group is gone (the person left, was removed or undone, it was deleted, or they
     signed out): leave for the Groups tab (`navController.openTab(MainTab.Groups)`). Not designed: list it.
   - `FAILED`: 23, built from `PageFrame` (title = the group's name, no action), `EmptyState` (alert icon, "Could not
     load this group" / "Check your connection and try again. Nothing is lost.") and a Primary Medium "Try again" →
     `app.sync.requestSync()`, with your own `grouppage_` strings.
   - The group goes while the page is open (a sync removed it, or this page's Leave or Delete succeeded): leave for
     the Groups tab the same way.
2. 24: top bar = group name (a direct split: the friend's name), "Add" → `AddExpense(groupId)`. Balance Card: net
   above 0 "{GROUP} OWES YOU", below 0 "YOU OWE {GROUP}" (Tone=Negative, 42); caption "{n} people · {k} expenses in
   {Mon}". Balances (6.4): pair above 0 "owes you +₹", below 0 "you owe -₹", 0 with net 0 "all settled ₹0", 0 with a
   net "settles with others ₹0" (42). "This month" rows: "{date} · {equally|amounts|%|shares}", or "· splits itself"
   when `auto`; meta "You paid" / "{name} paid".
3. Taps on the page (none is designed; list them):
   - A Balance Row: the person owes them (pair below 0) → `SettleUp(g, m)`; they owe the person (pair above 0) →
     `MarkAsPaid(g, m)`; 0 → not tappable.
   - A "This month" row: `ExpenseDetail` of the personal row `app.expenses.findByGroupExpense(id)` gives (the
     person's payment, or the mirror of their share), where editing and deleting go through the group (split area,
     9.4). No personal row (the person neither paid nor has a share): not tappable.
   - **Group settings is reached from a Setting Row at the end of the body**, after "This month": icon `users`,
     "Group settings", subtitle "People, name and leaving" (a direct split: "People and leaving"). The top bar keeps
     only the title and "Add", as designed.
4. Footer: "Settle up" (Primary) opens `SettleUp(g, m)` for the person the user owes most, or `MarkAsPaid(g, m)` for
   the person who owes the user most when the user owes nobody. "Remind" (Secondary) only when someone owes the
   user (42 hides it): every app user who owes gets `groups.remind(groupId, ids)` (push); when nobody on the app owes,
   `RemindOnWhatsApp(g, m)` for the friend without the app who owes most. Reminding another friend without the app
   is not in stage 2. Not designed in detail: list it.
5. Join banner (40) from `detail.joinsToUndo(now)` (re-check every minute with `minuteTicks(clock)`): `Banner` (Notice)
   with the user-plus icon, "{joiner} joined" / "Not {placeName ?: joiner}?" and a Secondary Medium "Undo" →
   `groups.undoJoin` (no confirm: none is designed). Done shows 41 (`Banner` Plain, check-circle, "Join undone" /
   "{friend} is back as not on the app. That link is now off.") until the person leaves the page. Every refusal
   already removes the banner in the data (section 2); offline the banner stays and the offline banner shows above it.
6. Not saved (43): each refused EXPENSE change is an Expense Row Kind=Review with Meta "Not saved", the note
   (create: "Not added to {group}. The group changed before it was sent."; edit: "Your edit was not saved. The group
   changed before it was sent."; delete: "Not deleted. The group changed before it was sent."), Try again (Secondary
   Medium) and Dismiss (Ghost Medium) → `rejectedChanges.dismiss`. Try again: when the refused change split one of the
   person's own payments (its `restoreJson` names the payment), the ViewModel opens `SplitPayment(thatId)` (the
   payment waits again) and dismisses the row. Otherwise build `RejectedChanges.retry(id)` with the constructor's
   `repository` (you may change `retry`'s signature; only your code calls it): a refused create is made again with a
   new expense id through `repository.addGroupExpense`, an edit through `updateGroupExpense`, a delete through
   `deleteGroupExpense`, and the row is forgotten. Hide Try again for 403, 413 and 422 codes. Other kinds are not
   shown (not designed).
7. Group settings (25, 45): name input (rename with `groups.renameGroup` when it changed and the person leaves the
   field or the screen); People: "You", app users "On SumBuddy", friends "Not on SumBuddy" or "Not on SumBuddy · link sent"
   (`observeLinkedMembers`); the owner's row carries Tag Tone=Brand "Owner" where Choose people has its switch
   (`detail.ownerMemberId`); "Add someone" → `AddFriend(groupId)`. A direct split shows no name, no Add someone and
   no Delete.
8. Leaving (25, 45; the rules are not designed, list them):
   - Leave group is tappable only while the person's net is 0; otherwise it is not tappable and its designed subtitle
     ("You can leave once your balance is ₹0.") says why. A tap opens a `ChoiceDialog` (logout icon; "Leave
     {group}?" / "You can come back only if someone adds you again." / "Leave group" / "Stay"), then
     `groups.leaveGroup`.
   - Delete group, for the owner: tappable only while every member's net is 0 (subtitle "Removes it for everyone.
     Balances must be settled first."). A tap opens a `ChoiceDialog` (trash; "Delete {group} for everyone?" /
     "Everyone loses its expenses and balances. This cannot be undone." / "Delete group" / "Keep it"), then
     `groups.deleteGroup`. Others see the lock icon and "Only {owner} can do this. {Owner first name} is the owner."
   - `NotSettled` (a balance changed meanwhile): the screen stays; the rows re-read and stop being tappable. Done:
     leave for the Groups tab.
9. Friend's link (46): tapping a friend without the app opens `BottomSheet` titled with the friend's name, the two
   info rows, the note, "Turn off link" → `groups.turnOffLinks` (online), "Cancel". Back closes it.

**Copy.** The JSON of the screens above; 43's edit and delete notes, the leave and delete confirms, the Group
settings row and the offline and failure banners are not designed (use the wording above and mark it).

**Data.** `groups.observeGroup/observeLoad/undoJoin/turnOffLinks/observeLinkedMembers/renameGroup/leaveGroup/
deleteGroup/remind`, `rejectedChanges`, `app.expenses.findByGroupExpense`, `app.liveRoom`, `app.network`,
`app.sync`, `app.clock`.

**Tests.** ViewModel: placeholders while loading, a group not here yet (offline 23; online gone after a sync →
leaves), banner shown only within the window and only to the owner, undo success shows 41, an undo refusal hides the
banner; balances text for each case of 6.4 (including "settles with others"); footer targets and Balance Row targets;
the This month row target; owner tag (also with `ownerMemberId` not stored yet) and the not-owner delete row; Leave
and Delete tappable only at 0; "link sent". `RejectedChangesTest` (Robolectric with `env.rejectedChanges`): retry of
a typed create, of an edit, of a delete; dismiss. The split-of-own-payment case opens `SplitPayment` (ViewModel test).

### 4.3 split: the split flow, Add to expenses, Add expense, Expense detail and payment notifications

**Screens.** `02-everyday/09-split-details`, `10-split-details-amounts`, `11-split-details-amounts-do-not-add-up`,
`12-choose-people`, `48-choose-people-several-friends`, `13-split-added`, `14-recurring-split-offer`,
`05-add-to-expenses` ("Split it instead"), `15-add-expense` (Paid by, Split this), `08-expense-detail` ("Split this
expense", the split row), `47-expense-detail-delete-split`, `04-notification` (the Split button; a notification for an
automatic split).

**Owns.** `ui/screens/split/**`, `ui/screens/payment/**`, `groups/split/**`, `sms/PaymentNotifications.kt`;
`strings_split.xml`, `strings_payment.xml`, `strings_sms.xml`; debug `preview/screens/SplitScreenPreviews.kt`,
`PaymentScreenPreviews.kt`; tests `ui/screens/split/**`, `ui/screens/payment/**`, `groups/split/**`,
`sms/PaymentNotifications*Test.kt`.

**May also edit.** Nothing.

**Tasks.**

1. Split it for `SplitPayment(expenseId)` (09 to 11), inside the existing `SignInGate`: the payment's Expense Row
   ("You paid"); "With" chips: the groups (`observeGroups`), the one chosen, "+ New" → `ChoosePeople` (a person with
   no group sees only "+ New"); prefilled from `splitRuleFor(payee)`. "How": Equally, Amounts, %, Shares (system
   keyboard for numbers); rows "You · your share · counts in {Month}" and "{name} · will owe you +₹".
   `SplitMath.shares` and `leftToAssign` for the note ("Only your ₹150 share goes into your November expenses." /
   "Amounts add up to ₹450. ..." / "₹50 is still left to assign. The parts must add up to ₹450."); "Split ₹450" stays
   off until the parts match. "Do this every time" (subtitle "{group}, {method}") → `rememberRule`. Split →
   `groups.splitPayment` → `SplitAdded(id)` in place of Split it.
2. `SplitTyped(draft)` from Add expense (15): you define the draft JSON (amount, what for, category, date, who paid,
   paid with). Same screen; `groups.addGroupExpense`.
3. Choose people (12, 48): search by name or phone; Groups and Friends switch rows (`observeGroups`,
   `observeKnownPeople`, "On SumBuddy" / "Gets a WhatsApp link"); one group → `CHOSEN_SPLIT_GROUP_KEY`; one friend →
   `groups.directGroupWith(person)` then the key; several friends → the 48 info row and "Make a group" →
   `NewGroup.with(keys)`; the group id that comes back under `CREATED_GROUP_KEY` is handed on as the choice. At the end
   of Friends, the "Add someone" / "By name and phone number" Setting Row of 25 opens `AddFriend()`; the friend who
   comes back under `ADDED_FRIEND_KEY` is added to Friends, switched on (a person with nobody known sees only the
   search and this row, so the first split works). Not designed: list it.
4. Split. Sorted. (13): the sentence, Balance Rows, the two info rows ("Remembered" only when a rule was kept). Done
   goes back to the screen that opened Split it: Review payments, Home or Expense detail; when that screen is Add to
   expenses for the same payment, it goes too (the payment no longer waits). Undo → `groups.undoSplit` and back to
   where the person was (the payment waits again).
5. Same split? (14): shown in place of 13 when the payment was split with an existing rule that is not automatic;
   "Yes, every month" / "Ask me each time" and Save → `groups.setSplitRuleAuto`. It is also how an automatic split is
   turned off (task 9).
6. `AutoSplitter` (`groups/split`, a `NewPaymentsHook`): for each new detected debit it gets whose merchant has an
   automatic rule, while `signedIn()` and when the rule's members are still active, re-read the payment and skip it
   unless it is still DETECTED, then `groups.splitPayment(..., auto = true)` and a notification (not designed:
   "{amount at payee} · split with {group}", tap opens `AppLink.Group`) in place of "Payment detected"; return the
   ids it split. It runs after the settlement matcher and never sees what the matcher took.
7. Add to expenses (05): drop the "splitting comes with sign-in" note (Split now opens Split it).
8. Add expense (15): Paid by chips, Paid with chips, "Split this" switch → "Next: choose split" opens `SplitTyped`.
   Paid by shows only "You" until a group is known: `AddExpense(groupId)` starts with Split this on and that group,
   and Paid by then lists You first and the group's active members. Turning Split this off puts Paid by back to You.
   Opened from Home or Activity, the payer is You (choose a group's page to add for someone else). Not designed: list
   it.
9. Expense detail (08, 47): a linked row shows the Info Row "{group}" / "Split equally · your share ₹150"
   (`observeSplitLabel`) and hides "Split this expense"; when the payee's rule is automatic, the subtitle ends with
   "· splits itself" and a tap on that row opens Same split? (`RecurringSplitOffer(expenseId)`) with "Yes, every
   month" chosen; "Ask me each time" and Save → `setSplitRuleAuto(payee, false)` (not designed: list it). Delete opens
   `ChoiceDialog` (Trash, "Delete Swiggy from Flat 302?", body, "Delete for everyone", "Keep it") →
   `groups.deleteGroupExpense` then the personal delete; edits of category or "what it was for" on a linked row (a
   mirror included, so someone else's expense can be put right) go through `groups.updateGroupExpense` (server.md
   9.4).
10. `PaymentNotifications`: the automatic split notification (task 6). Keep the four public functions as they are
    (section 1).

**Copy.** The JSON of the screens above. 13's sentence for one person or for a direct split, the automatic split
notification, the Add someone row and "· splits itself" on Expense detail are not designed.

**Data.** `groups.splitPayment/addGroupExpense/updateGroupExpense/deleteGroupExpense/undoSplit/directGroupWith/
observeGroups/observeGroup/observeKnownPeople/splitRuleFor/setSplitRuleAuto/forgetSplitRule/observeSplitLabel`,
`expenses`, `auth`, `SplitMath`.

**Tests.** Split ViewModel (each method, left to assign, the button off, rule prefill, what `splitPayment` gets);
Choose people (one group, one friend, several friends and the created group coming back, a friend from Add friend);
Split added Done and Undo targets; Same split? Save, also turning automatic off; `AutoSplitterTest` (Robolectric
with `GroupsTestEnv` and `env.signedIn`: an auto rule splits and returns the id, a removed member, signed out, or a
payment no longer DETECTED does nothing); Add expense Paid by with and without a group; Expense detail delete of a
split; `PaymentNotifications*Test` for the automatic split notification; `PaymentModelsTest` stays green.

### 4.4 settle: Settle up, Mark as paid, Money received, All square, Remind and the SMS matches

**Screens.** `03-getting-paid-back/00-settle-up`, `07-settle-up-no-upi-id`, `01-mark-as-paid`,
`02-payment-match`, `03-balance-cleared`, `05-remind-on-whatsapp`; `02-everyday/04-notification` ("Rahul paid you
₹1,200 / Flat 302 balance cleared. Nothing to tick.").

**Owns.** `ui/screens/settle/**`, `groups/match/**`, `groups/sync/GroupNotifications.kt`,
`groups/sync/GroupActionReceiver.kt`; `strings_settle.xml`, `strings_notify.xml`; debug
`preview/screens/SettleScreenPreviews.kt`; tests `ui/screens/settle/**`, `groups/match/**`,
`groups/sync/GroupNotifications*Test.kt`, `groups/sync/GroupActionReceiver*Test.kt`.

**May also edit.** Nothing.

**Tasks.**

1. Settle up (00, 07) for `SettleUp(g, m)`: Avatar, "You owe {name}" and the pair amount, Tag "{k} expenses ·
   {group}" (6.4's count), Option Cards "Pay by UPI" (only when `GroupText.isPayableUpiId(member.upiId)`) and "Paid
   another way", the "It clears itself" row; "Pay ₹1,200" opens `upi://pay` as server.md 7 builds it (pa, pn, am with
   2 decimals, cu=INR, tn = group name; `@` kept) and records a pending UPI payment (`grp_pending_upi`, through the
   matcher); "Paid another way" → "Mark ₹1,200 as paid" → `groups.recordSettlement(me → them, OTHER)`. 07: no UPI ID
   (or a friend without the app): the Info Row "No UPI ID for {name}" / "Ask {name} how to pay. Then mark it as paid
   here." and only "Paid another way".
2. Mark as paid (01) for `MarkAsPaid(g, m)`: "{name} paid you" and the amount, "Cash" / "Bank transfer or other",
   "{name} can confirm" only when they are on the app, "Mark ₹1,200 as paid" → `recordSettlement(them → me, CASH or
   OTHER)`.
3. `SettlementMatcher` (a `NewPaymentsHook`, server.md 9.5; does nothing unless `signedIn()`): a new debit of exactly
   a pending UPI amount within 30 minutes (and to the friend's UPI ID when the SMS shows one) →
   `recordSettlement(..., UPI, matchedFromSms = true)`, `expenses.ignore(id)`, forget the pending row, return the id
   (the auto splitter then never sees it). A new credit equal to what someone owes the person: one candidate whose
   name matches the SMS counterparty (case-insensitive word match) → settle at once (one settlement per group for each
   transfer) and post the 04 notification (tap → `AppLink.AllSquare`); same amount, unclear name → a notification
   "Was this {name} paying you back?" (tap → `AppLink.MoneyReceived`). Old pending rows are forgotten.
4. Money received (02) for `PaymentMatch(creditId)`: the credit (Tag "From SMS", +amount, account · time), "Was this
   {name} paying you back?", the candidate Balance Row "owes you for {group}", "Same amount" row, "Yes, clear it" →
   settle and open `BalanceCleared`; "No, something else" leaves.
5. All square (03): "All square with {name}", "The ₹1,200 arrived by UPI and the balance closed by itself.", the
   Balance Row, "Nothing to tick", "Nice" → Home. **One credit that clears a friend in several groups gives one
   notification and one screen.** The link names the group with the largest transfer settled and the friend's member
   in it (the route and link stay as they are). The Balance Row shows the friend's balance over every group after the
   credit (`observeFriends()` by the friend's key; not listed means "all settled ₹0"), so it reads right whatever
   group the link names.
6. Remind on WhatsApp (05): title "Remind {name}", the message preview ("Hey {name}" / "You owe {me} ₹1,150 for
   {group}." / "See the breakdown and pay by UPI:" / the link from `groups.shareLink` / "No app needed."), "Sent from
   your own WhatsApp", the two info rows, "Send on WhatsApp" (`ACTION_SEND` text to `com.whatsapp`, then
   `com.whatsapp.w4b`, else the share sheet) and "Copy link". The link uses `BuildConfig.LINK_HOST` through
   `shareLink`; never log it. Offline: the section 1 offline banner.
7. `GroupNotifications`: a tap opens `AppLink.Group(groupId)`; "{name} marked ₹X as paid" with `canConfirm` gets a
   Confirm action → `GroupActionReceiver` → `groups.confirmSettlement`. Keep the `GroupNotifier` interface as it is.
   Its strings are `notify_...` now.

**Copy.** The JSON of the screens above and 04's second notification. The payer-side "balance cleared"
notification and the Money received notification are not designed.

**Data.** `groups.observeGroup/observeFriends/recordSettlement/confirmSettlement/shareLink`,
`GroupExtrasDao.upsertPendingUpi/allPendingUpi/deletePendingUpi`, `expenses.observe/ignore`,
`IngestResult.detected/credits`, `auth.profile()`.

**Tests.** `SettlementMatcherTest` (Robolectric with `GroupsTestEnv` and `env.signedIn`): UPI debit matched within
30 minutes, too late, wrong amount, another UPI ID, signed out; credit with a matching name settles every group's
transfer; unclear name posts the question and settles nothing; nothing of the SMS reaches the fake server. Settle up
ViewModel (UPI shown or not, the count), Mark as paid, All square with a friend settled in two groups, Remind message
text and the WhatsApp intent; `GroupActionReceiver*Test` for Confirm.

### 4.5 you-join: You, Account, Sign in and Join group

**Screens.** `04-you/00-you` (signed in: "Signed in with Google"), `06-you-not-signed-in`, `02-account`,
`03-delete-account-confirm`, `07-account-upi-id-not-valid`, `08-sign-out-changes-not-sent`;
`01-onboarding/01-sign-in`, `02-sign-in-could-not-sign-in`, `03-your-name-after-sign-in` (UPI ID field);
`02-everyday/49-join-group-sign-in-first`, `32-join-group`, `50-join-group-link-no-longer-works`.

**Owns.** `ui/screens/you/**`, `ui/screens/signin/**`, `ui/screens/join/**`, debug and release
`ui/screens/signin/DevSignInEntry.kt`, debug `res/values/strings_debug_signin.xml`; `strings_you.xml`,
`strings_signin.xml`, `strings_join.xml`; debug `preview/screens/YouScreenPreviews.kt`, `SignInScreenPreviews.kt`,
`JoinScreenPreviews.kt`; tests `ui/screens/you/**`, `ui/screens/signin/**`, `ui/screens/join/**`.

**May also edit.** Nothing.

**Tasks.**

1. You tab: signed in, the profile line reads "Signed in with Google" and opens `YouAccount`; signed out (06), the
   Info Row "Sign in with Google" / "To split with friends. Your SMS stay on this phone." opens `SignIn()`. Plus, "See
   the code" and Import from Splitwise stay out (product.md).
2. Account (02, 07): Name, "Phone number (optional)", "UPI ID (optional)" and its note; Save checks the name,
   `GroupText.isPhoneOk` and `GroupText.checkUpiId` (Invalid → Input State=Error "That does not look like a UPI ID.",
   nothing saved), then `auth.saveProfile(name, phone, SetTo(upi or null))`; a 400 with field `upiId` shows the same
   error. Sign out → `auth.signOut()`; `UNSENT_CHANGES` opens `ChoiceDialog` (Logout, "{n} changes are not sent
   yet" as a plural, from `groups.observeUnsentCount()`, body, "Sign out anyway" → `signOut(force = true)`, "Stay
   signed in"). Delete (new subtitle) → `ChoiceDialog` (03: Trash, title, body, "Delete everything", "Keep my
   account") → `auth.deleteAccount()`.
3. Your name (after sign-in) (03): the UPI ID field and note, the same check, saved in the same `saveProfile`.
4. Sign in for a link (49, `SignIn(forJoin = true)`): heading "Join a group", reason "Sign in to join a group. Your
   SMS stay on this phone.", the "You were invited" row in place of "Groups and balances", the rest unchanged. Keep
   `GoogleButton`'s signature (groups-home uses it).
5. Join group (32) for `JoinGroup(token)` (inside the existing `SignInGate`): `groups.previewInvite(token)`; "{inviter}
   added you to {group}", "{n} people share expenses here. You can see what you owe before you join.", the group row
   ("Rahul, Priya and you"), "You owe ₹1,150 so far" / "{k} expenses in {Month}", "Join group" →
   `groups.acceptInvite` → `GroupPage(groupId)` in place of Join group (the page waits for the pull itself, 4.2);
   "Not now" leaves. `alreadyMember` opens the group at once. `link_not_found` (and `already_claimed`) shows 50 with
   "OK". Offline shows the 02/23 wording with Try again. Owed rather than owing is not designed. A friend who
   installed the app from the link reaches Join group after onboarding; the lead's `AppRoot` already does that.

**Copy.** The JSON of the screens above.

**Data.** `auth.state/profile/saveProfile/signOut/deleteAccount/signInWithGoogle`, `groups.observeUnsentCount/
previewInvite/acceptInvite`, `GroupText`.

**Tests.** Account ViewModel (UPI rule cases, server `upiId` error, unsent changes dialog then forced sign-out,
delete); Your name with a UPI ID; You signed in and out; Join ViewModel (preview, accept, already a member, link no
longer works, offline); a Compose test of Sign in's join variant.

### 4.6 fixes: Privacy promise, Review payments and the payee wording

**Screens.** `01-onboarding/05-privacy-promise`; `02-everyday/34-review-payments` to `38-review-payments-all-sorted`.

**Owns.** `ui/screens/review/**`, `ui/screens/onboarding/**`, `ui/format/PayeeWording.kt`,
`data/settings/ReviewSessionStore.kt`; `strings_review.xml`, `strings_onboarding.xml`, `strings_format.xml`; debug
`preview/screens/ReviewScreenPreviews.kt`, `OnboardingScreenPreviews.kt`; tests `ui/screens/review/**`,
`ui/screens/onboarding/**`, `ui/format/PayeeWordingTest.kt`, `data/settings/ReviewSessionStoreTest.kt`.

**May also edit.** Nothing.

**Tasks.**

1. The Privacy promise headline "Your messages never leave this phone" wraps to 3 lines; the design has 2 at 360dp.
   Find why (width, padding, style) and match the design without changing its text style. Keep `OnboardingHeadline`
   and `OnboardingFrame` as they are called (signin uses them).
2. Review payments keeps its run in `ReviewSessionStore` (ids only, never SMS text): Undo still works after Android
   stopped the app, and All sorted counts every payment of the run, not only this visit. Done on All sorted ends the
   run. Payments decided elsewhere meanwhile drop out of the run, as today.
3. A split from the run ("Split it instead" on a card opens Split it; Done on Split. Sorted. comes back to Review
   payments, section 5) counts in All sorted as added, with the person's share in the total. Review's Undo does not
   undo a split (Undo on Split. Sorted. does that), so Undo then goes back to the decision before it. Record the id as
   split when Split is tapped; when the run comes back, a payment that is ADDED with a `groupExpenseId` counts as
   added; one that still waits is still in the run.
4. Check `PayeeWording` against the parser's sample SMS (person-to-person UPI, merchants on UPI, cards) and adjust
   the rule; Home, the notification and Expense added already use it. Keep its shape (section 1).

**Copy.** The design JSON; no new copy.

**Data.** `expenses`, `app.reviewSession`.

**Tests.** `ReviewPaymentsViewModelTest` additions (Undo after a new ViewModel on the same store; All sorted counts
two visits; a split from the run counts as added; Done clears); `ReviewSessionStoreTest`; a Compose test that the
headline takes 2 lines at 360dp; `PayeeWordingTest` cases from real samples.

## 5. Hand-offs between areas

| From | Opens | Contract | Built by |
|---|---|---|---|
| Split it, Choose people | `NewGroup(people)` | the new group id under `CREATED_GROUP_KEY` in the opener's saved state | groups-home |
| New group, Choose people ("Add someone" row) | `AddFriend()` | an `AddedFriend` as JSON under `ADDED_FRIEND_KEY` | groups-home |
| Group settings | `AddFriend(groupId)` | adds the friend to that group and leaves | groups-home |
| Split it | `ChoosePeople(selected)` | a group id under `CHOSEN_SPLIT_GROUP_KEY` | split |
| Group page | `AddExpense(groupId)` | Split this on, that group, Paid by lists its members | split |
| Group page footer and Balance Rows | `SettleUp`, `MarkAsPaid`, `RemindOnWhatsApp` | (groupId, memberId) | settle |
| Group page "This month" row | `ExpenseDetail(expenseId)` | the personal row from `findByGroupExpense` | split |
| Group page Setting Row | `GroupSettings(groupId)` | | group-page |
| Groups (group and friend rows), Join group, notifications | `GroupPage(groupId)` | the page waits for a group not pulled yet | group-page |
| Not saved Try again | `SplitPayment(expenseId)` | | split |
| Review payments "Split it instead" | `SplitPayment(expenseId)` | Done on Split. Sorted. comes back to Review payments; the split counts as added in the run | split, fixes |
| Expense detail (a split that splits itself) | `RecurringSplitOffer(expenseId)` | Save with "Ask me each time" turns it off | split |
| You, Groups | `SignIn()`, `SignInName()` | `SIGNED_IN_KEY` as today | you-join |
| Join group | `SignIn(forJoin = true)` | | you-join |
| Notifications | `AppLink.*` via `MainActivity.openLinkIntent` | different links never share an intent; request codes are free | split, settle |

## 6. Decisions on the designer's and server's open points

1. Join banner names: "{joiner} joined · Not {friend}?", where joiner is the joining account's name and friend is
   `MemberEntity.placeName` (falls back to the joiner's name). When they are the same person it reads as the decision
   text. To confirm with the owner.
2. Undo has no confirm step (none designed). Undo is online; offline leaves the banner. Any refusal removes it.
3. Not-owner Group settings shows Delete group with the lock and the reason (45). There is no screen to remove someone
   on the app or to edit a friend: not built.
4. Refused-change copy for edits and deletes: the designer's proposals (4.2 task 6). Try again is hidden for 403, 413
   and 422.
5. "{n} changes are not sent yet" is a plural.
6. Dialog pills are drawn 44 high; `ChoiceDialog` gives them 48dp touch targets.
7. Settle up without a UPI ID (07) also covers a friend without the app.
8. The Remind link is the real one (`LINK_HOST`, a real token).
9. Not built: the SMS UPI ID suggestion, what Automatic reminders promise (the switch stays as it is), any label for
   repayments set to IGNORED, reminding a friend without the app who is not the one owing most.
10. "Not on SumBuddy" and the sheet title use `app_name`.
11. Group settings is reached from a Setting Row at the end of the Group page body (not a new top bar icon).
12. Adding someone new: New group and Choose people reuse 25's "Add someone" Setting Row, so a first-time user can
    make a first group and a first split.
13. Leave and Delete are tappable only at a 0 balance (the designed subtitles say why) and ask first with a
    `ChoiceDialog`.
14. A group that is not on this phone (Join group before the pull, an old notification, after leaving, deleting or
    an undo): the page waits for one sync, then goes to the Groups tab.
15. All square for a credit that cleared several groups: one notification and one screen; the Balance Row shows the
    friend over every group.
16. Add expense: Paid by lists people only once a group is known.
17. A split made from Review payments returns there and counts as added.
18. Turning an automatic split off: Expense detail's split row opens Same split?.
19. Month view shows split rows the same way as Activity (done in the scaffold).

## 7. Merge and release

1. The lead applies the six areas in this order, building, testing and linting after each: you-join, groups-home,
   group-page, split, settle, fixes (`tools/stage2.sh apply <area>`, section 0). A refused apply (a file outside the
   area, or one changed in the main project) goes back to that builder or is settled by the lead before going on.
2. Then the lead deletes `PlaceholderScreen` once nothing uses it, updates `docs/architecture.md` and `docs/server.md`
   from the reports, and walks through every screen on the emulator in both themes against a local server only:
   `wrangler dev` with `--persist-to` a scratch folder (two dev users on one emulator in turn, as server.md 14 C).
   Never against the live server: no request that creates data goes there.
3. **Release (the owner, not stage 2).** The live server still runs migration 0001 and the Worker from before the
   undo-join. In this order: apply `0002_undo_join.sql` to the live database (`npx wrangler d1 migrations apply xpense
   --remote` in `server/`), then deploy the Worker (`npm run deploy`, which now refuses while a migration is missing),
   then check `/v1/health`. Only then ship an app build that people use for groups. Until then the app does not depend
   on the new fields: the owner label falls back to `ownerUserId`, no member has a join window so the banner and Undo
   never show, a refused stored link is replaced, and the link fixes for leave and remove are simply not live yet.
   Nobody but the owner runs a remote command.
