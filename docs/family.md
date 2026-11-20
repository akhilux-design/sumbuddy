<!-- Spec for Family sharing. Written 2026-11-18 from a survey of the code, docs and design. -->
# Family sharing: implementation spec

Status: built and live (2026-11-20). Server (0003 applied to the live database, Worker version `3db84b59`), app (Room 6, FamilyLinker, FamilyRepository, every screen of `06-family`), reviewed and checked on the emulator against a local server. See docs/server-setup.md 4.2.

Design: `design/spec/screens/06-family/00` Set up Family, `01` Join Family, `02` Home (family month), `02-everyday/51` Home (Family) with no family; `flow.md` "03B Family month". Product: "Family is a pooled group with no owing. A Family expense counts in the payer's month and in the family month." This spec turns `ExpenseScope.FAMILY` (local today) into a shared family month, reusing groups, members, links/invites, the group version counter, pull, outbox and push. Nothing here changes split maths or `server/test/vectors`.

Every product choice below is listed in `decisions` with the recommendation used here. Items marked **(cut candidate)** can be left out without breaking the rest.

---

## 0. Rules in one place

1. A **family** is a server group of kind `family`: one owner who set it up, at most **2 active members** (counting an invite not yet accepted), **one live family per user**. Free (no Plus).
2. A **family expense** is a group expense in the family with exactly one share: the payer, for the full amount. So every net is always 0, nobody owes anybody, and settle-up, reminders and balances never apply.
3. Only the **payer** shares, edits or deletes a family expense, and only their **own** payments: a row is shared exactly while it is `scope = FAMILY`, `ADDED`, a rupee `DEBIT`, not split, within server limits, and a family exists on the phone.
4. What goes to the server per family expense: description (payee, with the UPI-ID / long-digit rule of 4.7, else the category name), category, amount, India date, payer member. **Never**: the note, account, card, "paid with", SMS text or sender, exact time, or any Just me expense.
5. Counting: the payer's own month counts it as today (personal row, `myShareMinor = amountMinor`); the family month counts every live family expense of the month, at its full amount. A partner's family expense never becomes a personal row on the other phone.
6. Earlier Family-tagged expenses on a phone are shared when that phone sets up or joins a family. Set up Family and Join Family say how many first.
7. Leaving, being removed, or deleting the account takes that person's family expenses out of the family (server tombstones). They stay on the person's phone, still tagged Family locally.
8. A family expense cannot also be a split. Splitting a shared Family payment takes it out of the family month; undoing the split shares it again.
9. Invite = the existing friend's-link mechanism (`https://<LINK_HOST>/pay/<token>`, App Link, Join group route) on a placeholder member; family links live **7 days**. No lookup by phone number.
10. Notifications: only "{name} joined {family}" (existing joined notification). No notification per family expense (pushes for them are NORMAL priority, background sync only).

---

## 1. Screens, entry points and states (what the person sees)

### 1.1 Entry points
- **Home, Family side of the toggle, no family on this phone** (signed in or out): today's 51 plus a **Family card** right under the Balance Card: Tag "Family", "Share this month with your partner", body, Primary Medium "Set up Family" → `SetUpFamily`.
- **You tab**: new section "Family" with one row (Info Row in a List card, Icon/users 3D) "Family": subtitle "Share a monthly total with your partner" (no family) → `SetUpFamily`; "Home · Arjun and Meera" or "Home · waiting for your partner" or "Home · just you" → `FamilySettings`.
- **A family invite link** opened in the app → `JoinGroup(token)` → Join Family (06/01) when the preview says `kind = "family"`.
- **"Meera joined Home" notification** → Home with the Family side selected (`AppLink.Family`).
- `SetUpFamily` and `FamilySettings` sit behind `SignInGate` with `SignIn(forFamily = true)` (family copy). A signed-out invite link keeps today's `SignIn(forJoin = true)` (49) because the kind is unknown before sign-in.

### 1.2 Set up Family (06/00)
- Top Bar "Family", sticker Icon/users on pink-soft, "One month for the two of you", body, Input "Family name" (prefilled "Home", 1–40 characters after trim; blank → Input State=Error "That does not look right."), facts as drawn, then **only when the phone has earlier shareable Family expenses** an extra Info Row (Icon/calendar 3D) "Earlier Family expenses" / "Your 12 Family expenses on this phone are shared too." Footer Primary Large "Invite partner".
- "Invite partner" (online only): create the family (with a placeholder member), make the placeholder's link, open the system share sheet with the invite message, then go to Home with the Family side selected (waiting state). Back is ignored while it runs.
- Offline: Offline banner at the top of the body ("You're offline" / "Check your connection and try again. Nothing is lost."), nothing created. Other failure: Problem banner ("Could not do that now" / "Try again in a moment."). If the family was created but the link failed, a second tap only makes the link (the ViewModel keeps the created family id; Home also shows the waiting card).
- `409 already_in_family` (a family made on another phone, or restored after sign-in): request a sync and open Home on the Family side.
- Opening Set up Family while a family already exists on the phone (race after sign-in): go to Home on the Family side.

### 1.3 Invite message (system share sheet, not WhatsApp-only)
Three lines joined by "\n":
1. "Arjun invited you to Home on SumBuddy: one monthly total for the two of you." (`%1$s` my first name, `%2$s` family name, `%3$s` `app_name`)
2. "Get the app, then open this link on your phone to join:"
3. the link `https://<LINK_HOST>/pay/<token>`
4. "Only expenses you mark Family are shared. Never your SMS."
Built only from `BuildConfig.LINK_HOST`; the token is never logged. Use `Intent.createChooser(ACTION_SEND text/plain, null)` (reuse `RemindShare.sendText`).

### 1.4 Join Family (06/01) on the partner's phone
- Preview `kind = "family"` → JoinFamilyScreen: sticker Icon/heart on brand-soft, "Arjun invited you to Home", "One monthly total for the two of you. Nobody owes anybody.", List card with Counts in both / Personal stays private, plus the "Earlier Family expenses" Info Row when this phone has shareable Family rows. Footer "Join Family" (Primary Large) / "Not now" (Ghost Medium).
- Join → `POST /v1/invites/{token}/accept` (online only) → Home, Family side; the phone pulls the family from version 0 (loading state), then shares its earlier Family rows (reconcile).
- `alreadyMember` → Home, Family side. `alreadyInFamily` (preview) or `409 already_in_family` (accept) → "You're already in a family" (built on 50). `link_not_found` / `already_claimed` → 50 "This link no longer works" (unchanged). Lookup offline/failure → 95/101 (unchanged). Join offline/failure → banners on the Join Family frame.

### 1.5 Home, Family side
| Situation | Balance Card | Under it |
|---|---|---|
| No family on this phone | "SPENT IN NOVEMBER", "your Family expenses on this phone" (51, local rows, full amounts, as today) | Family card "Set up Family", then 51 as today (payment card, split tiles, Latest of own Family rows) |
| Family, invite sent, nobody joined | "FAMILY SPENT IN NOVEMBER", "Just you · 12 expenses" | Family card Tag "Invite sent", "Waiting for your partner", "Send link again" → `FamilySettings(sendLink = true)`; then By person, Top categories, Latest |
| Family, partner left / invite cancelled | same label, "Just you · 12 expenses" | Family card Tag "Family", "Invite your partner", "Home has just you now. Send a link to share it again.", "Invite partner" → `FamilySettings(sendLink = true)` |
| Family with partner (06/02) | "FAMILY SPENT IN NOVEMBER", "Arjun and Meera · 64 expenses" | By person (Balance Row Direction=Plain per active member on the app, me first: "Arjun (you)" / "38 expenses" / "₹24,100"), Top categories (up to 2 Stat Tiles Tone=Neutral, largest first; 1 tile when one category; section hidden at 0), Latest (up to 5 Expense Rows Kind=Shared, Meta "Family", subtitle "Groceries · Meera paid" or "Food · You paid") |
| Month has no family expense | ₹0, "… · 0 expenses" | By person rows at ₹0 "0 expenses"; Top categories hidden; Latest replaced by an empty card "Nothing in the family month yet" / "When you add an expense, choose Family under Count it in." |
| First pull after join / sign-in (`LoadState.LOADING`) | placeholder shapes | placeholders as on 02-everyday/97 |
| Offline (signed in) | as above | Offline banner on top, body "Your own expenses still work. The family month syncs when you are back online." |

- With a family, the Family side shows no payment card and no split tiles (06/02).
- Own Latest rows open Expense detail of the linked personal row; the partner's rows are not tappable.
- Tapping the Balance Card on the Family side with a family opens **Month view (Family)** (cut candidate: without it, the card does nothing on the Family side with a family). Without a family it opens Month view (Me) as today.
- The toggle side lives in an app-scoped `MutableStateFlow<HomeView>` (`AppContainer.homeView`) so Join, the notification and Set up can open Home on the Family side. It resets to Me on a new process.

### 1.6 Family settings (route `FamilySettings(sendLink: Boolean = false)`)
- Top Bar "Family". Input "Family name" (rename on IME Done and on leaving the screen, as Group settings does; queued, works offline; blank → error, not saved).
- Label "People": Setting Row Icon/user "Arjun Mehta" / "You"; partner row "Meera Iyer" / "Partner" (owner taps → remove confirm; others: not tappable); or placeholder row Icon/user-plus "Your partner" / "Invite sent · not joined yet" (tap → invite sheet); or Icon/plus "Invite partner" / "They join with a link and need the app." (tap → add placeholder + link + share).
- Invite sheet (on 46): "Your partner", Info Row Icon/link "Link is on" / "Anyone with it can join Home as your partner.", note "Send it again if it got lost. Cancel invite turns the link off.", Primary Large "Send link again", Secondary Large "Cancel invite", Ghost Medium "Close".
- Label "Leaving": Info Row Icon/logout "Leave Family" / "Your Family expenses leave Home and stay on this phone." → confirm (on 92) "Leave Home?" / "Your Family expenses leave Home and stay on this phone as yours. You can join again with a new link." / "Leave Family" / "Stay".
- Remove confirm: "Remove Meera from Home?" / "Meera's Family expenses leave Home. They stay on Meera's phone." / "Remove" / "Keep".
- Online-only actions (send link, cancel invite, invite, remove, leave) show the Offline or Problem banner at the top of the body on failure. `sendLink = true` starts "Send link again" (or "Invite partner" when there is no placeholder) as soon as the screen opens.
- After Leave: back to Home, Family side (now no family). After the family disappears for any other reason while open: leave the screen.

### 1.7 Other screens
- Add to expenses (05): "Do this every time" with Family chosen reads "Swiggy is always Food · Family" and remembers the scope (see 8.5).
- Expense detail delete confirm for a row linked to a family expense: body "It will not count in November or in the family month any more. The bank message stays on your phone."
- Activity: unchanged. The Family filter keeps listing this phone's own Family rows; the family month of both people is on Home and Month view (Family).
- Groups, Choose people, Friends, Settle up, Remind, Money received, All square: never show a family (see 11).

---

## 2. Server data model: migration `server/migrations/0003_family.sql`

0001's `groups.kind` CHECK allows only `'group'` and `'direct'`, and SQLite cannot alter a CHECK in place, so `groups` is rebuilt with the order Cloudflare documents for D1 (`PRAGMA defer_foreign_keys`). `members`, `expenses`, `settlements` and `links` reference `groups(id)` with plain `REFERENCES` (no `ON DELETE CASCADE` / `SET NULL`), so the DROP's implicit delete removes no child row; it only counts foreign-key violations, which `PRAGMA defer_foreign_keys = off` clears before commit (D1: turning it off discards outstanding violations). **That last PRAGMA is required**: the re-insert happens before the DROP, so the violation counter would otherwise still be above 0 at commit and the migration would fail. Columns, rows and both partial indexes are kept exactly.

File, exactly (after a header comment that says the above and "apply --remote BEFORE deploying the Worker that writes 'family'"):

```sql
DROP TABLE IF EXISTS groups_new;   -- a leftover from a failed earlier attempt, never real data

PRAGMA defer_foreign_keys = on;

CREATE TABLE groups_new (
  id             TEXT PRIMARY KEY,
  kind           TEXT NOT NULL CHECK (kind IN ('group', 'direct', 'family')),
  name           TEXT,
  owner_user_id  TEXT REFERENCES users(id) ON DELETE SET NULL,
  version        INTEGER NOT NULL DEFAULT 0,
  created_at     INTEGER NOT NULL,
  updated_at     INTEGER NOT NULL,
  deleted_at     INTEGER,
  purged_at      INTEGER,
  CHECK ((kind IN ('group', 'family') AND name IS NOT NULL) OR (kind = 'direct' AND name IS NULL))
);

INSERT INTO groups_new (id, kind, name, owner_user_id, version, created_at, updated_at, deleted_at, purged_at)
  SELECT id, kind, name, owner_user_id, version, created_at, updated_at, deleted_at, purged_at FROM groups;

DROP TABLE groups;
ALTER TABLE groups_new RENAME TO groups;

CREATE INDEX groups_owner ON groups(owner_user_id) WHERE owner_user_id IS NOT NULL;
CREATE INDEX groups_to_purge ON groups(deleted_at, id) WHERE deleted_at IS NOT NULL AND purged_at IS NULL;

PRAGMA defer_foreign_keys = off;
```

No other schema change: a family uses `groups`, `members`, `expenses`, `expense_shares`, `changes`, `links`, `access_ended` as they are. "One family per user" and "2 members" are enforced by handlers plus the 3.1 guard (members has no kind column for an index). `changes.kind/op` CHECKs already cover everything a family writes.

Deploy order (docs/server.md 3 table gains a row): take a D1 Time Travel bookmark (`npx wrangler d1 time-travel info xpense`), apply 0003 `--remote`, then `npm run deploy` (the remote-migration check already blocks deploys until it is applied). 0003 alone changes nothing for the Worker that runs now (same columns). Before the live apply, run it once on a throwaway remote database (`wrangler d1 create xpense-scratch`, apply 0001–0003 with rows, then delete it) and locally on a populated `--persist-to` folder, as was done for 0002.

---

## 3. Server API (docs/server.md section 4)

New error: `409 already_in_family` (phone: show the "already in a family" state; never queued). New constant `MAX_FAMILY_MEMBERS = 2` (active members, the placeholder included), `FAMILY_LINK_TTL_MS = 7 days`. Shapes: `Group.kind` and `/v1/me/groups[].kind` may be `"family"`.

New helpers in `src/db/groups.ts`:
- `inAnotherFamily(db, userId, gid): Promise<boolean>`: `SELECT 1 FROM members m JOIN groups g ON g.id = m.group_id WHERE m.user_id = ?1 AND m.removed_at IS NULL AND g.kind = 'family' AND g.deleted_at IS NULL AND g.id <> ?2 LIMIT 1` (index `members_user`).
- `NOT_IN_ANOTHER_FAMILY(userParam)`: the same as SQL text for guard rules: `NOT EXISTS (SELECT 1 FROM members m2 JOIN groups g2 ON g2.id = m2.group_id WHERE m2.user_id = <userParam> AND m2.removed_at IS NULL AND g2.kind = 'family' AND g2.deleted_at IS NULL AND g2.id <> ?1)`.
- `familyExpenseTombstoneStmts(db, gid, mid, actor, now)`:
  ```sql
  UPDATE expenses SET deleted_at = ?3, updated_at = ?3, updated_by_member_id = ?4,
    version = (SELECT version FROM groups WHERE id = ?1)
  WHERE group_id = ?1 AND paid_by_member_id = ?2 AND deleted_at IS NULL;
  DELETE FROM expense_shares WHERE expense_id IN
    (SELECT id FROM expenses WHERE group_id = ?1 AND paid_by_member_id = ?2 AND deleted_at IS NOT NULL);
  ```
  (reads the family's expenses only, via `expenses_group_version`).
- `alreadyInFamily()` in `src/http.ts`: `conflict("already_in_family", "Already in a family.")`.

### 3.1 Create a family: `POST /v1/groups` (Idempotency-Key; online only on the phone)
```json
{ "id": "<uuid v4>", "kind": "family", "name": "Home", "myMemberId": "<uuid v4>",
  "members": [ { "id": "<uuid v4>", "name": "Partner" } ] }
```
→ `201 { "group": Group (kind "family", version 1), "members": [Member me, Member placeholder (userId null, onApp false)] }`.
- `parseGroupInput`: `kind` one of `group|direct|family`; family: `name` 1–40 (text rules); `members` exactly 1 (`400 bad_request` field `members`); that entry must have no `userId` (`400` field `members[0].userId`, "Family members join with a link.") and no `phone` (`400` field `members[0].phone`); its `name` 1–60. The phone always sends the fixed placeholder name `"Partner"` (never shown: after a join the user's own name wins). Ids v4 as for groups.
- Before the batch: `inAnotherFamily(me, input.id)` → `409 already_in_family`; caller at 300 groups → `409 limit_reached`.
- Batch: `createGroupStmts` gains `kind: "group" | "direct" | "family"` and `onlyFamilyOf?: string`; for a family the change insert becomes
  `INSERT INTO changes (...) VALUES (?1, 1, 'group', 'create', (SELECT ?1 WHERE <NOT_IN_ANOTHER_FAMILY(?4)>), ?2, ?3)` with `?4 = caller`. Two creates racing from two phones: the second fails the guard (NOT NULL `changes.entity_id`) → re-check → `409 already_in_family` (else `503 internal`).
- Same id again: unchanged `existingGroup` path (200 same content, 409 `exists_different`). No push (no other app user).

### 3.2 Rename: `PATCH /v1/groups/{gid}` `{ "name" }`
Allowed for `group` and `family` (any active member); `direct` still `403 not_allowed`. `renameGroupStmt`: `... AND kind <> 'direct'`. Queued on the phone (outbox), last writer wins.

### 3.3 Delete: `DELETE /v1/groups/{gid}` on a family
`403 not_allowed` ("A family ends when its last member leaves."). Not offered by the phone.

### 3.4 Leave: `POST /v1/groups/{gid}/leave` on a family → `204` (online only)
No balance check (nets are always 0). One batch:
1. `bump`
2. `removeMemberStmts(gid, myMemberId, now)` (marks removed, revokes links for and made by the place)
3. `familyExpenseTombstoneStmts(gid, myMemberId, actor = myMemberId, now)`
4. `passOwnershipStmt` when the caller owns it
5. guard `member`/`leave`, rule `removed_at IS NOT NULL`
6. `tombstoneIfEmptyStmts(gid, now)` (now `kind IN ('group', 'family')`: a family whose last member on the app leaves is tombstoned, the placeholder does not keep it alive)
7. `accessEndedStmt(me, gid, 'left', now)`
8. key
Guard failure → `requireMember` answer, else `503 internal`. After: `kick: [me]`, `groupsPush: [me]`, others get `changed` (NORMAL). Pulls carry the tombstones at the new version.

### 3.5 Remove a member: `DELETE /v1/groups/{gid}/members/{mid}` on a family → `204` (online only)
- Not yourself (`403 not_allowed`); target on the app: owner only (`403 not_owner`); target is the placeholder: any member ("Cancel invite"). Already removed → 204.
- No balance check. Batch: `bump`, `removeMemberStmts`, `familyExpenseTombstoneStmts(gid, mid, actor, now)` (no rows for a placeholder), guard `member`/`remove` with rule `removed_at IS NOT NULL AND user_id = ?7` (person seen by the checks) or `removed_at IS NOT NULL AND user_id IS NULL` (placeholder) — a place taken through the link between check and batch fails the guard and the checks run once more —, `accessEndedForMemberStmt(gid, mid, 'removed', now)`, key.
- After: `kick` and `groupsPush` the removed user, **no** `high` (no notification is designed).

### 3.6 Add the placeholder again: `POST /v1/groups/{gid}/members` on a family (online only)
Body `{ "id": "<uuid v4>", "name": "Partner" }` → `201 Member`. `userId` → `403 not_allowed` ("Family members join with a link."); `phone` → `400` field `phone`; active members already 2 → `409 limit_reached`; guard rule `(SELECT COUNT(*) FROM members mc WHERE mc.group_id = ?1 AND mc.removed_at IS NULL) <= 2`. `direct` keeps its 403.

### 3.7 Undo-join, reminders, balances, settlements on a family
`POST .../undo-join` → `403 not_allowed` (the owner removes instead). `POST .../reminders`, `GET .../balances`, `PUT .../settlements/{sid}`, `POST .../settlements/{sid}/confirm`, `DELETE .../settlements/{sid}` → `403 not_allowed` ("Families do not settle up."), checked right after `requireMember`.

### 3.8 Family expenses: `PUT /v1/groups/{gid}/expenses/{eid}` and `DELETE` (queued)
Body is the normal `ExpenseInput`:
```json
{ "description": "BigBasket", "category": "GROCERIES", "amountPaise": 234000, "occurredOn": "2026-11-15",
  "paidByMemberId": "<caller's member>", "splitMethod": "EQUAL", "auto": false,
  "shares": [ { "memberId": "<caller's member>", "sharePaise": 234000, "input": null } ], "baseVersion": null }
```
After `parseExpense`, for kind `family`:
- `paidByMemberId !== access.myMemberId` → `403 not_allowed` ("In a family you share your own payments only.").
- Update of an expense whose stored payer is not the caller → `403 not_allowed`.
- `splitMethod !== "EQUAL"` or `shares` not exactly `[{ memberId: caller, sharePaise: amountPaise, input: null }]` → `400 bad_request` field `shares` ("Family expenses are not split.").
- `auto === true` → `400` field `auto`.
- Everything else as 4.7 (text 1–80, category, amount 1..1,000,000,000, date 2000-01-01..tomorrow, 20,000 per group, LWW, `410 expense_deleted`, `overwrote`).
- After: `after(ctx, { gid, version })` with no `high`: the partner's phones get a NORMAL `changed` push and sync in the background.
`DELETE`: stored payer not the caller → `403 not_allowed`; otherwise as today.

### 3.9 Links: `PUT /v1/groups/{gid}/members/{mid}/links/{linkId}` on a family
Target must be the placeholder: target on the app → `409 member_on_app`. `insertLink` gains a `ttlMs` argument: `FAMILY_LINK_TTL_MS` for a family, `LINK_TTL_MS` otherwise; the answer's `expiresAt` is the stored `expires_at` (also on the 200 replay path, which today recomputes `createdAt + LINK_TTL_MS`). Revoke routes unchanged.

### 3.10 Invite preview: `GET /v1/invites/{token}`
Every preview gains `"kind": "group" | "direct" | "family"` and `"alreadyInFamily": false` (added at answer time, not in the cache body). For a family link (no cache, no `wholeGroup` read):
```json
{ "groupId": "…", "kind": "family", "groupName": "Home", "inviterName": "Arjun",
  "memberNames": ["Arjun"], "memberCount": 1, "youOwePaise": 0, "owedToYouPaise": 0,
  "expensesThisMonth": 0, "alreadyMember": false, "alreadyInFamily": false }
```
`memberNames` / `memberCount`: active members **on the app** other than the placeholder (first words). `alreadyInFamily`: `inAnotherFamily(caller, gid)`.

### 3.11 Accept: `POST /v1/invites/{token}/accept` on a family link
As today plus: `inAnotherFamily(caller, gid)` → `409 already_in_family`; the guard rule becomes `user_id = ?7 AND <NOT_IN_ANOTHER_FAMILY(?7)>`; on a guard failure, re-check and answer `already_in_family`, `already_claimed`, the replay, or `link_not_found`. `after(... high: everyone, groupsPush: [me])` unchanged → the owner gets "Meera joined Home".

### 3.12 Public link: `GET /v1/links/{token}` on a family link
`200 { "family": true, "familyName": "Home", "senderName": "Arjun" }`; nothing else (no expenses, amounts, other names, ids). Not cached (one member read for the sender's name). Revoked, expired, unknown, deleted: the same `404 link_not_found`. Headers unchanged.

### 3.13 Account deletion: `DELETE /v1/me`
New step **1b** right after step 1 (bump), before the group tombstones:
```sql
UPDATE expenses SET deleted_at = ?2, updated_at = ?2,
  version = (SELECT g.version FROM groups g WHERE g.id = expenses.group_id)
WHERE deleted_at IS NULL
  AND group_id IN (SELECT m.group_id FROM members m JOIN groups g ON g.id = m.group_id
                   WHERE m.user_id = ?1 AND g.kind = 'family' AND g.deleted_at IS NULL)
  AND paid_by_member_id IN (SELECT m.id FROM members m WHERE m.user_id = ?1);
DELETE FROM expense_shares WHERE expense_id IN (
  SELECT e.id FROM expenses e WHERE e.deleted_at = ?2
    AND e.group_id IN (SELECT m.group_id FROM members m JOIN groups g ON g.id = m.group_id
                       WHERE m.user_id = ?1 AND g.kind = 'family'));
```
The rest is unchanged (a family with the partner on the app lives on with "Former member"; a family with no other app user is tombstoned).

### 3.14 Unchanged
`GET .../changes` (family pages carry members and expenses; no settlements), `GET /v1/me/groups`, `PATCH /v1/me`, live room (allowed, not used by the phone), cron, rate limits, logging (route templates only).

### 3.15 Authorization summary (add to 4.2)
| Family route | Who |
|---|---|
| Create | any signed-in user not active in another live family |
| Rename | any active member |
| Add placeholder, link for the placeholder, cancel invite | any active member (family not full) |
| Remove the partner on the app | the owner |
| Leave | any active member |
| Expense create, update, delete | the payer, own member only |
| Delete, undo-join, settlements, reminders, balances | nobody (`403 not_allowed`) |
| Invite preview, accept | signed-in holder of a live token, not in another family |
| Public link | anyone with the token, family shape only |

---

## 4. Server tests (vitest)

New `server/test/family.test.ts`:
1. Create: 201, kind family, owner + placeholder, change `group/create`; `/me/groups` lists kind `family`; `GET changes?since=0` returns both members.
2. Create validation: members `[]`, two members, `userId` member, phone, blank name, 41 chars → 400 with the right `field`.
3. One family: second create → `409 already_in_family`; `testHooks.beforeBatch("group.create")` inserts another live family for the caller → guard → `409 already_in_family`; the caller can still create a normal group.
4. Invite preview of a family link: `kind`, zeros, `memberNames` without the placeholder, `alreadyInFamily` true when the caller is in another family; group links now carry `kind: "group"` / `"direct"`.
5. Accept: joins; `409 already_in_family` (check and guard race); push HIGH to the owner (FCM fake as in `push.test.ts`), `groups` to the joiner.
6. Expenses: payer must be the caller (403), stored payer (403 on update and delete), shares not `[payer, full]` (400 `shares`), method `SHARES` (400), `auto: true` (400), happy create/update/delete, 410 after delete, partner gets NORMAL `changed`.
7. `GET balances`, settlement `PUT`/confirm/delete, reminders, undo-join, `DELETE group` on a family → 403 `not_allowed`.
8. Leave: leaver's expenses tombstoned at the new version with shares gone, partner's untouched, partner's pull shows tombstones; `access_ended` `left`; ownership passes; last member on the app leaving tombstones the family; placeholder left alone does not keep it alive.
9. Remove: owner removes partner (expenses tombstoned, `access_ended` `removed`, kick); partner removing owner → `403 not_owner`; anyone cancels the placeholder (links revoked).
10. Add member: placeholder only, `userId` → 403, phone → 400, third active → `409 limit_reached`.
11. Links: link for the on-app partner → `409 member_on_app`; family link `expiresAt = createdAt + 7 days`; an 8-day-old link → `link_not_found` on preview, accept and public page.
12. Public page: family link body keys exactly `family`, `familyName`, `senderName`.
13. `DELETE /v1/me`: caller's family expenses tombstoned where the partner remains; partner's stay; family tombstoned when the caller was alone on the app.
14. Rename by the partner bumps the version.

New `server/test/migration.test.ts` (second D1 binding `MIGRATION_DB` in `vitest.config.ts` `miniflare.d1Databases`, declared in `test/env.d.ts`): apply 0001+0002 with `applyD1Migrations(env.MIGRATION_DB, migrations.slice(0, 2))`, insert users, a group, a direct split, members, expenses, shares, settlements, links, changes, access_ended; apply the rest; assert equal row counts and values, `PRAGMA foreign_key_check` empty, `groups_owner` and `groups_to_purge` exist, a family row inserts, a family with NULL name and kind `'pool'` fail, a member of an unknown group still fails the FK.

Changes to existing tests: `groups.test.ts` "validates names, phones and ids" (`kind: "family"` with `members: []` now fails on `members`; use `kind: "pool"` for the `kind` case); `authz.test.ts` (family routes as a non-member, signed out, deleted family); `links.test.ts` (`expiresAt` from the stored row); `logging.test.ts` (the spy flow also creates a family, invites, accepts, adds a family expense, leaves: no user value logged); `me.test.ts` (13 above); `sync.test.ts` (family page shape); `push.test.ts` (accept HIGH, family expense NORMAL).

---

## 5. Friends' web page (`server/web`)

- `public/pay.js` `parseLinkData`: when `json.family === true`, require `familyName` and `senderName` text and return `{ family: true, familyName, senderName }`; `stateOf` returns `'familyInvite'`; new `renderFamilyInvite(view, data, config)`, `textContent` only: sticker `heart_3d` (copy `design/icons/svg/heart` 3D into `public/icons/heart_3d.svg` and the build's icon list), h1 "Arjun invited you to Home", body "Home is a family month on SumBuddy: one monthly total for the two of you.", Info Row (user_plus_3d) "Get the app" / "Install SumBuddy on your phone, then open this link again to join.", footer Primary Large "Get SumBuddy" only when `config.appDownloadUrl` or `config.playStoreUrl` is set (the download link wins) (not the "(optional)" label). Document title "Family invite". App name from `config.appName`.
- `test/page-states.mjs`: the family state renders, shows no amounts, and has no Pay button; a malformed family body → "Could not load this page". Fixture added to `test/fixtures/links.mjs`.

---

## 6. App data layer

### 6.1 Room version 6 (`AutoMigration(5 → 6)`)
Version 5 is taken: Splitwise import was built first and added `grp_imports` (`AutoMigration(4 → 5)`,
docs/splitwise-import.md 5.1). Family is the next version, 6.
- `expenses.familyExpenseId: String?` (KDoc: "the family expense this payment is shared as; only the payer's own row") with `Index(value = ["familyExpenseId"], unique = true)`.
- `category_rules.scope: ExpenseScope?` (null = the rule says nothing about Just me or Family).
- `GroupKind.FAMILY` appended (stored by name; no schema change).
- Export `app/schemas/.../6.json`; `DatabaseMigrationTest` 5 → 6 keeps every row, new columns null.

### 6.2 DAOs
`ExpenseDao`: `findByFamilyExpense(id)`, `familyCandidates()` (`WHERE scope = 'FAMILY' OR familyExpenseId IS NOT NULL`), `unlinkFamily(ids)`, `unlinkAllFamily()`, `familyIdsOfSmsBefore(before)`, `observeShareableFamilyCount(maxMinor, from, to)` (the 7.1 eligibility in SQL, `familyExpenseId IS NULL`, `source != 'GROUP'`); `findUnlinkedPayments` gains `AND familyExpenseId IS NULL`.
`GroupDao`: `familyGroup()` / `observeFamilyGroup()` (`WHERE kind = 'FAMILY' LIMIT 1`), `observeFamilyExpenses(groupId, from: LocalDate, to: LocalDate)` (not deleted, `occurredOn` in range, newest first; uses index `(groupId, occurredOn)`), `observeMembersOf(groupId)`; `observeActiveExpenses()` excludes family groups (`AND groupId NOT IN (SELECT id FROM grp_groups WHERE kind = 'FAMILY')`) so the groups snapshot never loads the family history.
`CategoryRuleDao`: unchanged API (`upsert` writes the new column).

### 6.3 API and mappers
- `groupKindOf("family") = FAMILY`, `GroupKind.FAMILY.apiName = "family"` (unknown names still read as GROUP).
- `InvitePreviewDto` gains `kind: String = "group"`, `alreadyInFamily: Boolean = false`; `JoinPreview` gains `kind: GroupKind`, `alreadyInFamily: Boolean`.
- `GroupBodies.createFamily(id, name, myMemberId, placeholderId)` and `GroupBodies.placeholder(id)` with the fixed name `FamilyRepository.PLACEHOLDER_NAME = "Partner"` (not a string resource: never shown).
- `GroupsApi`: no new route functions (`createGroup`, `addMember`, `removeMember`, `leaveGroup`, `putLink`, `invite`, `acceptInvite`, `send` cover everything).

---

## 7. Sharing rules on the phone: `FamilyLinker` (new, `groups/data/FamilyLinker.kt`)

Constructor: `ExpenseDao`, `GroupDao`, `Outbox`, `SyncScheduler`, `Clock`, India `ZoneId`, `categoryName`, `newId`. Every function runs inside the caller's Room transaction.

### 7.1 Eligibility (`shareable(row)`)
`row.scope == FAMILY && row.status == ADDED && row.direction == DEBIT && row.currency == INR && row.groupExpenseId == null && row.amountMinor in 1..1_000_000_000 && India date of occurredAt in 2000-01-01..today+1 && (row.source != GROUP || row.familyExpenseId != null)`.

### 7.2 Fields sent
`description = GroupText.descriptionFor(row.counterparty, categoryName(row.category))`, `category`, `amountPaise = amountMinor`, `occurredOn = occurredAt` in India time, `paidByMemberId = family.myMemberId`, `splitMethod = EQUAL`, `auto = false`, `shares = [{ myMemberId, amountMinor, null }]`, `baseVersion` = stored version on updates (null for creates). Outbox entries: `groupId = family id`, `EntityKind.EXPENSE`, ops CREATE/UPDATE (`PUT`) and DELETE, `restoreJson = RestoreSnapshot(expense = server copy or null)` and **no** `personal` snapshot. Coalescing (5.1 step 4) applies as for splits: Family then Just me before anything was sent sends nothing.

### 7.3 Entry points
- `onChanged(ids)`: called by `ExpenseRepository` after every write that can change eligibility or fields (`add`, `addWaiting`, `ignoreWaiting`, `backToWaiting`, `ignore`, `undoAdd`, `setCategory`, `update`, `addManual`), inside the same transaction. Family missing or not READY → nothing. A create waits until the family's data is complete (pulled up to the newest version, last pull not failed; `reconcile` then shares the row), so a payment whose own family expense is not pulled yet is never shared twice (review fix 2026-11-20); take-outs and updates of linked rows go once the first pull ended. Per row: eligible & unlinked → create (new v4 id, local `grp_expenses` row PENDING + share, set `familyExpenseId`); not eligible & linked → delete (local tombstone PENDING, outbox DELETE, `familyExpenseId = null`); eligible & linked & local copy present & fields differ → update. Then `scheduler.requestSync()`.
- `onDeleting(rows)`: called before `ExpenseRepository.delete` and `deleteSmsBefore` remove rows: queue DELETE for each linked family expense.
- `reconcile(): Boolean`: eligibility only (create unlinked eligible rows; delete linked ineligible rows whose local copy exists; unlink rows whose copy is missing only when the family is READY). Never pushes field differences (avoids two phones overwriting each other). Called by `SyncEngine` before the push and after the pull (and by `FamilyRepository.create`); when it queued something after the pull it calls `requestSync(expedited = true)`.
- `applyStored(groupExpenseId)`: called by `MirrorWriter.sync` for FAMILY groups instead of the split logic (also for tombstones and for ids no longer stored). Payer not me → nothing (no personal row ever). Live and linked: a MANUAL or GROUP-source row follows description, category, amount and date; an SMS row follows the category only (the bank's amount, payee and time stay). Live, mine, unlinked (new phone, reinstall): link the one unlinked SMS/MANUAL rupee debit of that amount within a day (`findUnlinkedPayments`), set `familyExpenseId`, `scope = FAMILY`, `status = ADDED`; with none or several, insert a mirror `Expense(source = GROUP, scope = FAMILY, status = ADDED, myShareMinor = amountMinor, familyExpenseId = id, counterparty = description, occurredAt = date 00:00)`. Tombstone or gone while linked: `familyExpenseId = null`, `scope = ME` (taken out on another phone of the same person).
- `onRejected(entry, error)`: called by `SyncEngine.reject` for family expense entries instead of rollback + `keepRejected` (no "Not saved" row). Create refused: delete the local copy; linked row `familyExpenseId = null`, `scope = ME` (so it is not sent again). Update refused: restore the snapshot's server copy, queue a DELETE of it, unlink, `scope = ME`. Delete refused (`not_found`): drop. `not_member` / `group_deleted` keep today's handling (the family leaves the phone).
- `unlinkFamily(groupId)` (the family left this phone: leave, removal, deletion, `lost`): `familyExpenseId = null` on every row linked to that group's expenses; scope stays FAMILY (local tag, as before the family). `unlinkAll()`: a different account signed in.
- Sign-out (`LocalGroupData.clearAll`) does **not** unlink: links stay so the same account signing in again finds them.

### 7.4 Wiring
- `ExpenseRepository` gets `personalChanges: () -> PersonalChangeListener` (new data-layer interface with `onChanged(ids)` and `onDeleting(rows)`; default no-op) set in `AppContainer` to the `FamilyLinker`; each listed write wraps itself and the call in `transactions.run`.
- `GroupsRepository.splitPayment` calls `familyLinker.onChanged(listOf(expenseId))` in its transaction (a shared Family payment that gets split leaves the family month).
- `MirrorWriter.sync`: look up the stored expense's group; `FAMILY` → `family.applyStored(id)`; return.
- `LocalGroupData.removeGroup`: for a FAMILY group call `unlinkFamily(groupId)` before deleting; `switchAccount` calls `unlinkAll()` (GROUP-source family mirrors already go with `deleteGroupMirrors`).
- `SyncEngine.reject`: family expense entries → `onRejected`; `keepRejected` skips FAMILY groups.
- `SyncEngine.sync`: `reconcile()` before `pushLocked()`, and after `pullLocked()`.

---

## 8. App: repository, ViewModels, navigation

### 8.1 `FamilyRepository` (new, `groups/data/FamilyRepository.kt`)
```kotlin
fun observeFamily(): Flow<FamilyInfo?>      // null: no family on this phone
fun observeMonth(month: YearMonth): Flow<FamilyMonth?>
fun observeShareableEarlier(): Flow<Int>     // rows 7.1 would share, not linked yet
suspend fun create(name: String): FamilyResult<String>   // online; group id
suspend fun inviteLink(): FamilyResult<String>           // online; re-adds the placeholder if none, then GroupsRepository.shareLink
suspend fun rename(name: String)                         // GroupsRepository.renameGroup (queued)
suspend fun removePartner(memberId: String): ActionResult // GroupsRepository.removeMember
suspend fun cancelInvite(memberId: String): ActionResult  // same, on the placeholder
suspend fun leave(): ActionResult                         // api.leaveGroup; on OK: linker.unlinkFamily + local.removeGroup
```
- `FamilyInfo(groupId, name, myMemberId, isOwner, me: FamilyMember, partner: FamilyMember?, placeholderId: String?, load: LoadState)`; `FamilyMember(memberId, name, shortName, avatar)`.
- `FamilyMonth(month, familyName, totalMinor, count, people: List<FamilyPersonTotal>, topCategories: List<CategoryTotal> (≤2), latest: List<FamilyRow> (≤5), lastMonthTotalMinor, load)`; `FamilyRow(groupExpenseId, title, category, amountMinor, paidByMe, paidByName, occurredOn, personalExpenseId: Long?)`. Totals use full amounts of non-deleted expenses with `occurredOn` in the month (India dates); people are active members on the app (me first), with ₹0 rows.
- `FamilyResult`: `Ok(value)`, `Offline`, `SignedOut`, `AlreadyInFamily`, `Failed(code)`.
- `create`: `api.createGroup(GroupBodies.createFamily(...), key)`; on 201 store `GroupEntity(kind = FAMILY, version = latestVersion = group.version, syncState = SYNCED, ownerMemberId)` and the members, then `linker.reconcile()` and `requestSync()`; `409 already_in_family` → `requestSync()` + `AlreadyInFamily`.
- `GroupsRepository.shareLink` gains `renewAfter: Duration` and `lifetime: Duration` parameters; the family passes 3 days / 7 days (groups keep 60 / 90).
- `GroupsRepository.renameGroup` accepts FAMILY.

### 8.2 Existing code that must ignore families
- `GroupsRepository`: `summariesOf` (already GROUP only), `storedOf` (filter out FAMILY, or Groups (empty) never shows for someone with only a family), `friendsOf` and `totalsOf` (skip FAMILY), `observeSplitLabels` (skip FAMILY), `observeKnownPeople` (skip members of FAMILY groups that are not on the app), the snapshot's `balances` (skip FAMILY), `observeGroup` unchanged.
- `avatarColorsOf`: leave out FAMILY members not on the app (the placeholder never takes a colour); the family is already not `named`.
- `CreditMatching` / `SettlementMatcher`: skip FAMILY groups.
- `SyncEngine.displayName` and `GroupNotifications`: a FAMILY group's name is `group.name`; the joined notification of a FAMILY group opens `AppLink.Family`.
- `GroupPageNav` (and `GroupSettings`): a FAMILY id redirects to Home, Family side.
- `SettleUp`, `MarkAsPaid`, `RemindOnWhatsApp`, `ChoosePeople`: never reached for a family (lists above); no change beyond the filters.

### 8.3 Navigation
- `Routes.kt`: `@Serializable data object SetUpFamily`, `@Serializable data class FamilySettings(val sendLink: Boolean = false)`, `@Serializable data class FamilyMonthView(val yearMonth: String)` (cut candidate), `SignIn(..., forFamily: Boolean = false)`.
- `AppLinks.kt`: `@Serializable data object Family : AppLink` → `route() = Home`; whoever follows links sets `AppContainer.homeView.value = HomeView.Family` first.
- `JoinNav`: effect `OpenFamily` → navigate `Home` (`popUpTo<Home>`) with `homeView = Family`.
- New `ui/screens/family/FamilyNav.kt` registering the three destinations (SetUpFamily and FamilySettings inside `SignInGate(signIn = SignIn(forFamily = true))`).

### 8.4 ViewModels and screens (`ui/screens/family/`)
- `SetUpFamilyViewModel(family, online, savedState)`: state `SetUpFamilyUiState(name, nameError, earlierCount, busy, problem: ActionProblem?)`; events `NameChanged`, `Invite`, `Back`; effects `Share(text)`, `Done`, `OpenFamily`. Keeps the created family id across a failed link step.
- `FamilySettingsViewModel(family, online, sendLink)`: state `FamilySettingsUiState(name, nameError, me, partner: Partner (Joined(name, canRemove) | Invited | None), busy, problem, confirm: LEAVE | REMOVE(name) | null, inviteSheet)`; events for every control; effects `Share(text)`, `Left`, `Gone`.
- `JoinGroupViewModel`: `Ready` carries `preview.kind`; new `ReadyFamily(preview, earlierCount, joining, problem)` and `AlreadyInFamily`; accept `409 already_in_family` → `AlreadyInFamily`; success on a family → `OpenFamily`. `JoinGroupScreen` draws `JoinFamilyScreen` for the family states.
- `FamilyMonthViewModel(month, family)` + `FamilyMonthScreen` (cut candidate): Month view (Family).
- `FamilyInvite.message(res, myFirstName, familyName, link)` builds 1.3; shares with `Intent.createChooser`.
- `HomeViewModel`: takes `FamilyRepository` and the app-scoped `homeView`; Family side with a family → `HomeContent.FamilyMonth(load, people, topCategories, latest, empty)`; `HomeUiState` gains `familyCard: FamilyCard?` (`SET_UP`, `WAITING`, `INVITE`) and a caption model (`FamilyLocal`, `FamilyAlone(count)`, `FamilyTogether(me, partner, count)`); label "Family spent in %1$s" when a family exists. New events `SetUpFamily`, `SendFamilyLink`, `FamilyRowTapped(expenseId)`; `SpentClicked` opens `FamilyMonthView` on the Family side with a family.
- `YouViewModel` / `YouUiState`: `family: YouFamily` (`None`, `Waiting(name)`, `Alone(name)`, `Together(name, me, partner)`); `YouEvent.OpenFamily`.
- `SignInScreen`: family copy when `forFamily`.
- `AddToExpensesViewModel` / screen: the rule remembers the chosen scope (8.5); subtitle variant.
- `ExpenseDetailScreen`: family delete body when the row has `familyExpenseId`.

### 8.5 "Do this every time" for Family
`CategoryRule(merchantKey, category, scope)`. `ExpenseRepository.rememberCategory(counterparty, category, scope)` writes the scope chosen on Add to expenses when the switch is on; `guessScope(counterparty)` returns the rule's scope or ME. `SmsIngestPipeline` sets `scope = expenses.guessScope(payment.counterparty)` on new detected rows next to `guessCategory`, so Add (Home card, Add to expenses, a swipe, Add all) adds it as Family and 7.3 shares it. A payment is still never added without a tap.

### 8.6 Strings (exact copy)
`strings_family.xml` (new): `family_title` "Family"; `family_setup_headline` "One month for the two of you"; `family_setup_body` "Pool what you both spend into one monthly total. Nobody owes anybody."; `family_name_label` "Family name"; `family_name_default` "Home"; `family_name_invalid` "That does not look right."; `family_fact_invite_title` "Invite your partner"; `family_fact_invite_body` "They join with a link and need the app."; `family_fact_counts_title` "Counts in both"; `family_fact_counts_body` "What you pay for the family also shows in your own month."; `family_fact_private_title` "Personal stays private"; `family_fact_private_body` "Only expenses marked Family are shared."; `family_fact_sms_title` "SMS stays on each phone"; `family_fact_sms_body` "Only the expense entry is shared."; `family_fact_earlier_title` "Earlier Family expenses"; plurals `family_fact_earlier_body` one "Your %d Family expense on this phone is shared too." other "Your %d Family expenses on this phone are shared too."; `family_invite_button` "Invite partner"; `family_invite_line1` "%1$s invited you to %2$s on %3$s: one monthly total for the two of you."; `family_invite_line2` "Get the app, then open this link on your phone to join:"; `family_invite_line3` "Only expenses you mark Family are shared. Never your SMS."; `family_join_headline` "%1$s invited you to %2$s"; `family_join_body` "One monthly total for the two of you. Nobody owes anybody."; `family_join_private_body` "Only expenses you mark Family are shared."; `family_join_button` "Join Family"; `family_join_not_now` "Not now"; `family_already_headline` "You\'re already in a family"; `family_already_body` "You can be in one family at a time. Leave your family on the You tab first, then open this link again."; `family_already_ok` "OK"; `family_people` "People"; `family_you` "You"; `family_partner` "Partner"; `family_invited_title` "Your partner"; `family_invited_subtitle` "Invite sent · not joined yet"; `family_invite_row_title` "Invite partner"; `family_invite_row_subtitle` "They join with a link and need the app."; `family_leaving` "Leaving"; `family_leave_title` "Leave Family"; `family_leave_subtitle` "Your Family expenses leave %1$s and stay on this phone."; `family_leave_confirm_title` "Leave %1$s?"; `family_leave_confirm_body` "Your Family expenses leave %1$s and stay on this phone as yours. You can join again with a new link."; `family_leave_confirm` "Leave Family"; `family_leave_stay` "Stay"; `family_remove_confirm_title` "Remove %1$s from %2$s?"; `family_remove_confirm_body` "%1$s\'s Family expenses leave %2$s. They stay on %1$s\'s phone."; `family_remove_confirm` "Remove"; `family_remove_keep` "Keep"; `family_sheet_link_title` "Link is on"; `family_sheet_link_body` "Anyone with it can join %1$s as your partner."; `family_sheet_note` "Send it again if it got lost. Cancel invite turns the link off."; `family_sheet_send` "Send link again"; `family_sheet_cancel_invite` "Cancel invite"; `family_sheet_close` "Close"; `family_month_title` "%1$s · %2$s"; `family_month_spent` "Family spent"; `family_month_last` "Last month".
`strings_home.xml`: `home_family_spent_label` "Family spent in %1$s"; `home_family_caption_two` "%1$s and %2$s · %3$s"; `home_family_caption_alone` "Just you · %1$s"; plurals `home_family_expenses` one "%d expense" other "%d expenses"; `home_family_by_person` "By person"; `home_family_top_categories` "Top categories"; `home_family_you` "%1$s (you)"; `home_family_row_paid` "%1$s · %2$s paid"; `home_family_row_you_paid` "%1$s · You paid"; `home_family_row_meta` "Family"; `home_family_empty_title` "Nothing in the family month yet"; `home_family_empty_body` "When you add an expense, choose Family under Count it in."; `home_family_card_tag` "Family"; `home_family_setup_title` "Share this month with your partner"; `home_family_setup_body` "Set up Family to see one total for the two of you. Only expenses marked Family are shared."; `home_family_setup_button` "Set up Family"; `home_family_waiting_tag` "Invite sent"; `home_family_waiting_title` "Waiting for your partner"; `home_family_waiting_body` "They join with the link you sent. Send it again if it got lost."; `home_family_waiting_button` "Send link again"; `home_family_alone_title` "Invite your partner"; `home_family_alone_body` "%1$s has just you now. Send a link to share it again."; `home_family_alone_button` "Invite partner"; `home_offline_body_family` "Your own expenses still work. The family month syncs when you are back online."
`strings_you.xml`: `you_section_family` "Family"; `you_family_title` "Family"; `you_family_none` "Share a monthly total with your partner"; `you_family_two` "%1$s · %2$s and %3$s"; `you_family_waiting` "%1$s · waiting for your partner"; `you_family_alone` "%1$s · just you".
`strings_signin.xml`: `signin_family_headline` "Share a family month"; `signin_family_reason` "Sign in to share a family month. Your SMS stay on this phone."; `signin_family_kept_title` "Family month"; `signin_family_kept_body` "Kept in your account, so a new phone gets it back."; `signin_family_private_title` "Personal stays private"; `signin_family_private_body` "Only expenses you mark Family are shared. Never your SMS."
`strings_payment.xml`: `payment_add_always_subtitle_family` "%1$s is always %2$s · Family"; `payment_detail_delete_family_body` "It will not count in %1$s or in the family month any more. The bank message stays on your phone."
Notifications: reuse `notify_joined` "%1$s joined %2$s".

---

## 9. App tests (JVM / Robolectric)
- `DatabaseMigrationTest`: 5 → 6.
- `FamilyLinkerTest` (new): mark Family → one CREATE whose body has only the 7.2 fields (no note, account, card, paid with, SMS text, time); Just me → DELETE (and nothing sent when coalesced); category edit → UPDATE with `baseVersion`; ignore, undo add, back to waiting, delete, `deleteSmsBefore` → DELETE; split a Family row → DELETE, undo the split → CREATE; credit, other currency, DETECTED, over ₹1 crore, a future date → never sent; no family or LOADING → nothing; `reconcile` shares earlier rows once and is idempotent; `applyStored`: partner's expense makes no personal row, a restored own expense links the single match (scope FAMILY, ADDED) or makes a GROUP-source mirror, an SMS row keeps the bank amount and takes the category, a tombstone unlinks with scope ME; `onRejected` (create, update) leaves the row Just me and unlinked, no `grp_rejected` row.
- `FamilyRepositoryTest` (new, MockWebServer): create body shape and stored group (version 1, READY), `409 already_in_family`, offline; `inviteLink` re-adds the placeholder when missing and uses a 7-day link renewed after 3 days; `leave` unlinks (scope stays FAMILY) and removes the group; `observeMonth` totals, people with ₹0, top 2 categories with ties, latest 5, India month bounds, PENDING rows counted.
- `SyncEngineTest`: kind `family` stored as FAMILY; reconcile before push and after pull; family `lost` → rows unlinked; switch account → unlinked; sign-out keeps links and the same account signing in finds them; a partner's family expense makes no notification; a joined change makes "Meera joined Home" opening `AppLink.Family`.
- `GroupsRepositoryTest`: a family never shows in Groups, Friends, totals, split labels, known people (placeholder), `storedOf`; `AvatarColorsTest`: a family changes no other colour; the placeholder takes none.
- `ExpenseRepositoryTest`: each write path calls the listener in its transaction; `rememberCategory` with scope; `guessScope`.
- `SmsIngestPipelineTest`: a merchant rule with Family makes the new detected payment FAMILY.
- `HomeViewModelTest`: Family side with no family (SET_UP card, local total), waiting, alone, together, empty month, loading, offline body; `SpentClicked` target.
- `SetUpFamilyViewModelTest`, `FamilySettingsViewModelTest` (rename queued; remove only for the owner; cancel invite; leave; offline banners; `sendLink = true` starts at once), `JoinGroupViewModelTest` (family preview, `alreadyInFamily`, accept 409, success → `OpenFamily`), `YouViewModelTest` / `YouScreenTest` (row subtitles), `SignInScreenJoinTest` (family copy), `AppLinksTest` (`AppLink.Family`).
- Robolectric screen tests for Set up Family, Join Family, Family settings, Home family sections: both themes, text at least 14sp at font scale 1.0, every control at least 48dp, Tags carry text (state not by colour alone).

---

## 10. Docs and design to change in the same work
- `docs/server.md`: 1 (06 Family row), 2 (decision: family = kind `family`), 3 (0003 SQL, table row, notes: rebuild, one family/2 members by guard), 3.1 (family rules: create, accept, add, leave, remove), 4.1 (`already_in_family`), 4.2 (3.15 table), 4.5 (`/me/groups` kind, `DELETE /v1/me` step 1b), 4.6–4.12 (sections 3.1–3.12 here, new 4.17 "Family"), 4.15 (caps: one family, 2 members, 7-day family links), 5.1 (online-only: create family, add placeholder, cancel invite, remove, leave; queued: rename, family expenses), 5.2 (reconcile), 6 (family nets are always 0; never computed), 7 (family invite page), 9.3 (Room 6), new 9.9 "Family on the phone" (section 7 here), 9.7 (joined only), 10 (privacy line for family expenses), 12 (Family no longer out of scope; Splitwise import, when built, makes kind `group` only), 13 (new open points: joint accounts, removed person not told), build notes.
- `docs/product.md`: a "Family (decided …)" section with the decisions.
- `docs/architecture.md`: `Expense.familyExpenseId`, `CategoryRule.scope`, month rule note, new routes, Home Family side.
- `docs/groups-build-plan.md` line 95 (Family no longer left out).
- `design/spec/flow.md` 03B (entries, invite wording "By a link they open on their phone", waiting, settings, leaving, earlier expenses, already in a family, signed out) and `design/spec/README.md` ("App states added" list); Figma Dark and Light boards, Flow page, and the `design/spec` export (JSON and both PNGs) for every item in `design_additions`.

## 11. Build order
1. Server: 0003 + routes + tests (`npm test`), web page + tests. 2. App data: Room 6, mappers, DAOs, FamilyLinker, FamilyRepository, filters, tests. 3. Screens and navigation, strings, screen tests. 4. Design export and docs. 5. Owner: Time Travel bookmark, 0003 on a scratch D1, then `--remote`, then `npm run deploy`; ship the app build together (an older build reads `family` as a named group).

## 12. Cut candidates
Month view (Family) and its route; the "Earlier Family expenses" note (sharing would still happen); the 7-day family link lifetime (90 days works); the family Sign in copy (use 01's). Not cuttable: the Home entry card, You row, Family settings with Leave, Join Family states, the server family rules.

## Decisions taken (2026-11-18)
The lead accepted every recommendation below. Lead note: Room version 6, because Splitwise import takes 5 and is built first. Server migration 0003 is the family one.
1. **How is a family stored on the server?** New kind 'family' with the table rebuild in 0003. Every existing check that tests kind === 'group' or kind !== 'group' then refuses a family until it is opened on purpose. A flag would quietly treat a family as a splitting group anywhere a check was missed: balances, settlements, Groups list, link page. The rebuild is cheap now while the live database is tiny. The fallback is the flag column if the D1 rebuild fails on the scratch database.
2. **What does a family expense carry to the server?** Payee, category, amount, India date, who paid; never the note. Matches what a split already sends (4.7). The note is the person's own and product says notes never leave the phone. The partner needs the payee to recognise the expense.
3. **What happens to expenses tagged Family before a family existed?** Share them all on set up or join, with 'Your 12 Family expenses on this phone are shared too.' on both screens. The person already chose Family for them. Showing the count before Invite partner or Join Family is the consent. It keeps one rule (Family means shared once a family exists) with no extra switch for an ordinary user.
4. **How many people can be in a family?** 2 in version 1, enforced by the server (placeholder included). Every designed screen says 'your partner' and 'the two of you'. More members need new copy and a new invite flow. The constant can be raised later.
5. **Can a person be in more than one family?** One. Home has one Me / Family toggle and one family month. The server enforces it with a guard, so two phones cannot race past it.
6. **When someone leaves, is removed or deletes their account, what happens to their shared entries?** They leave the family month. The entries were that person's own spending, shared by choice. Leaving should stop the sharing, and a wrong person joined by a forwarded link must be removable with their data. It also matches 'un-marking deletes it from the family'. Cost: written rows in proportion to their entries.
7. **Who may edit or delete a family expense?** Only the payer. Each entry mirrors one person's own payment on their own phone. It avoids conflicts between two people and fits 'each phone reads only its own SMS'.
8. **Who can remove whom?** The owner removes; anyone cancels an invite; anyone leaves. This reuses the existing owner rule. The main case is a wrong join, and the person who sent the link (the owner) sees 'Meera joined Home'. Undo-join is not offered for families, because Remove covers it.
9. **Should Family have a Delete family action?** No Delete; leaving is enough. This is one fewer screen and rule. Both members can leave, and the server tombstones an empty family.
10. **Notifications for family activity?** Only the joined notification; family expense pushes are NORMAL priority and only trigger a background sync. About 60 expenses a month would be noise. Android lowers apps whose HIGH pushes make no notification. The joined notification is how a wrong join is noticed.
11. **How is the partner invited?** Link via the share sheet; flow.md's 'or phone number' is dropped. The server never looks people up by phone (privacy, unverified numbers). The share sheet lets the person pick WhatsApp or any app. The link reuses App Links, Join group and LINK_HOST.
12. **How long does a family invite link work?** 7 days. Whoever holds the link can join and see every family entry. The partner normally joins the same day. A short life limits a forwarded or leaked link.
13. **Is there a full list of the family month (all 64 expenses)?** Build Month view (Family); it is the first thing to cut if time is short. Otherwise only 5 entries of the shared month are visible anywhere. It reuses Month view's parts.
14. **Where does the Activity tab's Family filter look?** Unchanged. Activity is the phone's own payments, and every row opens Expense detail. The partner's entries live on Home's family month and Month view (Family).
15. **Can the partner's rows be opened?** Not tappable. The row already shows the payee, category, who paid and the amount. A detail screen would be a new screen with nothing to change on it.
16. **How does 'Do this every time' work for Family?** Remember and pre-select; never add silently. Product rule: a detected payment is never added silently. Pre-selecting makes rent, groceries and bills one tap.
17. **What happens to Family tags after leaving the family?** Keep the local tag. It is the person's own labelling and is what 51 shows without a family. Joining a new family later shares them again, with the count shown first.
18. **Can a payment be both a split and a Family expense?** No. It keeps one link per payment and today's Expense detail rule (a split shows its group, not Just me or Family).
19. **Sign in copy for a family invite link opened signed out?** Reuse 49; family Sign in copy only for Set up Family and the You row. This avoids an extra unauthenticated lookup. The partner sees Join Family right after signing in.
20. **Is creating a family queued offline?** Online only for membership actions. Invite partner needs the network anyway to make the link. Leave and remove follow the existing online-only rule. Marking Family works offline through the outbox.

## Design additions (Figma Dark and Light boards, Flow page, flow.md, design/spec export)
### Home (Family) with Set up card (02-everyday/51 changed in place)
- Based on: 02-everyday/51-home-family, with the 'Auto-tracking tip' card frame from 02-everyday/02-home-manual-mode
- Content: Insert under the Balance Card a card on color/bg/surface-raised (tip card frame): Tag Tone=Brand 'Family'; Heading/M 'Share this month with your partner'; Body/M color/text/secondary 'Set up Family to see one total for the two of you. Only expenses marked Family are shared.'; one Button Style=Primary Size=Medium 'Set up Family'. Everything else on 51 unchanged.
- flow.md: 03B new branch 'Where to start': `SCREEN` `NEW` **Home (Family), no family** - The Family side shows Set up Family under the card.

### Home (family month, waiting for partner)
- Based on: 06-family/02-home-family-month plus the card frame of the Home (Family) Set up card
- Content: Balance Card Tone=Positive Label 'FAMILY SPENT IN NOVEMBER', Amount '₹6,840', Caption 'Just you · 12 expenses'. Card: Tag Tone=Yellow 'Invite sent'; Heading/M 'Waiting for your partner'; Body/M 'They join with the link you sent. Send it again if it got lost.'; Button Primary Medium 'Send link again'. By person: one Balance Row Direction=Plain 'Arjun (you)' / '12 expenses' / '₹6,840' (Avatar Purple). Top categories: Stat Tiles 'Groceries ₹3,120', 'Food ₹1,860'. Latest: Expense Row Kind=Shared 'BigBasket' / 'Groceries · You paid' / '₹2,340' / Meta 'Family'.
- flow.md: 03B new branch 'While waiting': `SCREEN` `NEW` **Waiting for your partner** - Home's Family side and Family settings say the invite is sent. Send link again.

### Home (family month, just you)
- Based on: Home (family month, waiting for partner)
- Content: Same as waiting, but the card has Tag Tone=Brand 'Family'; Heading/M 'Invite your partner'; Body/M 'Home has just you now. Send a link to share it again.'; Button Primary Medium 'Invite partner'. Caption 'Just you · 12 expenses'.
- flow.md: 03B branch 'If they leave or are removed': `OUTCOME` `NEW` **Just you** - Their Family expenses leave the family month. Invite partner again.

### Home (family month) (06-family/02 changed in place)
- Based on: 06-family/02-home-family-month
- Content: Latest gets a second row for the person's own expense: Expense Row Kind=Shared Title 'Swiggy', Subtitle 'Food · You paid', Amount '₹450', Meta 'Family', Icon/food 3D. Meera's Avatar follows the avatar colour rule (the first other person is Green when no other group exists).

### Home (family month, nothing yet)
- Based on: 06-family/02-home-family-month; empty card from 02-everyday/21-groups-empty
- Content: Balance Card 'FAMILY SPENT IN NOVEMBER' / '₹0' / 'Arjun and Meera · 0 expenses'. By person rows 'Arjun (you)' '0 expenses' '₹0', 'Meera' '0 expenses' '₹0'. Top categories hidden. Latest label then an empty card (color/bg/surface): Icon/users 3D, Heading/L 'Nothing in the family month yet', Body/M 'When you add an expense, choose Family under Count it in.' No button.
- flow.md: 03B branch 'Family month': `SCREEN` `NEW` **Nothing yet** - A new month, or a new family, starts at ₹0.

### Home (family month, loading)
- Based on: 06-family/02-home-family-month with the placeholder shapes of 02-everyday/97-group-page-loading
- Content: Greeting and Me/Family toggle (Family selected) as 06/02; Balance Card, By person rows, Top categories tiles and Latest rows replaced by the loading placeholder shapes of 97. No text besides 'By person', 'Top categories', 'Latest' labels.
- flow.md: 03B branch 'On the partner's phone': step after Join Family: `SCREEN` `NEW` **Loading the family month** - First time only.

### Home (family month, offline)
- Based on: 06-family/02-home-family-month with the Offline banner of 02-everyday/03-home-offline
- Content: Offline banner (color/accent/yellow-soft, Icon/wifi-off) above the Balance Card: 'You're offline' / 'Your own expenses still work. The family month syncs when you are back online.' Rest as 06/02.

### Sign in (family)
- Based on: 01-onboarding/01-sign-in (layout of 02-everyday/49-join-group-sign-in-first)
- Content: Top Bar 'Sign in'; Display/L 'Share a family month'; Body/M 'Sign in to share a family month. Your SMS stay on this phone.'; Info Row Icon/users 3D 'Family month' / 'Kept in your account, so a new phone gets it back.'; Info Row Icon/lock 3D 'Personal stays private' / 'Only expenses you mark Family are shared. Never your SMS.'; Footer: Continue with Google button, Ghost Medium 'Not now'.
- flow.md: 03B branch 'Signed out': `SCREEN` `NEW` **Sign in first** - From Set up Family or You. Not now goes back.

### Set up Family (earlier expenses)
- Based on: 06-family/00-set-up-family
- Content: Facts gain a fifth Info Row at the end: Icon/calendar 3D, Title 'Earlier Family expenses', Subtitle 'Your 12 Family expenses on this phone are shared too.' Shown only when the phone has such expenses.
- flow.md: 03B branch 'Earlier Family expenses': `AUTOMATIC` `NEW` **Shared when you set up or join** - Set up Family and Join Family say how many first.

### Set up Family (name blank)
- Based on: 06-family/00-set-up-family
- Content: Input 'Family name' State=Error, empty value, helper 'That does not look right.'

### Set up Family (offline)
- Based on: 06-family/00-set-up-family with the Offline banner frame used on 02-everyday/101
- Content: Offline banner at the top of the body: Icon/wifi-off 'You're offline' / 'Check your connection and try again. Nothing is lost.' Rest unchanged.

### Set up Family (could not do that)
- Based on: 06-family/00-set-up-family with the Problem banner frame used on 02-everyday/104
- Content: Problem banner at the top of the body: Icon/alert 'Could not do that now' / 'Try again in a moment.' Rest unchanged.

### Join Family (earlier expenses)
- Based on: 06-family/01-join-family
- Content: The List card gains a third Info Row: Icon/calendar 3D 'Earlier Family expenses' / 'Your 12 Family expenses on this phone are shared too.'

### Join Family (offline)
- Based on: 06-family/01-join-family with the banner of 02-everyday/101-join-group-offline
- Content: Info Row banner Icon/wifi-off 'You're offline' / 'Check your connection and try again. Nothing is lost.' above the sticker; rest as 01.

### Join Family (could not do that)
- Based on: 06-family/01-join-family with the banner of 02-everyday/104-join-group-could-not-do-that
- Content: Banner Icon/alert 'Could not do that now' / 'Try again in a moment.'; rest as 01.

### Join Family (already in a family)
- Based on: 02-everyday/50-join-group-link-no-longer-works
- Content: Sticker tile color/accent/pink-soft with Icon/users 3D; Display/L 'You're already in a family'; Body/M 'You can be in one family at a time. Leave your family on the You tab first, then open this link again.'; Footer Button Primary Large 'OK'.
- flow.md: 03B branch 'On the partner's phone': `SCREEN` `NEW` **Already in a family** - One family at a time.

### Family settings
- Based on: 02-everyday/25-group-settings
- Content: Top Bar 'Family'. Input 'Family name' value 'Home'. Label 'People': Setting Row Icon/user 3D 'Arjun Mehta' / 'You' (no Tag); Setting Row Icon/user 3D 'Meera Iyer' / 'Partner'. Label 'Leaving': List card with Info Row Icon/logout 3D 'Leave Family' / 'Your Family expenses leave Home and stay on this phone.' No Add someone row, no Delete row.
- flow.md: 03B new branch 'Family settings': `SCREEN` `NEW` **Family settings** - From You. Rename, the partner, leave.

### Family settings (invite sent)
- Based on: Family settings
- Content: The partner row is Setting Row Icon/user-plus 3D 'Your partner' / 'Invite sent · not joined yet'.

### Family settings (no partner)
- Based on: Family settings
- Content: The partner row is Setting Row Icon/plus 3D 'Invite partner' / 'They join with a link and need the app.'

### Family settings (invite sheet)
- Based on: 02-everyday/46-group-settings-friend-s-link (sheet)
- Content: Sheet over Family settings (invite sent): Heading/L 'Your partner'; Info Row Icon/link 3D 'Link is on' / 'Anyone with it can join Home as your partner.'; Body/M note 'Send it again if it got lost. Cancel invite turns the link off.'; Button Primary Large 'Send link again'; Button Secondary Large 'Cancel invite'; Button Ghost Medium 'Close'.

### Family settings (leave)
- Based on: 02-everyday/92-group-settings-leave
- Content: Confirm dialog: Icon/logout 3D; Title 'Leave Home?'; Body 'Your Family expenses leave Home and stay on this phone as yours. You can join again with a new link.'; Choices 'Leave Family', 'Stay'.
- flow.md: 03B branch 'If they leave or are removed': `SCREEN` `NEW` **Leave Family** - Their Family expenses leave the family month and stay on their phone.

### Family settings (remove partner)
- Based on: 02-everyday/92-group-settings-leave
- Content: Confirm dialog: Icon/user 3D; Title 'Remove Meera from Home?'; Body 'Meera's Family expenses leave Home. They stay on Meera's phone.'; Choices 'Remove', 'Keep'. Only the owner sees it.

### Family settings (offline)
- Based on: Family settings with the banner of 02-everyday/100-group-settings-offline
- Content: Offline banner at the top of the body: 'You're offline' / 'Check your connection and try again. Nothing is lost.'

### Family settings (could not do that)
- Based on: Family settings with the banner of 02-everyday/103-group-settings-could-not-do-that
- Content: Problem banner: 'Could not do that now' / 'Try again in a moment.'

### You (family row)
- Based on: 04-you/00-you
- Content: After the Privacy list card: Label 'Family', then a List card with Info Row Icon/users 3D 'Family' / 'Home · Arjun and Meera'. Variants for the subtitle: 'Share a monthly total with your partner' (no family), 'Home · waiting for your partner', 'Home · just you'. Draw the no-family variant on 04-you/06 (not signed in) too.
- flow.md: 03B branch 'Where to start': `SCREEN` `NEW` **You: Family** - Opens Set up Family, or Family settings.

### Add to expenses (Family every time)
- Based on: 02-everyday/05-add-to-expenses
- Content: Count it in: 'Family' chip selected, 'Just me' not. Setting Row 'Do this every time' with Switch On=True and subtitle 'Swiggy is always Food · Family'.
- flow.md: 03B branch 'If it repeats': card text unchanged; add `AUTOMATIC` `NEW` **Family chosen for you** - The next payment to that merchant waits with Family selected; Add still needs a tap.

### Expense detail (delete, Family)
- Based on: 02-everyday/60-expense-detail-delete
- Content: Scope row Info Row Icon/users 3D 'Family' / 'Counted in November and in the family month'. Dialog Title 'Delete this expense?', Body 'It will not count in November or in the family month any more. The bank message stays on your phone.', Choices 'Delete', 'Keep it'.

### Month view (Family)
- Based on: 02-everyday/18-month-view
- Content: Top Bar 'Home · November 2026'. Stat Tiles Tone=Neutral 'Family spent' '₹42,300' and 'Last month' '₹38,100'. Buttons Secondary Medium 'Previous month', 'Next month' (disabled in the current month). Label 'By person': Balance Rows Direction=Plain 'Arjun (you)' '38 expenses' '₹24,100', 'Meera' '26 expenses' '₹18,200'. Label 'By category': Expense Rows Kind=Personal 'Rent and bills' / '1 expense' / '₹24,000' / Meta '57%', 'Groceries' / '14 expenses' / '₹9,840' / '23%'. Day label 'Today' then Expense Rows Kind=Shared 'BigBasket' / 'Groceries · Meera paid' / '₹2,340' / 'Family' and 'Swiggy' / 'Food · You paid' / '₹450' / 'Family'.
- flow.md: 03B branch 'Family month': `SCREEN` `NEW` **Family month list** - Tap the card on Home's Family side. Every family expense by day.

### Friend web page (family invite)
- Based on: 03-getting-paid-back/14-friend-web-page-link-no-longer-works (state card) and 06-friend-web-page-no-app (layout)
- Content: Sticker Icon/heart 3D; Display/L 'Arjun invited you to Home'; Body/M 'Home is a family month on SumBuddy: one monthly total for the two of you.'; Info Row Icon/user-plus 3D 'Get the app' / 'Install SumBuddy on your phone, then open this link again to join.'; Footer Button Primary Large 'Get SumBuddy' (only when the Play Store link exists). No amounts, no Pay button.
- flow.md: 03B Main path step 2 changes to: `SCREEN` `V3` **Invite partner** - By a link they open on their phone. Family members need the app. Add `SCREEN` `NEW` **Link without the app** - The web page says who invited them and to get the app first.

## Risks
- D1 table rebuild in 0003: it relies on PRAGMA defer_foreign_keys on, then off at the end. Cloudflare documents that turning it off discards outstanding violations, but this was not run against D1 here. If wrangler does not wrap the migration file in one transaction, the DROP could fail half way; 'DROP TABLE IF EXISTS groups_new' makes a retry safe. Before the live apply: run it on a throwaway remote D1 database and on a populated local --persist-to folder, and take a Time Travel bookmark. Fallback if it fails: keep kind 'group' and add a groups.family flag column (decision 1).
- The rebuild is safe only because nothing references groups with ON DELETE CASCADE or SET NULL. D1 still runs cascades under defer_foreign_keys. Re-check 0001/0002 for any such clause before applying; the migration test asserts child row counts.
- Older app builds map an unknown kind to GROUP (groupKindOf). A phone on an older build would show a family on the Groups tab, and its settle or remind calls would get 403. Ship the server and the app together; nothing is released yet.
- Joint bank accounts: both partners can get the same debit SMS, and if both mark it Family the family month counts it twice. Not handled in version 1; a later fix could flag likely duplicates (same amount, date and payee from both people).
- A forwarded family link lets whoever opens it join and see every family entry until the owner removes them. Mitigations are the 7-day link life, a single claim, the HIGH 'Meera joined Home' notification, and Remove (which also takes the stranger's entries out). The entries already seen cannot be unseen.
- Leaving, removing or deleting the account tombstones that person's family expenses in one batch. At about 4–5 written rows per expense, a person with thousands of family expenses could use a large part of the 100,000 daily writes. Realistic families stay far below this; chunking would be the later fix.
- Sharing a big backlog of earlier Family rows on set up or join means one PUT each. RL_WRITE (30 a minute) makes 500 rows take about 17 minutes over several WorkManager runs. The data stays correct, but the family month fills slowly.
- Field edits to a shared row made while signed out (session cleared) are not pushed later: reconcile pushes only eligibility changes, so that two phones of one person cannot overwrite each other in a loop. The server copy keeps the old category or payee until the next edit.
- A person removed from a family gets no message: the family just disappears from Home. Copy is not designed; this is the same open point as 13.19 for undone joins.
- Month boundaries: family dates are India dates (server), while the personal month uses the phone's zone. A phone set to another time zone could put an expense in different months on the Me and Family sides.
- The family placeholder member is stored on the server with the English name 'Partner'. It is never shown (the phone shows its own string), but it appears in raw pulls and in the database.
- FCM is optional. Without push, the partner sees new family expenses only on the next foreground or periodic sync. That is acceptable but feels less live.
- Design 06/02 draws Meera with an Orange avatar. The avatar colour rule gives the first other person Green when no group exists, so the board should be redrawn in place.
- The groups snapshot today loads every active group expense. The spec excludes family expenses from it; if that filter is missed, a long family history slows every Groups and Home recomposition.
- The Home toggle side is in-memory app state, so after process death Home opens on Me even if the person left it on Family. This is intended, but worth confirming with the owner.

## Files expected to change
- `server/migrations/0003_family.sql`
- `server/src/db/shapes.ts`
- `server/src/db/groups.ts`
- `server/src/db/links.ts`
- `server/src/db/accounts.ts`
- `server/src/http.ts`
- `server/src/routes/groups.ts`
- `server/src/routes/ledger.ts`
- `server/src/routes/links.ts`
- `server/vitest.config.ts`
- `server/test/env.d.ts`
- `server/test/family.test.ts`
- `server/test/migration.test.ts`
- `server/test/groups.test.ts`
- `server/test/authz.test.ts`
- `server/test/links.test.ts`
- `server/test/me.test.ts`
- `server/test/sync.test.ts`
- `server/test/push.test.ts`
- `server/test/logging.test.ts`
- `server/web/public/pay.js`
- `server/web/public/icons/heart_3d.svg`
- `server/web/build.mjs`
- `server/web/test/page-states.mjs`
- `server/web/test/fixtures/links.mjs`
- `app/src/main/kotlin/com/expenses/app/data/model/Expense.kt`
- `app/src/main/kotlin/com/expenses/app/data/model/Records.kt`
- `app/src/main/kotlin/com/expenses/app/data/db/AppDatabase.kt`
- `app/src/main/kotlin/com/expenses/app/data/db/Daos.kt`
- `app/src/main/kotlin/com/expenses/app/data/repository/ExpenseRepository.kt`
- `app/src/main/kotlin/com/expenses/app/data/repository/PersonalChangeListener.kt`
- `app/src/main/kotlin/com/expenses/app/groups/data/GroupEntities.kt`
- `app/src/main/kotlin/com/expenses/app/groups/data/GroupDaos.kt`
- `app/src/main/kotlin/com/expenses/app/groups/data/Mappers.kt`
- `app/src/main/kotlin/com/expenses/app/groups/data/GroupModels.kt`
- `app/src/main/kotlin/com/expenses/app/groups/data/GroupsRepository.kt`
- `app/src/main/kotlin/com/expenses/app/groups/data/MirrorWriter.kt`
- `app/src/main/kotlin/com/expenses/app/groups/data/FamilyLinker.kt`
- `app/src/main/kotlin/com/expenses/app/groups/data/FamilyRepository.kt`
- `app/src/main/kotlin/com/expenses/app/groups/api/Dtos.kt`
- `app/src/main/kotlin/com/expenses/app/groups/api/GroupBodies.kt`
- `app/src/main/kotlin/com/expenses/app/groups/sync/SyncEngine.kt`
- `app/src/main/kotlin/com/expenses/app/groups/sync/LocalGroupData.kt`
- `app/src/main/kotlin/com/expenses/app/groups/sync/GroupNotifications.kt`
- `app/src/main/kotlin/com/expenses/app/groups/match/CreditMatching.kt`
- `app/src/main/kotlin/com/expenses/app/groups/match/SettlementMatcher.kt`
- `app/src/main/kotlin/com/expenses/app/sms/SmsIngestPipeline.kt`
- `app/src/main/kotlin/com/expenses/app/di/AppContainer.kt`
- `app/src/main/kotlin/com/expenses/app/di/ViewModels.kt`
- `app/src/main/kotlin/com/expenses/app/MainActivity.kt`
- `app/src/main/kotlin/com/expenses/app/ui/nav/Routes.kt`
- `app/src/main/kotlin/com/expenses/app/ui/nav/AppLinks.kt`
- `app/src/main/kotlin/com/expenses/app/ui/nav/AppNavHost.kt`
- `app/src/main/kotlin/com/expenses/app/ui/shell/AppRoot.kt`
- `app/src/main/kotlin/com/expenses/app/ui/screens/home/HomeViewModel.kt`
- `app/src/main/kotlin/com/expenses/app/ui/screens/home/HomeUiState.kt`
- `app/src/main/kotlin/com/expenses/app/ui/screens/home/HomeScreen.kt`
- `app/src/main/kotlin/com/expenses/app/ui/screens/home/HomeNav.kt`
- `app/src/main/kotlin/com/expenses/app/ui/screens/home/HomeSamples.kt`
- `app/src/main/kotlin/com/expenses/app/ui/screens/family/FamilyNav.kt`
- `app/src/main/kotlin/com/expenses/app/ui/screens/family/SetUpFamilyScreen.kt`
- `app/src/main/kotlin/com/expenses/app/ui/screens/family/SetUpFamilyViewModel.kt`
- `app/src/main/kotlin/com/expenses/app/ui/screens/family/FamilySettingsScreen.kt`
- `app/src/main/kotlin/com/expenses/app/ui/screens/family/FamilySettingsViewModel.kt`
- `app/src/main/kotlin/com/expenses/app/ui/screens/family/FamilyUiState.kt`
- `app/src/main/kotlin/com/expenses/app/ui/screens/family/JoinFamilyScreen.kt`
- `app/src/main/kotlin/com/expenses/app/ui/screens/family/FamilyMonthSections.kt`
- `app/src/main/kotlin/com/expenses/app/ui/screens/family/FamilyMonthScreen.kt`
- `app/src/main/kotlin/com/expenses/app/ui/screens/family/FamilyMonthViewModel.kt`
- `app/src/main/kotlin/com/expenses/app/ui/screens/family/FamilyInvite.kt`
- `app/src/main/kotlin/com/expenses/app/ui/screens/family/FamilySamples.kt`
- `app/src/main/kotlin/com/expenses/app/ui/screens/join/JoinGroupViewModel.kt`
- `app/src/main/kotlin/com/expenses/app/ui/screens/join/JoinGroupUiState.kt`
- `app/src/main/kotlin/com/expenses/app/ui/screens/join/JoinGroupScreen.kt`
- `app/src/main/kotlin/com/expenses/app/ui/screens/join/JoinNav.kt`
- `app/src/main/kotlin/com/expenses/app/ui/screens/you/YouScreen.kt`
- `app/src/main/kotlin/com/expenses/app/ui/screens/you/YouUiState.kt`
- `app/src/main/kotlin/com/expenses/app/ui/screens/you/YouViewModel.kt`
- `app/src/main/kotlin/com/expenses/app/ui/screens/you/YouNav.kt`
- `app/src/main/kotlin/com/expenses/app/ui/screens/signin/SignInScreen.kt`
- `app/src/main/kotlin/com/expenses/app/ui/screens/signin/SignInNav.kt`
- `app/src/main/kotlin/com/expenses/app/ui/screens/payment/AddToExpensesViewModel.kt`
- `app/src/main/kotlin/com/expenses/app/ui/screens/payment/AddToExpensesScreen.kt`
- `app/src/main/kotlin/com/expenses/app/ui/screens/payment/AddToExpensesUiState.kt`
- `app/src/main/kotlin/com/expenses/app/ui/screens/payment/ExpenseDetailScreen.kt`
- `app/src/main/kotlin/com/expenses/app/ui/screens/payment/ExpenseDetailUiState.kt`
- `app/src/main/kotlin/com/expenses/app/ui/screens/payment/ExpenseDetailViewModel.kt`
- `app/src/main/kotlin/com/expenses/app/ui/screens/grouppage/GroupPageNav.kt`
- `app/src/main/res/values/strings_family.xml`
- `app/src/main/res/values/strings_home.xml`
- `app/src/main/res/values/strings_you.xml`
- `app/src/main/res/values/strings_signin.xml`
- `app/src/main/res/values/strings_payment.xml`
- `app/schemas/com.expenses.app.data.db.AppDatabase/6.json` (new; `5.json` is Splitwise import's and stays as it is)
- `app/src/test/kotlin/com/expenses/app/data/db/DatabaseMigrationTest.kt`
- `app/src/test/kotlin/com/expenses/app/groups/FamilyLinkerTest.kt`
- `app/src/test/kotlin/com/expenses/app/groups/FamilyRepositoryTest.kt`
- `app/src/test/kotlin/com/expenses/app/groups/SyncEngineTest.kt`
- `app/src/test/kotlin/com/expenses/app/groups/GroupsRepositoryTest.kt`
- `app/src/test/kotlin/com/expenses/app/groups/AvatarColorsTest.kt`
- `app/src/test/kotlin/com/expenses/app/groups/GroupsApiTest.kt`
- `app/src/test/kotlin/com/expenses/app/data/repository/ExpenseRepositoryTest.kt`
- `app/src/test/kotlin/com/expenses/app/sms/SmsIngestPipelineTest.kt`
- `app/src/test/kotlin/com/expenses/app/ui/screens/home/HomeViewModelTest.kt`
- `app/src/test/kotlin/com/expenses/app/ui/screens/join/JoinGroupViewModelTest.kt`
- `app/src/test/kotlin/com/expenses/app/ui/screens/join/JoinGroupScreenTest.kt`
- `app/src/test/kotlin/com/expenses/app/ui/screens/family/SetUpFamilyViewModelTest.kt`
- `app/src/test/kotlin/com/expenses/app/ui/screens/family/FamilySettingsViewModelTest.kt`
- `app/src/test/kotlin/com/expenses/app/ui/screens/family/FamilyScreensTest.kt`
- `app/src/test/kotlin/com/expenses/app/ui/screens/you/YouViewModelTest.kt`
- `app/src/test/kotlin/com/expenses/app/ui/screens/you/YouScreenTest.kt`
- `app/src/test/kotlin/com/expenses/app/ui/screens/signin/SignInScreenJoinTest.kt`
- `app/src/test/kotlin/com/expenses/app/ui/nav/AppLinksTest.kt`
- `docs/server.md`
- `docs/product.md`
- `docs/architecture.md`
- `docs/groups-build-plan.md`
- `design/spec/flow.md`
- `design/spec/README.md`
- `design/spec/screens/06-family/`
- `design/spec/screens/02-everyday/51-home-family.json`
- `design/spec/screens/04-you/00-you.json`
