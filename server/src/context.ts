import { constraintOn, type Db } from "./db/db";
import { groupAccess, lostReason, type Access } from "./db/groups";
import type { IdemWriter } from "./db/mutation";
import type { MemberView } from "./db/shapes";
import type { Env } from "./env";
import { groupDeleted, notMember } from "./http";
import { fanout, type Fanout } from "./notify";
import { testHooks } from "./testing";

export interface Ctx {
  env: Env;
  req: Request;
  url: URL;
  params: Record<string, string>;
  body: unknown;
  now: number;
  db: Db;
  exec: ExecutionContext;
  /** Set on signed-in routes: user id, session id and the access token's expiry (epoch millis). */
  user: { id: string; sid: string; exp: number } | null;
  /** X-Installation-Id when it is a UUID. */
  installationId: string | null;
  /** Set on routes that require an Idempotency-Key. */
  idem: IdemWriter | null;
  ip: string;
}

export interface Member extends Access {
  myMemberId: string;
}

export function me(ctx: Ctx): string {
  return ctx.user!.id;
}

/** The caller reading members of a group with this owner (memberJson's `canUndo`, 4.6). */
export function memberView(ctx: Ctx, group: { owner_user_id: string | null }): MemberView {
  return { now: ctx.now, ownerUserId: group.owner_user_id, viewerUserId: me(ctx) };
}

/**
 * docs/server.md 4.2: the caller must be an active member of a live group. A caller who is not gets `403 not_member`
 * with the `reason` they lost it for, when the server knows (4.1; one more query, on that answer only).
 */
export async function requireMember(ctx: Ctx, gid: string): Promise<Member> {
  const access = await groupAccess(ctx.db, gid, me(ctx));
  if (!access || access.myMemberId === null) throw notMember(await lostReason(ctx.db, gid, me(ctx), ctx.now));
  if (access.group.deleted_at !== null) throw groupDeleted();
  return access as Member;
}

/** Thrown when two copies of one request raced and the other wrote the Idempotency-Key first. */
export class IdempotencyRace extends Error {}

/** Runs a mutation batch (after the test hook). The idempotency key statement is expected last. */
export async function runBatch(ctx: Ctx, label: string, stmts: D1PreparedStatement[]): Promise<D1Result[]> {
  if (testHooks.beforeBatch) await testHooks.beforeBatch(label);
  try {
    return await ctx.db.batch(stmts);
  } catch (err) {
    if (constraintOn(err, "idempotency_keys")) throw new IdempotencyRace();
    throw err;
  }
}

/** The key statement, or nothing on routes without one. */
export function keyStmt(ctx: Ctx, status: number, entityId: string | null): D1PreparedStatement[] {
  return ctx.idem ? [ctx.idem.stmt(status, entityId)] : [];
}

/** Version returned by the guard's RETURNING clause. */
export function guardVersion(results: D1Result[], index: number): number {
  return (results[index].results as { version: number }[])[0].version;
}

/** Room notify and push, after the answer (ctx.waitUntil). */
export function after(ctx: Ctx, f: Omit<Fanout, "callerUserId" | "callerInstallationId">): void {
  ctx.exec.waitUntil(fanout(ctx.env, ctx.db, { ...f, callerUserId: me(ctx), callerInstallationId: ctx.installationId }));
}
