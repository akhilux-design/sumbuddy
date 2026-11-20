// Me and devices (docs/server.md 4.5).
import { keyStmt, me, type Ctx } from "../context";
import { deleteAccount, deleteDevice, getUser, putDevice, updateProfile } from "../db/accounts";
import { lostGroups, myGroups } from "../db/groups";
import { userJson } from "../db/shapes";
import { json, noContent, unauthorized } from "../http";
import { testHooks } from "../testing";
import * as v from "../validate";

async function currentUser(ctx: Ctx) {
  const user = await getUser(ctx.db, me(ctx));
  if (!user) throw unauthorized();
  return user;
}

export async function getMe(ctx: Ctx): Promise<Response> {
  return json(userJson(await currentUser(ctx)));
}

/**
 * Name, phone or UPI ID. A change bumps every live group the user is active in, in one batch of set-based
 * statements (no per-group batch, no room notify, no push: other phones see it on their next sync).
 */
export async function patchMe(ctx: Ctx): Promise<Response> {
  const body = v.object(ctx.body);
  const user = await currentUser(ctx);
  const next = {
    name: body.name === undefined ? user.name : v.text(body.name, "name", 1, 60),
    phone: body.phone === undefined ? user.phone : v.optionalPhone(body.phone, "phone"),
    upiId: body.upiId === undefined ? user.upi_id : body.upiId === null ? null : v.upiId(body.upiId, "upiId"),
  };
  if (next.name === user.name && next.phone === user.phone && next.upiId === user.upi_id) {
    return json(userJson(user)); // nothing changed: no version bump anywhere
  }
  if (testHooks.beforeBatch) await testHooks.beforeBatch("profile");
  await updateProfile(ctx.db, me(ctx), next, ctx.now, keyStmt(ctx, 200, me(ctx)));
  return json({ id: user.id, name: next.name, phone: next.phone, upiId: next.upiId });
}

export async function deleteMe(ctx: Ctx): Promise<Response> {
  await deleteAccount(ctx.db, me(ctx), ctx.now);
  return noContent();
}

/**
 * The groups the caller is active in, and those lost in the last 90 days with why (`lost`): the phone forgets a
 * group's splits only when the person's join was undone (docs/server.md 9.4).
 */
export async function getMyGroups(ctx: Ctx): Promise<Response> {
  const rows = await myGroups(ctx.db, me(ctx));
  const lost = await lostGroups(ctx.db, me(ctx), ctx.now);
  return json({
    groups: rows.map((g) => ({
      id: g.id,
      kind: g.kind,
      name: g.name,
      version: g.version,
      myMemberId: g.my_member_id,
      ownerUserId: g.owner_user_id,
    })),
    lost: lost.map((l) => ({ id: l.group_id, reason: l.reason, at: l.at })),
    serverTime: ctx.now,
  });
}

export async function putDeviceRoute(ctx: Ctx): Promise<Response> {
  const body = v.object(ctx.body);
  const token = v.text(body.fcmToken, "fcmToken", 1, 4096);
  await putDevice(ctx.db, me(ctx), ctx.params.installationId, token, ctx.now);
  return noContent();
}

export async function deleteDeviceRoute(ctx: Ctx): Promise<Response> {
  await deleteDevice(ctx.db, me(ctx), ctx.params.installationId);
  return noContent();
}
