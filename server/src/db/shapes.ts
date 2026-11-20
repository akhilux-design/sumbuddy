// Row types and the JSON shapes of docs/server.md 4.5-4.8. Never returned: emails, Google ids, sessions, devices.
import { DAY } from "../time";

export type GroupKind = "group" | "direct" | "family";

export interface GroupRow {
  id: string;
  kind: GroupKind;
  name: string | null;
  owner_user_id: string | null;
  /** The owner's active member row in the group (a subquery of the group read), or null. */
  owner_member_id: string | null;
  version: number;
  created_at: number;
  deleted_at: number | null;
}

/** SQL for the owner's active member id; `g` must be the groups row. One index read (members_one_per_user). */
export const OWNER_MEMBER_ID = `(SELECT o.id FROM members o
  WHERE o.group_id = g.id AND o.user_id = g.owner_user_id AND o.removed_at IS NULL) AS owner_member_id`;

/** A members row joined with its user (u_* columns are NULL for friends without the app). */
export interface MemberRow {
  id: string;
  group_id: string;
  user_id: string | null;
  name: string;
  phone: string | null;
  joined_at: number | null;
  last_reminded_at: number | null;
  version: number;
  removed_at: number | null;
  /** Set when the person took this place through a friend's link (0002_undo_join.sql). */
  join_link_id: string | null;
  u_name: string | null;
  u_phone: string | null;
  u_upi: string | null;
}

export const MEMBER_COLUMNS = `m.id, m.group_id, m.user_id, m.name, m.phone, m.joined_at, m.last_reminded_at,
  m.version, m.removed_at, m.join_link_id, u.name AS u_name, u.phone AS u_phone, u.upi_id AS u_upi`;
export const MEMBER_FROM = `members m LEFT JOIN users u ON u.id = m.user_id`;

/** How long the group owner may undo a join by link (docs/product.md "Forwarded links"). */
export const UNDO_JOIN_WINDOW_MS = 7 * DAY;

/**
 * When the owner's undo window ends for a person who joined through a friend's link and is still in that place, or
 * null (not a link join, left, removed, or the 7 days are over at `now`).
 */
export function undoJoinUntil(
  m: Pick<MemberRow, "user_id" | "removed_at" | "joined_at" | "join_link_id">,
  now: number,
): number | null {
  if (m.join_link_id === null || m.user_id === null || m.removed_at !== null || m.joined_at === null) return null;
  const until = m.joined_at + UNDO_JOIN_WINDOW_MS;
  return now < until ? until : null;
}

/** Who reads a Member, and when: `canUndo` depends on both (4.6). */
export interface MemberView {
  now: number;
  /** groups.owner_user_id of the member's group. */
  ownerUserId: string | null;
  /** The caller. */
  viewerUserId: string;
}

export interface ExpenseRow {
  id: string;
  group_id: string;
  description: string;
  category: string;
  amount_paise: number;
  currency: string;
  occurred_on: string;
  paid_by_member_id: string;
  split_method: string;
  auto: number;
  created_by_member_id: string;
  updated_by_member_id: string;
  version: number;
  created_at: number;
  updated_at: number;
  deleted_at: number | null;
}

export interface ShareRow {
  expense_id: string;
  member_id: string;
  share_paise: number;
  input: number | null;
}

export interface SettlementRow {
  id: string;
  group_id: string;
  from_member_id: string;
  to_member_id: string;
  amount_paise: number;
  method: string;
  matched_from_sms: number;
  occurred_on: string;
  recorded_by_member_id: string;
  confirmed_at: number | null;
  version: number;
  created_at: number;
  deleted_at: number | null;
}

export interface UserRow {
  id: string;
  name: string;
  phone: string | null;
  upi_id: string | null;
}

export function userJson(u: UserRow) {
  return { id: u.id, name: u.name, phone: u.phone, upiId: u.upi_id };
}

export function groupJson(g: GroupRow) {
  return {
    id: g.id,
    kind: g.kind,
    name: g.name,
    ownerUserId: g.owner_user_id,
    ownerMemberId: g.owner_member_id,
    version: g.version,
    createdAt: g.created_at,
  };
}

/** Name and phone of a member as everyone in the group sees them. */
export function memberName(m: MemberRow): string {
  return m.user_id !== null && m.u_name !== null ? m.u_name : m.name;
}

export function memberJson(m: MemberRow, view: MemberView) {
  const onApp = m.user_id !== null;
  const removed = m.removed_at !== null;
  // Only for a join by link inside the 7 days at the time of the answer. A phone keeps the row it pulled, so it checks
  // `now < undoUntil` and the group's ownerUserId itself (4.6): rows are not sent again when time passes or the owner
  // changes.
  const undoUntil = undoJoinUntil(m, view.now);
  // A user's phone and UPI ID are seen only by people in the user's groups: not once the user has left.
  return {
    id: m.id,
    groupId: m.group_id,
    userId: m.user_id,
    name: memberName(m),
    phone: onApp ? (removed ? null : m.u_phone) : m.phone,
    upiId: onApp && !removed ? m.u_upi : null,
    onApp,
    removed,
    joinedAt: undoUntil === null ? null : m.joined_at,
    undoUntil,
    canUndo: undoUntil !== null && view.ownerUserId === view.viewerUserId && m.user_id !== view.viewerUserId,
    version: m.version,
  };
}

export function expenseJson(e: ExpenseRow, shares: ShareRow[]) {
  if (e.deleted_at !== null) return { id: e.id, groupId: e.group_id, deleted: true, version: e.version };
  return {
    id: e.id,
    groupId: e.group_id,
    description: e.description,
    category: e.category,
    amountPaise: e.amount_paise,
    currency: e.currency,
    occurredOn: e.occurred_on,
    paidByMemberId: e.paid_by_member_id,
    splitMethod: e.split_method,
    auto: e.auto === 1,
    shares: shares
      .filter((s) => s.expense_id === e.id)
      .sort((a, b) => (a.member_id < b.member_id ? -1 : 1))
      .map((s) => ({ memberId: s.member_id, sharePaise: s.share_paise, input: s.input })),
    createdByMemberId: e.created_by_member_id,
    updatedByMemberId: e.updated_by_member_id,
    createdAt: e.created_at,
    updatedAt: e.updated_at,
    deleted: false,
    version: e.version,
  };
}

export function settlementJson(s: SettlementRow) {
  return {
    id: s.id,
    groupId: s.group_id,
    fromMemberId: s.from_member_id,
    toMemberId: s.to_member_id,
    amountPaise: s.amount_paise,
    method: s.method,
    matchedFromSms: s.matched_from_sms === 1,
    occurredOn: s.occurred_on,
    recordedByMemberId: s.recorded_by_member_id,
    confirmedAt: s.confirmed_at,
    createdAt: s.created_at,
    deleted: s.deleted_at !== null,
    version: s.version,
  };
}
