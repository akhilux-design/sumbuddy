// Share links, invites and the public link data (docs/server.md 4.10), their family rules (4.18), and the live room
// upgrade (4.12).
import { after, guardVersion, keyStmt, me, requireMember, runBatch, type Ctx } from "../context";
import { sha256Hex } from "../crypto";
import { constraintOn, guardFailed, type Db } from "../db/db";
import { NOT_IN_ANOTHER_FAMILY, getMember, inAnotherFamily, membersOf, usersAtGroupCap } from "../db/groups";
import { cached, wholeGroup } from "../db/ledger";
import {
  FAMILY_LINK_TTL_MS,
  LINK_TTL_MS,
  acceptedLinkByHash,
  getLinkById,
  insertLink,
  liveLinkByHash,
  revokeLinkStmt,
  revokeMemberLinksStmt,
  type LiveLink,
} from "../db/links";
import { V1, bump, guard } from "../db/mutation";
import { memberName, type GroupKind, type MemberRow } from "../db/shapes";
import { alreadyInFamily, badRequest, conflict, json, linkNotFound, noContent, notAllowed, notFound } from "../http";
import { SHARE_TOKEN_RE } from "../ids";
import { indiaMonth } from "../time";
import * as v from "../validate";

const firstWord = (name: string) => name.split(/\s+/)[0];

// ---------- PUT /v1/groups/{gid}/members/{mid}/links/{linkId} ----------

export async function putLink(ctx: Ctx): Promise<Response> {
  const { gid, mid, linkId } = ctx.params;
  const access = await requireMember(ctx, gid);
  const body = v.object(ctx.body);
  if (typeof body.token !== "string" || !SHARE_TOKEN_RE.test(body.token)) throw badRequest("token", "Expected 24 base64url characters.");
  const tokenHash = await sha256Hex(body.token);
  // expiresAt is always the stored one: a family link lives 7 days, any other 90 (4.18).
  const answer = (createdAt: number, expiresAt: number, status: number) =>
    json({ linkId, url: `https://${ctx.env.LINK_HOST}/pay/${body.token}`, createdAt, expiresAt }, status);
  const existing = await getLinkById(ctx.db, linkId);
  if (existing) {
    // A lost answer is simply sent again: same id and token answers 200 (4.10). A link that was turned off or ran
    // out meanwhile (another phone's Turn off link, an undo-join, the friend removed) is not given out again: the
    // phone then makes a new one.
    if (existing.group_id === gid && existing.member_id === mid && existing.token_hash === tokenHash) {
      if (existing.revoked_at !== null || existing.expires_at <= ctx.now) throw linkNotFound();
      return answer(existing.created_at, existing.expires_at, 200);
    }
    throw conflict("exists_different", "The link exists with other content.");
  }
  const target = await getMember(ctx.db, mid);
  if (!target || target.group_id !== gid || target.removed_at !== null) throw notFound();
  if (target.id === access.myMemberId) throw notAllowed("Links are for other members.");
  const family = access.group.kind === "family";
  // A family link is an invite for the placeholder only: the partner on the app needs none.
  if (family && target.user_id !== null) throw conflict("member_on_app", "Family links are for the partner who has not joined.");
  const ttlMs = family ? FAMILY_LINK_TTL_MS : LINK_TTL_MS;
  let inserted: number;
  try {
    inserted = await insertLink(ctx.db, { linkId, tokenHash, gid, mid, createdBy: access.myMemberId, now: ctx.now, ttlMs });
  } catch (err) {
    if (constraintOn(err, "links.")) {
      const again = await getLinkById(ctx.db, linkId);
      if (again && again.group_id === gid && again.member_id === mid && again.token_hash === tokenHash) {
        return answer(again.created_at, again.expires_at, 200);
      }
      throw conflict("exists_different", "The link exists with other content.");
    }
    throw err;
  }
  if (inserted !== 1) {
    await requireMember(ctx, gid);
    throw notFound();
  }
  return answer(ctx.now, ctx.now + ttlMs, 201);
}

export async function revokeLink(ctx: Ctx): Promise<Response> {
  const { gid, linkId } = ctx.params;
  await requireMember(ctx, gid);
  const link = await getLinkById(ctx.db, linkId);
  if (!link || link.group_id !== gid) throw notFound();
  await runBatch(ctx, "link.revoke", [revokeLinkStmt(ctx.db, gid, linkId, ctx.now), ...keyStmt(ctx, 204, linkId)]);
  return noContent();
}

export async function revokeMemberLinks(ctx: Ctx): Promise<Response> {
  const { gid, mid } = ctx.params;
  await requireMember(ctx, gid);
  const target = await getMember(ctx.db, mid);
  if (!target || target.group_id !== gid) throw notFound();
  await runBatch(ctx, "link.revokeAll", [revokeMemberLinksStmt(ctx.db, gid, mid, ctx.now), ...keyStmt(ctx, 204, mid)]);
  return noContent();
}

// ---------- invites ----------

async function linkFor(ctx: Ctx, token: string): Promise<LiveLink> {
  if (!SHARE_TOKEN_RE.test(token)) throw linkNotFound();
  const link = await liveLinkByHash(ctx.db, await sha256Hex(token), ctx.now);
  if (!link) throw linkNotFound();
  return link;
}

interface InvitePreview {
  groupId: string;
  groupName: string | null;
  inviterName: string;
  memberNames: string[];
  memberCount: number;
  youOwePaise: number;
  owedToYouPaise: number;
  expensesThisMonth: number;
}

async function computeInvite(db: Db, link: LiveLink, month: string): Promise<InvitePreview> {
  const members = await membersOf(db, link.group_id);
  const whole = await wholeGroup(db, link.group_id);
  const active = members.filter((m) => m.removed_at === null);
  const inviter = members.find((m) => m.id === link.created_by_member_id);
  const mid = link.member_id;
  return {
    groupId: link.group_id,
    groupName: link.name,
    inviterName: inviter ? memberName(inviter) : "",
    memberNames: active.filter((m) => m.id !== mid).map((m) => firstWord(memberName(m))),
    memberCount: active.length,
    youOwePaise: whole.transfers.filter((t) => t.fromMemberId === mid).reduce((s, t) => s + t.amountPaise, 0),
    owedToYouPaise: whole.transfers.filter((t) => t.toMemberId === mid).reduce((s, t) => s + t.amountPaise, 0),
    expensesThisMonth: whole.expenses.filter(
      (e) =>
        e.occurred_on.startsWith(month) &&
        (e.paid_by_member_id === mid || (whole.sharesByExpense.get(e.id) ?? []).some((s) => s.member_id === mid)),
    ).length,
  };
}

/**
 * A family invite (4.18): who is in it and nothing about money. Not cached and no whole-group read: the family has at
 * most 2 member rows active. `memberNames` and `memberCount` are the active members on the app other than the
 * placeholder the link is for.
 */
async function computeFamilyInvite(db: Db, link: LiveLink): Promise<InvitePreview> {
  const members = await membersOf(db, link.group_id);
  const inviter = members.find((m) => m.id === link.created_by_member_id);
  const onApp = members.filter((m) => m.removed_at === null && m.user_id !== null && m.id !== link.member_id);
  return {
    groupId: link.group_id,
    groupName: link.name,
    inviterName: inviter ? memberName(inviter) : "",
    memberNames: onApp.map((m) => firstWord(memberName(m))),
    memberCount: onApp.length,
    youOwePaise: 0,
    owedToYouPaise: 0,
    expensesThisMonth: 0,
  };
}

export async function getInvite(ctx: Ctx): Promise<Response> {
  const link = await linkFor(ctx, ctx.params.token);
  const family = link.kind === "family";
  const month = indiaMonth(ctx.now);
  const preview = family
    ? await computeFamilyInvite(ctx.db, link)
    : await cached(ctx.db, link.group_id, link.version, `invite:${link.member_id}:${month}`, () =>
        computeInvite(ctx.db, link, month),
      );
  const mine = await ctx.db.first<{ id: string }>(
    "SELECT id FROM members WHERE group_id = ?1 AND user_id = ?2 AND removed_at IS NULL",
    link.group_id,
    me(ctx),
  );
  // kind and alreadyInFamily are added here, never stored in the cache body (4.16).
  return json({
    ...preview,
    kind: link.kind as GroupKind,
    alreadyMember: mine !== null,
    alreadyInFamily: family ? await inAnotherFamily(ctx.db, me(ctx), link.group_id) : false,
  });
}

export async function acceptInvite(ctx: Ctx): Promise<Response> {
  let link: LiveLink;
  try {
    link = await linkFor(ctx, ctx.params.token);
  } catch (err) {
    // Accepting revokes the link; a repeat by the person who accepted still answers 200 (4.3).
    if (!SHARE_TOKEN_RE.test(ctx.params.token)) throw err;
    const done = await acceptedLinkByHash(ctx.db, await sha256Hex(ctx.params.token), me(ctx), ctx.now);
    if (!done) throw err;
    return json({ groupId: done.group_id, memberId: done.member_id, version: done.version });
  }
  const gid = link.group_id;
  const target = await getMember(ctx.db, link.member_id);
  if (!target || target.removed_at !== null) throw linkNotFound();
  if (target.user_id === me(ctx)) return json({ groupId: gid, memberId: target.id, version: link.version });
  if (target.user_id !== null) throw conflict("already_claimed", "Someone else already joined as this person.");
  const mine = await ctx.db.first<{ id: string }>(
    "SELECT id FROM members WHERE group_id = ?1 AND user_id = ?2 AND removed_at IS NULL",
    gid,
    me(ctx),
  );
  if (mine) throw conflict("already_member", "Already a member of the group.");
  const family = link.kind === "family";
  // One live family per user (4.18): checked here, and again by the guard inside the batch.
  if (family && (await inAnotherFamily(ctx.db, me(ctx), gid))) throw alreadyInFamily();
  if ((await usersAtGroupCap(ctx.db, [me(ctx)])).length > 0) throw conflict("limit_reached", "Too many groups.");
  let results: D1Result[];
  try {
    results = await runBatch(ctx, "invite.accept", [
      bump(ctx.db, gid, ctx.now),
      // What the owner's undo puts back (4.6): the link and the friend's name and phone as they were. SQLite reads
      // `name` and `phone` on the right-hand side from the row before this update.
      ctx.db.stmt(
        `UPDATE members SET user_id = ?3, joined_at = ?4, join_link_id = ?5, join_prev_name = name,
           join_prev_phone = phone, phone = NULL, version = ${V1}
         WHERE id = ?2 AND group_id = ?1 AND user_id IS NULL AND removed_at IS NULL`,
        gid,
        target.id,
        me(ctx),
        ctx.now,
        link.id,
      ),
      ctx.db.stmt("UPDATE links SET revoked_at = ?2 WHERE member_id = ?1 AND revoked_at IS NULL", target.id, ctx.now),
      guard(ctx.db, {
        gid,
        kind: "member",
        op: "join",
        entityId: target.id,
        actor: target.id,
        now: ctx.now,
        rule: { sql: family ? `user_id = ?7 AND ${NOT_IN_ANOTHER_FAMILY("?7")}` : "user_id = ?7", params: [me(ctx)] },
      }),
      ...keyStmt(ctx, 200, target.id),
    ]);
  } catch (err) {
    if (constraintOn(err, "members.group_id, members.user_id")) throw conflict("already_member", "Already a member of the group.");
    if (guardFailed(err)) {
      const now = await getMember(ctx.db, target.id);
      if (now?.user_id === me(ctx)) return json({ groupId: gid, memberId: target.id, version: now.version });
      if (now?.user_id) throw conflict("already_claimed", "Someone else already joined as this person.");
      if (family && (await inAnotherFamily(ctx.db, me(ctx), gid))) throw alreadyInFamily();
      throw linkNotFound();
    }
    throw err;
  }
  const version = guardVersion(results, 3);
  const everyone = (await membersOf(ctx.db, gid)).filter((m) => m.user_id && m.removed_at === null).map((m) => m.user_id!);
  // "Priya joined Flat 302" is shown to every member (9.7): HIGH for all; the joiner's other phones get "groups".
  after(ctx, { gid, version, high: everyone, groupsPush: [me(ctx)] });
  return json({ groupId: gid, memberId: target.id, version });
}

export async function replayAccept(ctx: Ctx, entityId: string | null, status: number): Promise<Response> {
  const m = await getMember(ctx.db, entityId!);
  if (!m) throw notFound();
  return json({ groupId: m.group_id, memberId: m.id, version: m.version }, status);
}

// ---------- GET /v1/links/{token} (public) ----------

interface LinkPage {
  groupName: string | null;
  senderName: string;
  friendName: string;
  owes: { toName: string; amountPaise: number; upiId: string | null }[];
  owed: { fromName: string; amountPaise: number }[];
  expenses: {
    description: string;
    category: string;
    occurredOn: string;
    amountPaise: number;
    yourSharePaise: number;
    splitMethod: string;
    peopleCount: number;
    paidByName: string;
  }[];
  expensesTruncated: boolean;
}

/** Never included: phone numbers, other members' shares, member or group ids, notes, user ids. */
async function computeLinkPage(db: Db, link: LiveLink): Promise<LinkPage> {
  const members = await membersOf(db, link.group_id);
  const whole = await wholeGroup(db, link.group_id);
  const byId = new Map<string, MemberRow>(members.map((m) => [m.id, m]));
  const nameOf = (id: string) => {
    const m = byId.get(id);
    return m ? memberName(m) : "";
  };
  const upiOf = (id: string) => {
    const m = byId.get(id);
    return m && m.user_id !== null && m.removed_at === null ? m.u_upi : null;
  };
  const fid = link.member_id;
  const mine = whole.expenses
    .map((e) => {
      const shares = whole.sharesByExpense.get(e.id) ?? [];
      const my = shares.find((s) => s.member_id === fid)?.share_paise ?? 0;
      return { e, shares, my };
    })
    .filter((x) => x.e.paid_by_member_id === fid || x.my > 0)
    .sort((a, b) => (a.e.occurred_on < b.e.occurred_on ? 1 : a.e.occurred_on > b.e.occurred_on ? -1 : b.e.created_at - a.e.created_at));
  return {
    groupName: link.name,
    senderName: nameOf(link.created_by_member_id),
    friendName: nameOf(fid),
    owes: whole.transfers
      .filter((t) => t.fromMemberId === fid && t.amountPaise > 0)
      .map((t) => ({ toName: nameOf(t.toMemberId), amountPaise: t.amountPaise, upiId: upiOf(t.toMemberId) })),
    owed: whole.transfers
      .filter((t) => t.toMemberId === fid && t.amountPaise > 0)
      .map((t) => ({ fromName: nameOf(t.fromMemberId), amountPaise: t.amountPaise })),
    expenses: mine.slice(0, 20).map(({ e, shares, my }) => ({
      description: e.description,
      category: e.category,
      occurredOn: e.occurred_on,
      amountPaise: e.amount_paise,
      yourSharePaise: my,
      splitMethod: e.split_method,
      peopleCount: shares.length,
      paidByName: nameOf(e.paid_by_member_id),
    })),
    expensesTruncated: mine.length > 20,
  };
}

/**
 * A family invite on the friends' page (4.18): who sent it (first name) and the family's name, nothing else (no
 * expenses, amounts, other names or ids).
 */
interface FamilyLinkPage {
  family: true;
  familyName: string;
  senderName: string;
}

/**
 * Served from group_cache key link:<linkId>: a hit reads the link, its group and the cache row. A family link is not
 * cached: it reads one member row, the sender's.
 */
export async function linkPageData(db: Db, token: string, now: number): Promise<LinkPage | FamilyLinkPage | null> {
  if (!SHARE_TOKEN_RE.test(token)) return null;
  const link = await liveLinkByHash(db, await sha256Hex(token), now);
  if (!link) return null;
  if (link.kind === "family") {
    const sender = await getMember(db, link.created_by_member_id);
    // The sender's first name only, as the page says it ("Arjun invited you to Home", 06-family/28).
    return { family: true, familyName: link.name ?? "", senderName: sender ? firstWord(memberName(sender)) : "" };
  }
  return cached(db, link.group_id, link.version, `link:${link.id}`, () => computeLinkPage(db, link));
}

export async function publicLink(ctx: Ctx): Promise<Response> {
  const page = await linkPageData(ctx.db, ctx.params.token, ctx.now);
  if (!page) throw linkNotFound(); // revoked, expired, unknown or deleted group: the same answer
  return json(page);
}

/** CORS and privacy headers on every /v1/links answer (errors included). */
export function linkHeaders(ctx: { env: { WEB_ORIGIN: string; ENVIRONMENT: string }; req: Request }): Record<string, string> {
  const origin = ctx.req.headers.get("Origin");
  const allowed = [ctx.env.WEB_ORIGIN];
  if (ctx.env.ENVIRONMENT === "development") allowed.push("http://localhost:8788");
  return {
    "Access-Control-Allow-Origin": origin && allowed.includes(origin) ? origin : ctx.env.WEB_ORIGIN,
    Vary: "Origin",
    "X-Robots-Tag": "noindex, nofollow",
    "Referrer-Policy": "no-referrer",
    "Cache-Control": "no-store",
  };
}

/** OPTIONS preflight: answered for GET only. */
export function preflight(ctx: Ctx): Response {
  if (ctx.req.headers.get("Access-Control-Request-Method") !== "GET") {
    return new Response(null, { status: 405, headers: { Allow: "GET", "Cache-Control": "no-store" } });
  }
  return new Response(null, {
    status: 204,
    headers: { ...linkHeaders(ctx), "Access-Control-Allow-Methods": "GET", "Access-Control-Max-Age": "600" },
  });
}

// ---------- GET /v1/groups/{gid}/live ----------

export async function live(ctx: Ctx): Promise<Response> {
  const gid = ctx.params.gid;
  await requireMember(ctx, gid);
  if (ctx.req.headers.get("Upgrade")?.toLowerCase() !== "websocket") throw badRequest("Upgrade", "Expected a WebSocket upgrade.");
  const stub = ctx.env.GROUP_ROOM.get(ctx.env.GROUP_ROOM.idFromName(gid));
  return stub.fetch("https://room/connect", {
    headers: {
      Upgrade: "websocket",
      "X-User-Id": me(ctx),
      "X-Session-Id": ctx.user!.sid,
      "X-Token-Expires-At": String(ctx.user!.exp),
      "X-Installation-Id": ctx.installationId ?? "",
    },
  });
}
