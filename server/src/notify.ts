// After an accepted group mutation (docs/server.md 4.12): tell the room, then push to the devices of the members on
// the app, minus the devices whose user has a socket open on them and minus the caller's own installation.
import type { Db } from "./db/db";
import { deleteDeviceByToken, devicesOf } from "./db/accounts";
import type { Env } from "./env";
import { logError } from "./log";
import { pushConfig, sendPush, type PushMessage } from "./push/fcm";
import type { Connected } from "./room";

export interface Fanout {
  gid: string;
  version: number;
  callerUserId: string;
  callerInstallationId: string | null;
  /** Users whose sockets get {"type":"revoked"} and close 4403. */
  kick?: string[];
  /** Users for whom this change makes a notification (HIGH priority); everyone else NORMAL. */
  high?: string[];
  /** Users who get {"t":"groups"} (their group list changed) instead of "changed". */
  groupsPush?: string[];
  /** Skip the "changed" push to the other members (the group is gone for them: they get "groups"). */
  noChangedPush?: boolean;
}

/** Tells the room; returns the (user, installation) pairs of the sockets it kept open. */
export async function notifyRoom(env: Env, gid: string, version: number, kick: string[] = []): Promise<Connected[]> {
  const stub = env.GROUP_ROOM.get(env.GROUP_ROOM.idFromName(gid));
  const res = await stub.fetch("https://room/notify", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ groupId: gid, version, kick }),
  });
  if (!res.ok) return [];
  return ((await res.json()) as { connected: Connected[] }).connected;
}

const pairKey = (userId: string, installationId: string) => `${userId} ${installationId}`;

export async function fanout(env: Env, db: Db, f: Fanout): Promise<void> {
  try {
    // A device is skipped only when its own user holds the socket: the installation id on a socket is whatever the
    // phone sent, so on its own it could name someone else's phone and silence it.
    const connected = new Set((await notifyRoom(env, f.gid, f.version, f.kick)).map((c) => pairKey(c.userId, c.installationId)));
    const cfg = pushConfig(env);
    if (!cfg) return;
    const active = f.noChangedPush
      ? []
      : (
          await db.all<{ user_id: string }>(
            "SELECT user_id FROM members WHERE group_id = ?1 AND user_id IS NOT NULL AND removed_at IS NULL",
            f.gid,
          )
        ).map((r) => r.user_id);
    const groupsUsers = new Set(f.groupsPush ?? []);
    const changedUsers = active.filter((u) => !groupsUsers.has(u));
    const devices = await devicesOf(db, [...new Set([...changedUsers, ...groupsUsers])]);
    const high = new Set(f.high ?? []);
    const messages: PushMessage[] = [];
    for (const d of devices) {
      if (connected.has(pairKey(d.user_id, d.installation_id))) continue;
      // Only the caller's own installation is skipped: a header naming someone else's must not silence them.
      if (d.installation_id === f.callerInstallationId && d.user_id === f.callerUserId) continue;
      const priority = high.has(d.user_id) ? "HIGH" : "NORMAL";
      if (groupsUsers.has(d.user_id)) {
        messages.push({ token: d.fcm_token, data: { t: "groups" }, priority, collapseKey: "groups" });
      } else {
        messages.push({ token: d.fcm_token, data: { t: "changed", g: f.gid, v: String(f.version) }, priority, collapseKey: f.gid });
      }
    }
    const gone = await sendPush(cfg, messages);
    for (const t of gone) await deleteDeviceByToken(db, t);
  } catch (err) {
    logError("fanout", err);
  }
}

/** Pushes outside a group mutation (new group's members, reminders). */
export async function pushTo(
  env: Env,
  db: Db,
  userIds: string[],
  data: Record<string, string>,
  p: { callerUserId: string; callerInstallationId: string | null; collapseKey?: string },
): Promise<void> {
  try {
    const cfg = pushConfig(env);
    if (!cfg || userIds.length === 0) return;
    const devices = await devicesOf(db, userIds);
    const messages = devices
      .filter((d) => !(d.installation_id === p.callerInstallationId && d.user_id === p.callerUserId))
      .map((d) => ({ token: d.fcm_token, data, priority: "HIGH" as const, collapseKey: p.collapseKey }));
    const gone = await sendPush(cfg, messages);
    for (const t of gone) await deleteDeviceByToken(db, t);
  } catch (err) {
    logError("push", err);
  }
}
