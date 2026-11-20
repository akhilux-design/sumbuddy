// SumBuddy API Worker: splitting only (docs/server.md). SMS text and personal expenses never reach it.
import { Db } from "./db/db";
import { purgeDeletedGroups, purgeExpired } from "./db/cron";
import type { Env } from "./env";
import { logError } from "./log";
import { handle } from "./router";

export { GroupRoom } from "./room";

export default {
  fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    return handle(request, env, ctx);
  },

  /** Daily at 00:30 UTC (06:00 India): expired keys, sessions and links, then tombstoned groups (4.13). */
  async scheduled(_controller: ScheduledController, env: Env, _ctx: ExecutionContext): Promise<void> {
    const db = new Db(env.DB);
    const now = Date.now();
    try {
      await purgeExpired(db, now);
      await purgeDeletedGroups(db, now);
    } catch (err) {
      logError("cron", err);
    }
  },
} satisfies ExportedHandler<Env>;
