// Groups and members (docs/server.md 4.6), reminders (4.11), the family rules of these routes (4.18).
import { after, guardVersion, keyStmt, me, memberView, requireMember, runBatch, type Ctx, type Member } from "../context";
import { getUser } from "../db/accounts";
import { constraintOn, guardFailed } from "../db/db";
import {
  MAX_FAMILY_MEMBERS,
  MAX_MEMBERS,
  accessEndedForMemberStmt,
  accessEndedForMembersStmt,
  accessEndedStmt,
  activeMemberCount,
  createGroupStmts,
  editFriendStmt,
  familyExpenseTombstoneStmts,
  getGroup,
  getMember,
  inAnotherFamily,
  insertMemberStmt,
  knownUsers,
  membersByIds,
  membersOf,
  passOwnershipStmt,
  reactivateMemberStmt,
  removeMemberStmts,
  renameGroupStmt,
  tombstoneGroupStmts,
  tombstoneIfEmptyStmts,
  undoJoinStmts,
  usersAtGroupCap,
  type NewMember,
} from "../db/groups";
import { balances, type Balances } from "../db/ledger";
import { bump, guard, placeRule, versionRule, type GuardRule } from "../db/mutation";
import {
  UNDO_JOIN_WINDOW_MS,
  groupJson,
  memberJson,
  undoJoinUntil,
  type GroupKind,
  type GroupRow,
  type MemberRow,
} from "../db/shapes";
import {
  ApiError,
  alreadyInFamily,
  badRequest,
  conflict,
  groupDeleted,
  json,
  noContent,
  notAllowed,
  notFound,
  notOwner,
} from "../http";
import { directGroupId, directMemberId, isUuidV4 } from "../ids";
import { pushTo } from "../notify";
import { HOUR } from "../time";
import * as v from "../validate";

const retryLater = () => new ApiError(503, "internal", "The change could not be applied; retry.");
const notSettled = () => conflict("balances_not_settled", "Balances are not settled.");
/** Settle-up, reminders and balances on a family (docs/server.md 4.18): every net is always 0. */
export const familiesDoNotSettle = () => notAllowed("Families do not settle up.");
const joinWithLink = () => notAllowed("Family members join with a link.");

async function groupWithMembers(ctx: Ctx, gid: string) {
  const group = await getGroup(ctx.db, gid);
  if (!group) throw notFound();
  const members = await membersOf(ctx.db, gid);
  const view = memberView(ctx, group);
  return { group: groupJson(group), members: members.map((m) => memberJson(m, view)) };
}

// ---------- POST /v1/groups ----------

interface GroupInput {
  id: string;
  kind: GroupKind;
  name: string | null;
  myMemberId: string;
  members: { id: string; userId: string | null; name: string | null; phone: string | null }[];
}

function parseGroupInput(ctx: Ctx): GroupInput {
  const body = v.object(ctx.body);
  const id = v.uuid(body.id, "id");
  const kind = v.oneOf(body.kind, "kind", ["group", "direct", "family"] as const);
  const myMemberId = v.uuid(body.myMemberId, "myMemberId");
  let name: string | null = null;
  if (kind !== "direct") name = v.text(body.name, "name", 1, 40);
  else if (body.name !== undefined && body.name !== null) throw badRequest("name", "Direct splits have no name.");
  if (kind === "family") return { id, kind, name, myMemberId, members: [parsePlaceholder(body.members, myMemberId)] };
  const list = kind === "direct" ? v.array(body.members, "members", 1, 1) : v.array(body.members, "members", 0, MAX_MEMBERS - 1);
  const seenIds = new Set([myMemberId]);
  const seenUsers = new Set([me(ctx)]);
  const members = list.map((raw, i) => {
    const f = `members[${i}]`;
    const m = v.object(raw, f);
    const mid = v.uuid(m.id, `${f}.id`);
    if (seenIds.has(mid)) throw badRequest(`${f}.id`, "Member ids must be unique.");
    seenIds.add(mid);
    if (m.userId !== undefined && m.userId !== null) {
      const userId = v.uuid(m.userId, `${f}.userId`);
      if (m.name !== undefined || m.phone !== undefined) throw badRequest(`${f}.name`, "Give a user id or a name, not both.");
      if (seenUsers.has(userId)) throw badRequest(`${f}.userId`, "Users must be unique and not the caller.");
      seenUsers.add(userId);
      return { id: mid, userId, name: null, phone: null };
    }
    return { id: mid, userId: null, name: v.text(m.name, `${f}.name`, 1, 60), phone: v.optionalPhone(m.phone, `${f}.phone`) };
  });
  return { id, kind, name, myMemberId, members };
}

/**
 * A family is made with exactly one other member: the placeholder its invite link is for (docs/server.md 4.18). It
 * has no user (the partner joins with the link) and no phone; the phone always names it "Partner", and once someone
 * joins, their own name wins.
 */
function parsePlaceholder(raw: unknown, myMemberId: string): GroupInput["members"][number] {
  const m = v.object(v.array(raw, "members", 1, 1)[0], "members[0]");
  const mid = v.uuid(m.id, "members[0].id");
  if (mid === myMemberId) throw badRequest("members[0].id", "Member ids must be unique.");
  if (m.userId !== undefined && m.userId !== null) throw badRequest("members[0].userId", "Family members join with a link.");
  if (m.phone !== undefined && m.phone !== null) throw badRequest("members[0].phone", "Family members join with a link.");
  return { id: mid, userId: null, name: v.text(m.name, "members[0].name", 1, 60), phone: null };
}

export async function createGroup(ctx: Ctx): Promise<Response> {
  const input = parseGroupInput(ctx);
  const userIds = input.members.filter((m) => m.userId).map((m) => m.userId!);
  if (input.kind === "direct" && userIds.length === 1) {
    // The one place a client-made id is not random: both phones compute it, and the server checks it.
    const gid = await directGroupId(me(ctx), userIds[0]);
    if (input.id !== gid) throw badRequest("id", "Direct group id does not match.");
    if (input.myMemberId !== (await directMemberId(gid, me(ctx)))) throw badRequest("myMemberId", "Direct member id does not match.");
    if (input.members[0].id !== (await directMemberId(gid, userIds[0]))) {
      throw badRequest("members[0].id", "Direct member id does not match.");
    }
  } else {
    if (!isUuidV4(input.id)) throw badRequest("id", "Expected a random (version 4) UUID.");
    if (!isUuidV4(input.myMemberId)) throw badRequest("myMemberId", "Expected a random (version 4) UUID.");
    input.members.forEach((m, i) => {
      if (!isUuidV4(m.id)) throw badRequest(`members[${i}].id`, "Expected a random (version 4) UUID.");
    });
  }
  const existing = await getGroup(ctx.db, input.id);
  if (existing) return existingGroup(ctx, existing, input);
  const family = input.kind === "family";
  // One live family per user (4.18), checked here and again by the guard in the batch.
  if (family && (await inAnotherFamily(ctx.db, me(ctx), input.id))) throw alreadyInFamily();

  const known = await knownUsers(ctx.db, me(ctx), userIds);
  if (known.length !== userIds.length) throw notAllowed("Only people you already share a group with can be added.");
  if ((await usersAtGroupCap(ctx.db, [me(ctx), ...userIds])).length > 0) {
    throw conflict("limit_reached", "Too many groups.");
  }
  const myUser = await getUser(ctx.db, me(ctx));
  const names = new Map(known.map((k) => [k.id, k.name]));
  const members: NewMember[] = [
    { id: input.myMemberId, userId: me(ctx), name: myUser!.name, phone: null },
    ...input.members.map((m) => ({
      id: m.id,
      userId: m.userId,
      name: m.userId ? names.get(m.userId)! : m.name!,
      phone: m.userId ? null : m.phone,
    })),
  ];
  try {
    await runBatch(ctx, "group.create", [
      ...createGroupStmts(ctx.db, {
        gid: input.id,
        kind: input.kind,
        name: input.name,
        ownerUserId: me(ctx),
        actor: input.myMemberId,
        members,
        now: ctx.now,
        onlyFamilyOf: family ? me(ctx) : undefined,
      }),
      ...keyStmt(ctx, 201, input.id),
    ]);
  } catch (err) {
    if (constraintOn(err, "groups.id")) {
      const again = await getGroup(ctx.db, input.id);
      if (again) return existingGroup(ctx, again, input);
    }
    if (constraintOn(err, "members.id")) throw conflict("exists_different", "A member id is already used.");
    if (family && guardFailed(err)) {
      // Another family of the caller's went in between the check and the batch (two phones at once).
      if (await inAnotherFamily(ctx.db, me(ctx), input.id)) throw alreadyInFamily();
      throw retryLater();
    }
    throw err;
  }
  ctx.exec.waitUntil(
    pushTo(ctx.env, ctx.db, userIds, { t: "groups" }, { callerUserId: me(ctx), callerInstallationId: ctx.installationId, collapseKey: "groups" }),
  );
  return json(await groupWithMembers(ctx, input.id), 201);
}

/** The id exists: same content answers 200; a direct split of the same two users makes removed members active. */
async function existingGroup(ctx: Ctx, g: GroupRow, input: GroupInput): Promise<Response> {
  if (g.deleted_at !== null) throw groupDeleted();
  if (g.kind !== input.kind) throw conflict("exists_different", "The group exists with other content.");
  const members = await membersOf(ctx.db, g.id);
  const mine = members.find((m) => m.id === input.myMemberId && m.user_id === me(ctx));
  if (!mine) throw conflict("exists_different", "The group exists with other content.");
  const other = input.members[0];
  if (g.kind === "direct" && other?.userId) {
    const theirs = members.find((m) => m.id === other.id && m.user_id === other.userId);
    if (!theirs) throw conflict("exists_different", "The group exists with other content.");
    const removed = [mine, theirs].filter((m) => m.removed_at !== null);
    if (removed.length > 0) {
      if (mine.removed_at !== null && (await usersAtGroupCap(ctx.db, [me(ctx)])).length > 0) {
        throw conflict("limit_reached", "Too many groups.");
      }
      const stmts: D1PreparedStatement[] = [];
      for (const m of removed) {
        stmts.push(
          bump(ctx.db, g.id, ctx.now),
          reactivateMemberStmt(ctx.db, g.id, m.id),
          guard(ctx.db, {
            gid: g.id,
            kind: "member",
            op: "join",
            entityId: m.id,
            actor: input.myMemberId,
            now: ctx.now,
            rule: { sql: "removed_at IS NULL", params: [] },
          }),
        );
      }
      let results: D1Result[];
      try {
        results = await runBatch(ctx, "direct.rejoin", [...stmts, ...keyStmt(ctx, 200, g.id)]);
      } catch (err) {
        if (guardFailed(err)) throw retryLater();
        throw err;
      }
      const version = guardVersion(results, stmts.length - 1);
      after(ctx, { gid: g.id, version, groupsPush: theirs.removed_at !== null ? [other.userId] : [] });
    }
    return json(await groupWithMembers(ctx, g.id), 200);
  }
  if (mine.removed_at !== null || !input.members.every((x) => members.some((m) => m.id === x.id))) {
    throw conflict("exists_different", "The group exists with other content.");
  }
  return json(await groupWithMembers(ctx, g.id), 200);
}

/**
 * Replays that answer with content check access first (4.3): the key alone must not let someone who left, or was
 * removed, read the group as it is now. A non-member gets 403 not_member, a member of a deleted group 410.
 */
export async function replayGroupCreate(ctx: Ctx, entityId: string | null, status: number): Promise<Response> {
  await requireMember(ctx, entityId!);
  return json(await groupWithMembers(ctx, entityId!), status);
}

// ---------- PATCH /v1/groups/{gid} ----------

export async function renameGroup(ctx: Ctx): Promise<Response> {
  const gid = ctx.params.gid;
  const access = await requireMember(ctx, gid);
  if (access.group.kind === "direct") throw notAllowed("Direct splits cannot be renamed.");
  const name = v.text(v.object(ctx.body).name, "name", 1, 40);
  let results: D1Result[];
  try {
    results = await runBatch(ctx, "group.rename", [
      bump(ctx.db, gid, ctx.now),
      renameGroupStmt(ctx.db, gid, name),
      guard(ctx.db, { gid, kind: "group", op: "update", entityId: gid, actor: access.myMemberId, now: ctx.now }),
      ...keyStmt(ctx, 200, gid),
    ]);
  } catch (err) {
    if (guardFailed(err)) {
      await requireMember(ctx, gid);
      throw retryLater();
    }
    throw err;
  }
  const version = guardVersion(results, 2);
  after(ctx, { gid, version });
  return json(groupJson({ ...access.group, name, version }));
}

export async function replayGroup(ctx: Ctx, _entityId: string | null, status: number): Promise<Response> {
  const access = await requireMember(ctx, ctx.params.gid);
  return json(groupJson(access.group), status);
}

/** Leave, delete and the like: the answer holds nothing, so a retry gets its 204 whatever happened since. */
export async function replayNoContent(): Promise<Response> {
  return noContent();
}

/**
 * Leave and delete group depend on balances at the moment the server applies them: check the cached balances, run the
 * batch with the version guard, and on a guard failure check once more (docs/server.md 3.1). Remove member does the
 * same in its own two attempts (`removeMember`), because it must also read the member's place again.
 */
async function settled(
  ctx: Ctx,
  gid: string,
  label: string,
  check: (access: Member, b: Balances) => void | Promise<void>,
  stmts: (access: Member) => D1PreparedStatement[],
  first?: Member,
): Promise<{ access: Member; results: D1Result[] }> {
  for (let attempt = 0; attempt < 2; attempt++) {
    const access = attempt === 0 && first ? first : await requireMember(ctx, gid);
    await check(access, await balances(ctx.db, gid, access.group.version));
    try {
      return { access, results: await runBatch(ctx, label, stmts(access)) };
    } catch (err) {
      if (!guardFailed(err)) throw err;
    }
  }
  throw notSettled();
}

const netOf = (b: Balances, mid: string) => b.nets.find((n) => n.memberId === mid)?.netPaise ?? 0;

/** Someone other than the caller is an active member on the app (docs/server.md 4.6 delete, 4.17 import). */
export function othersOnApp(members: MemberRow[], callerUserId: string): boolean {
  return members.some((m) => m.user_id !== null && m.user_id !== callerUserId && m.removed_at === null);
}

// ---------- DELETE /v1/groups/{gid} ----------

/**
 * The owner deletes when every net is 0, or when nobody else in the group is on the app (docs/server.md 4.6; decided
 * 2026-11-18 with Import from Splitwise, docs/splitwise-import.md 3.3): an import of the wrong file can otherwise never
 * be removed, since nobody can settle it. The members are read inside the check, so a join after the first read (it
 * bumps the version and fails the guard) is seen by the second.
 */
export async function deleteGroup(ctx: Ctx): Promise<Response> {
  const gid = ctx.params.gid;
  const first = await requireMember(ctx, gid);
  if (first.group.kind === "family") throw notAllowed("A family ends when its last member leaves.");
  if (first.group.kind !== "group") throw notAllowed("Direct splits cannot be deleted.");
  if (first.group.owner_user_id !== me(ctx)) throw notOwner();
  const appUsers = (await membersOf(ctx.db, gid)).filter((m) => m.user_id && m.removed_at === null).map((m) => m.user_id!);
  const { results } = await settled(
    ctx,
    gid,
    "group.delete",
    async (access, b) => {
      if (access.group.owner_user_id !== me(ctx)) throw notOwner();
      if (b.nets.some((n) => n.netPaise !== 0) && othersOnApp(await membersOf(ctx.db, gid), me(ctx))) throw notSettled();
    },
    (access) => [
      bump(ctx.db, gid, ctx.now),
      guard(ctx.db, {
        gid,
        kind: "group",
        op: "delete",
        entityId: gid,
        actor: access.myMemberId,
        now: ctx.now,
        rule: versionRule(access.group.version),
      }),
      ...tombstoneGroupStmts(ctx.db, gid, ctx.now),
      // Why the group left each member's list (4.5 `lost`): their phones keep its splits (9.4).
      accessEndedForMembersStmt(ctx.db, gid, "deleted", ctx.now),
      ...keyStmt(ctx, 204, gid),
    ],
  );
  after(ctx, { gid, version: guardVersion(results, 1), kick: appUsers, groupsPush: appUsers, noChangedPush: true });
  return noContent();
}

// ---------- POST /v1/groups/{gid}/leave ----------

export async function leaveGroup(ctx: Ctx): Promise<Response> {
  const gid = ctx.params.gid;
  const first = await requireMember(ctx, gid);
  if (first.group.kind === "family") return leaveFamily(ctx, first);
  const { access, results } = await settled(
    ctx,
    gid,
    "group.leave",
    (a, b) => {
      if (netOf(b, a.myMemberId) !== 0) throw notSettled();
    },
    (a) => [
      bump(ctx.db, gid, ctx.now),
      ...removeMemberStmts(ctx.db, gid, a.myMemberId, ctx.now),
      ...(a.group.owner_user_id === me(ctx) ? [passOwnershipStmt(ctx.db, gid, me(ctx))] : []),
      guard(ctx.db, {
        gid,
        kind: "member",
        op: "leave",
        entityId: a.myMemberId,
        actor: a.myMemberId,
        now: ctx.now,
        rule: versionRule(a.group.version),
      }),
      ...tombstoneIfEmptyStmts(ctx.db, gid, ctx.now),
      accessEndedStmt(ctx.db, me(ctx), gid, "left", ctx.now),
      ...keyStmt(ctx, 204, gid),
    ],
    first,
  );
  const guardIndex = access.group.owner_user_id === me(ctx) ? 4 : 3;
  after(ctx, { gid, version: guardVersion(results, guardIndex), kick: [me(ctx)], groupsPush: [me(ctx)] });
  return noContent();
}

/**
 * Leave a family (4.18): no balance check (every net is 0). The leaver's family expenses leave the family in the same
 * batch, ownership passes on, and a family with no member on the app left is tombstoned: the placeholder of an invite
 * not yet accepted does not keep it alive.
 */
async function leaveFamily(ctx: Ctx, access: Member): Promise<Response> {
  const gid = access.group.id;
  const mine = access.myMemberId;
  const owner = access.group.owner_user_id === me(ctx);
  const stmts = [
    bump(ctx.db, gid, ctx.now),
    ...removeMemberStmts(ctx.db, gid, mine, ctx.now),
    ...familyExpenseTombstoneStmts(ctx.db, gid, mine, mine, ctx.now),
    ...(owner ? [passOwnershipStmt(ctx.db, gid, me(ctx))] : []),
    guard(ctx.db, {
      gid,
      kind: "member",
      op: "leave",
      entityId: mine,
      actor: mine,
      now: ctx.now,
      rule: { sql: "removed_at IS NOT NULL", params: [] },
    }),
    ...tombstoneIfEmptyStmts(ctx.db, gid, ctx.now),
    accessEndedStmt(ctx.db, me(ctx), gid, "left", ctx.now),
    ...keyStmt(ctx, 204, gid),
  ];
  const guardIndex = owner ? 6 : 5;
  let results: D1Result[];
  try {
    results = await runBatch(ctx, "group.leave", stmts);
  } catch (err) {
    if (guardFailed(err)) {
      await requireMember(ctx, gid); // left meanwhile (another phone): 403 not_member; deleted: 410
      throw retryLater();
    }
    throw err;
  }
  // The partner's phones get a NORMAL "changed" and pull the tombstones; no notification is designed.
  after(ctx, { gid, version: guardVersion(results, guardIndex), kick: [me(ctx)], groupsPush: [me(ctx)] });
  return noContent();
}

// ---------- POST /v1/groups/{gid}/members ----------

export async function addMember(ctx: Ctx): Promise<Response> {
  const gid = ctx.params.gid;
  const access = await requireMember(ctx, gid);
  if (access.group.kind === "direct") throw notAllowed("Direct splits have exactly two people.");
  const family = access.group.kind === "family";
  const cap = family ? MAX_FAMILY_MEMBERS : MAX_MEMBERS;
  const body = v.object(ctx.body);
  const id = v.uuid(body.id, "id");
  if (!isUuidV4(id)) throw badRequest("id", "Expected a random (version 4) UUID.");
  let m: NewMember;
  if (family) {
    // Only the placeholder of a new invite (4.18): the partner joins with its link.
    if (body.userId !== undefined && body.userId !== null) throw joinWithLink();
    if (body.phone !== undefined && body.phone !== null) throw badRequest("phone", "Family members join with a link.");
    m = { id, userId: null, name: v.text(body.name, "name", 1, 60), phone: null };
  } else if (body.userId !== undefined && body.userId !== null) {
    const userId = v.uuid(body.userId, "userId");
    if (body.name !== undefined || body.phone !== undefined) throw badRequest("name", "Give a user id or a name, not both.");
    m = { id, userId, name: "", phone: null };
  } else {
    m = { id, userId: null, name: v.text(body.name, "name", 1, 60), phone: v.optionalPhone(body.phone, "phone") };
  }

  const existing = await getMember(ctx.db, id);
  if (existing) {
    const same =
      existing.group_id === gid &&
      (m.userId ? existing.user_id === m.userId : existing.user_id === null && existing.name === m.name && existing.phone === m.phone);
    if (!same) throw conflict("exists_different", "The member id exists with other content.");
    return json(memberJson(existing, memberView(ctx, access.group)), 200);
  }
  if (((await activeMemberCount(ctx.db, gid))?.n ?? 0) >= cap) throw conflict("limit_reached", "Too many members.");
  if (m.userId) {
    const known = await knownUsers(ctx.db, me(ctx), [m.userId]);
    if (known.length !== 1) throw notAllowed("Only people you already share a group with can be added.");
    m.name = known[0].name;
    const active = (await membersOf(ctx.db, gid)).some((x) => x.user_id === m.userId && x.removed_at === null);
    if (active) throw conflict("already_member", "Already a member.");
    if ((await usersAtGroupCap(ctx.db, [m.userId])).length > 0) throw conflict("limit_reached", "Too many groups.");
  }
  let results: D1Result[];
  try {
    results = await runBatch(ctx, "member.create", [
      bump(ctx.db, gid, ctx.now),
      insertMemberStmt(ctx.db, gid, m, access.myMemberId, ctx.now),
      guard(ctx.db, {
        gid,
        kind: "member",
        op: "create",
        entityId: id,
        actor: access.myMemberId,
        now: ctx.now,
        rule: { sql: `(SELECT COUNT(*) FROM members mc WHERE mc.group_id = ?1 AND mc.removed_at IS NULL) <= ${cap}`, params: [] },
      }),
      ...keyStmt(ctx, 201, id),
    ]);
  } catch (err) {
    if (constraintOn(err, "members.group_id, members.user_id")) throw conflict("already_member", "Already a member.");
    if (constraintOn(err, "members.id")) throw conflict("exists_different", "The member id exists with other content.");
    if (guardFailed(err)) {
      await requireMember(ctx, gid);
      throw conflict("limit_reached", "Too many members.");
    }
    throw err;
  }
  const version = guardVersion(results, 2);
  after(ctx, { gid, version, groupsPush: m.userId ? [m.userId] : [], high: m.userId ? [m.userId] : [] });
  return json(memberJson((await getMember(ctx.db, id))!, memberView(ctx, access.group)), 201);
}

export async function replayMember(ctx: Ctx, entityId: string | null, status: number): Promise<Response> {
  const access = await requireMember(ctx, ctx.params.gid);
  const m = await getMember(ctx.db, entityId!);
  if (!m || m.group_id !== ctx.params.gid) throw notFound();
  return json(memberJson(m, memberView(ctx, access.group)), status);
}

// ---------- PATCH /v1/groups/{gid}/members/{mid} ----------

export async function editMember(ctx: Ctx): Promise<Response> {
  const { gid, mid } = ctx.params;
  const access = await requireMember(ctx, gid);
  // A family's placeholder keeps its fixed name and never has a phone (4.18); nobody else in a family can be edited.
  if (access.group.kind === "family") throw joinWithLink();
  const target = await getMember(ctx.db, mid);
  if (!target || target.group_id !== gid || target.removed_at !== null) throw notFound();
  if (target.user_id !== null) throw conflict("member_on_app", "Only friends without the app can be edited.");
  const body = v.object(ctx.body);
  const name = body.name === undefined ? target.name : v.text(body.name, "name", 1, 60);
  const phone = body.phone === undefined ? target.phone : v.optionalPhone(body.phone, "phone");
  if (name === target.name && phone === target.phone) return json(memberJson(target, memberView(ctx, access.group)));
  let results: D1Result[];
  try {
    results = await runBatch(ctx, "member.update", [
      bump(ctx.db, gid, ctx.now),
      editFriendStmt(ctx.db, gid, mid, name, phone),
      guard(ctx.db, {
        gid,
        kind: "member",
        op: "update",
        entityId: mid,
        actor: access.myMemberId,
        now: ctx.now,
        rule: { sql: "user_id IS NULL AND removed_at IS NULL", params: [] },
      }),
      ...keyStmt(ctx, 200, mid),
    ]);
  } catch (err) {
    if (guardFailed(err)) {
      await requireMember(ctx, gid);
      const now = await getMember(ctx.db, mid);
      if (now?.user_id) throw conflict("member_on_app", "Only friends without the app can be edited.");
      throw notFound();
    }
    throw err;
  }
  after(ctx, { gid, version: guardVersion(results, 2) });
  return json(memberJson((await getMember(ctx.db, mid))!, memberView(ctx, access.group)));
}

// ---------- DELETE /v1/groups/{gid}/members/{mid} ----------

/**
 * Checked and guarded like `settled` (3.1), and the place is read again on every attempt: through a link someone can
 * take a friend's place between the checks and the batch. The guard names who held the place when the checks ran, so
 * such a batch fails and every check runs again on the place as it is now. Without that, a member who is not the
 * owner could remove a person who has just joined, and that person's phone would never learn why the group is gone.
 */
export async function removeMember(ctx: Ctx): Promise<Response> {
  const { gid, mid } = ctx.params;
  let family = false;
  for (let attempt = 0; attempt < 2; attempt++) {
    const access = await requireMember(ctx, gid);
    if (access.group.kind === "direct") throw notAllowed("Direct splits have exactly two people.");
    family = access.group.kind === "family";
    const target = await getMember(ctx.db, mid);
    if (!target || target.group_id !== gid) throw notFound();
    if (target.id === access.myMemberId) throw notAllowed("Use leave to leave a group.");
    if (target.removed_at !== null) return noContent(); // already removed
    if (target.user_id !== null && access.group.owner_user_id !== me(ctx)) throw notOwner();
    // A family has no balances (4.18): the partner's family expenses leave the family instead.
    if (!family && netOf(await balances(ctx.db, gid, access.group.version), mid) !== 0) throw notSettled();
    const tombstones = family ? familyExpenseTombstoneStmts(ctx.db, gid, mid, access.myMemberId, ctx.now) : [];
    let results: D1Result[];
    try {
      results = await runBatch(ctx, "member.remove", [
        bump(ctx.db, gid, ctx.now),
        ...removeMemberStmts(ctx.db, gid, mid, ctx.now),
        ...tombstones,
        guard(ctx.db, {
          gid,
          kind: "member",
          op: "remove",
          entityId: mid,
          actor: access.myMemberId,
          now: ctx.now,
          rule: family ? familyPlaceRule(target.user_id) : placeRule(access.group.version, target.user_id),
        }),
        // Why the group left the person's list (4.5 `lost`), for whoever holds the place in this batch.
        accessEndedForMemberStmt(ctx.db, gid, mid, "removed", ctx.now),
        ...keyStmt(ctx, 204, mid),
      ]);
    } catch (err) {
      if (!guardFailed(err)) throw err;
      continue;
    }
    // The guard passed, so the place is still held by the person read above.
    const kicked = target.user_id ? [target.user_id] : [];
    // A removal from a family makes no notification (none is designed): no HIGH push.
    after(ctx, { gid, version: guardVersion(results, 3 + tombstones.length), kick: kicked, groupsPush: kicked, high: family ? [] : kicked });
    return noContent();
  }
  throw family ? retryLater() : notSettled();
}

/**
 * Rule for removing a family member (4.18): the place is still held by the person the checks saw (`userId`), or by
 * nobody when they saw the placeholder. There is no balance to keep, so no version rule: a place taken through the
 * link between the checks and the batch fails the guard, and every check runs once more.
 */
function familyPlaceRule(userId: string | null): GuardRule {
  return userId === null
    ? { sql: "removed_at IS NOT NULL AND user_id IS NULL", params: [] }
    : { sql: "removed_at IS NOT NULL AND user_id = ?7", params: [userId] };
}

// ---------- POST /v1/groups/{gid}/members/{mid}/undo-join ----------

const notJoinedByLink = () => conflict("not_joined_by_link", "The person did not join through a friend's link.");
const undoExpired = () => conflict("undo_expired", "A join can be undone for 7 days only.");

/** The rules of undo-join, in the order their answers are given (4.6). Returns the join to undo. */
function checkUndo(ctx: Ctx, access: Member, target: MemberRow | null, gid: string): { userId: string; linkId: string } {
  if (access.group.owner_user_id !== me(ctx)) throw notOwner();
  if (!target || target.group_id !== gid) throw notFound();
  if (target.id === access.myMemberId) throw notAllowed("Use leave to leave a group.");
  if (target.user_id === null || target.removed_at !== null || target.join_link_id === null) throw notJoinedByLink();
  if (undoJoinUntil(target, ctx.now) === null) throw undoExpired();
  return { userId: target.user_id, linkId: target.join_link_id };
}

/**
 * "Not Priya? Undo" (docs/product.md "Forwarded links"): the owner, within 7 days, takes the place back from the
 * person who joined through a friend's link. The place becomes the friend without the app again, its links and the
 * links that person made stop working, and the person is out of the group. One batch with the 3.1 guard.
 */
export async function undoJoin(ctx: Ctx): Promise<Response> {
  const { gid, mid } = ctx.params;
  const access = await requireMember(ctx, gid);
  if (access.group.kind === "family") throw notAllowed("In a family the owner removes instead.");
  const join = checkUndo(ctx, access, await getMember(ctx.db, mid), gid);
  let results: D1Result[];
  try {
    results = await runBatch(ctx, "member.undoJoin", [
      bump(ctx.db, gid, ctx.now),
      ...undoJoinStmts(ctx.db, { gid, mid, ...join, since: ctx.now - UNDO_JOIN_WINDOW_MS, now: ctx.now }),
      guard(ctx.db, {
        gid,
        kind: "member",
        op: "update",
        entityId: mid,
        actor: access.myMemberId,
        now: ctx.now,
        // The caller is still the owner: ownership can pass (the owner left) between the checks and the batch.
        rule: { sql: "user_id IS NULL AND removed_at IS NULL AND (SELECT owner_user_id FROM groups WHERE id = ?1) = ?7", params: [me(ctx)] },
      }),
      // The person's phone forgets the place's splits for this reason only (4.5 `lost`, 9.4).
      accessEndedStmt(ctx.db, join.userId, gid, "join_undone", ctx.now),
      ...keyStmt(ctx, 200, mid),
    ]);
  } catch (err) {
    if (guardFailed(err)) {
      // Something changed after the checks: answer as they read now (left, already undone, owner changed, deleted).
      checkUndo(ctx, await requireMember(ctx, gid), await getMember(ctx.db, mid), gid);
      throw retryLater();
    }
    throw err;
  }
  // The person's sockets close (4403) and their phones get {"t":"groups"}: the group leaves their list. No
  // notification for anyone (no HIGH push): the copy is not designed.
  after(ctx, { gid, version: guardVersion(results, 3), kick: [join.userId], groupsPush: [join.userId] });
  return json(memberJson((await getMember(ctx.db, mid))!, memberView(ctx, access.group)));
}

// ---------- POST /v1/groups/{gid}/reminders ----------

const REMIND_GAP = 20 * HOUR;

export async function remind(ctx: Ctx): Promise<Response> {
  const gid = ctx.params.gid;
  const access = await requireMember(ctx, gid);
  if (access.group.kind === "family") throw familiesDoNotSettle();
  const ids = v.array(v.object(ctx.body).memberIds, "memberIds", 1, MAX_MEMBERS).map((x, i) => v.uuid(x, `memberIds[${i}]`));
  if (new Set(ids).size !== ids.length) throw badRequest("memberIds", "Member ids must be unique.");
  const rows = await membersByIds(ctx.db, ids);
  ids.forEach((id, i) => {
    if (!rows.some((r) => r.id === id && r.group_id === gid)) throw badRequest(`memberIds[${i}]`, "Not a member of the group.");
  });
  const b = await balances(ctx.db, gid, access.group.version);
  const sent: string[] = [];
  const skipped: { memberId: string; reason: string }[] = [];
  const pushUsers: string[] = [];
  for (const id of ids) {
    const r = rows.find((x) => x.id === id)!;
    const owes = b.transfers
      .filter((t) => t.fromMemberId === id && t.toMemberId === access.myMemberId)
      .reduce((s, t) => s + t.amountPaise, 0);
    if (r.user_id === null) skipped.push({ memberId: id, reason: "not_on_app" });
    else if (r.removed_at !== null || owes <= 0) skipped.push({ memberId: id, reason: "nothing_owed" });
    else if (r.last_reminded_at !== null && ctx.now - r.last_reminded_at < REMIND_GAP) {
      skipped.push({ memberId: id, reason: "too_soon" });
    } else {
      sent.push(id);
      pushUsers.push(r.user_id);
    }
  }
  const result = { sent, skipped };
  const stmts: D1PreparedStatement[] = [];
  if (sent.length > 0) {
    const ph = sent.map((_, i) => `?${i + 3}`).join(", ");
    stmts.push(ctx.db.stmt(`UPDATE members SET last_reminded_at = ?2 WHERE group_id = ?1 AND id IN (${ph})`, gid, ctx.now, ...sent));
  }
  // No change row and no version bump. The first answer is kept with the key so a replay repeats it.
  stmts.push(...keyStmt(ctx, 200, JSON.stringify(result)));
  if (stmts.length > 0) await runBatch(ctx, "reminders", stmts);
  ctx.exec.waitUntil(
    pushTo(ctx.env, ctx.db, pushUsers, { t: "remind", g: gid, m: access.myMemberId }, {
      callerUserId: me(ctx),
      callerInstallationId: ctx.installationId,
    }),
  );
  return json(result);
}

export async function replayRemind(_ctx: Ctx, entityId: string | null, status: number): Promise<Response> {
  return json(entityId ? JSON.parse(entityId) : { sent: [], skipped: [] }, status);
}
