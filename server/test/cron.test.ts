// Daily cron (docs/server.md 4.13).
import { createExecutionContext, createScheduledController, waitOnExecutionContext } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import worker from "../src/index";
import { Db } from "../src/db/db";
import { purgeDeletedGroups, purgeExpired } from "../src/db/cron";
import { call, createGroup, env, rows, shareToken, signIn, uuid } from "./helpers";

const DAY = 86_400_000;

describe("cron", () => {
  it("purges idempotency keys over 7 days, old sessions and old links with their cache rows", async () => {
    const now = Date.now();
    const user = uuid();
    await env.DB.batch([
      env.DB.prepare("INSERT INTO idempotency_keys VALUES (?, ?, 'h', 200, NULL, ?)").bind(user, "old", now - 8 * DAY),
      env.DB.prepare("INSERT INTO idempotency_keys VALUES (?, ?, 'h', 200, NULL, ?)").bind(user, "new", now - DAY),
    ]);
    const a = await signIn();
    const g = await createGroup(a, { friends: [{ name: "P" }] });
    const linkId = uuid();
    await call("PUT", `/v1/groups/${g.id}/members/${g.members.P}/links/${linkId}`, { token: a.token, body: { token: "C".repeat(24) } });
    await call("GET", `/v1/links/${"C".repeat(24)}`); // makes the cache row
    expect(await rows("SELECT * FROM group_cache WHERE cache_key = ?", "link:" + linkId)).toHaveLength(1);
    await env.DB.prepare("UPDATE links SET revoked_at = ? WHERE id = ?").bind(now - 31 * DAY, linkId).run();
    await env.DB.prepare("UPDATE sessions SET revoked_at = ? WHERE user_id = ?").bind(now - 31 * DAY, a.id).run();
    await env.DB.prepare("INSERT INTO group_cache VALUES (?, 'invite:x:2026-01', 1, '{}')").bind(g.id).run();

    await purgeExpired(new Db(env.DB), now);
    expect((await rows("SELECT key FROM idempotency_keys WHERE user_id = ?", user)).map((r) => r.key)).toEqual(["new"]);
    expect(await rows("SELECT * FROM links WHERE id = ?", linkId)).toHaveLength(0);
    expect(await rows("SELECT * FROM group_cache WHERE cache_key = ?", "link:" + linkId)).toHaveLength(0);
    expect(await rows("SELECT * FROM group_cache WHERE cache_key = 'invite:x:2026-01'")).toHaveLength(0);
    expect(await rows("SELECT * FROM sessions WHERE user_id = ?", a.id)).toHaveLength(0);
  });

  it("purges a large deleted group in chunks, stopping at its daily row budget", async () => {
    const a = await signIn();
    const g = await createGroup(a, { friends: [{ name: "P" }, { name: "Q" }] });
    const [ma, mp, mq] = [g.members[a.id], g.members.P, g.members.Q];
    // 300 expenses with 3 shares each, written directly (fast), plus their change rows.
    const now = Date.now();
    const stmts: D1PreparedStatement[] = [];
    for (let i = 0; i < 300; i++) {
      const eid = uuid();
      stmts.push(
        env.DB.prepare(
          `INSERT INTO expenses (id, group_id, description, category, amount_paise, occurred_on, paid_by_member_id, split_method,
             created_by_member_id, updated_by_member_id, version, created_at, updated_at) VALUES (?, ?, 'x', 'FOOD', 300, '2026-11-16', ?, 'EQUAL', ?, ?, ?, ?, ?)`,
        ).bind(eid, g.id, ma, ma, ma, i + 2, now, now),
        env.DB.prepare("INSERT INTO expense_shares VALUES (?, ?, 100, NULL), (?, ?, 100, NULL), (?, ?, 100, NULL)").bind(eid, ma, eid, mp, eid, mq),
        env.DB.prepare("INSERT INTO changes VALUES (?, ?, 'expense', 'create', ?, ?, ?)").bind(g.id, i + 2, eid, ma, now),
      );
    }
    await env.DB.batch(stmts);
    await env.DB.prepare("UPDATE groups SET deleted_at = ?, version = 301 WHERE id = ?").bind(now, g.id).run();
    const total = 900 + 300 + 301 + 3; // shares, expenses, changes, members

    const db = new Db(env.DB);
    const first = await purgeDeletedGroups(db, now, 500, 100);
    expect(first).toBe(500);
    expect((await rows("SELECT purged_at FROM groups WHERE id = ?", g.id))[0].purged_at).toBeNull();
    // Children before parents: shares go first.
    expect((await rows("SELECT COUNT(*) AS n FROM expense_shares s JOIN expenses e ON e.id = s.expense_id WHERE e.group_id = ?", g.id))[0].n).toBe(400);

    let deleted = first;
    for (let day = 0; day < 10 && deleted < total; day++) deleted += await purgeDeletedGroups(new Db(env.DB), now, 500, 100);
    expect(deleted).toBe(total);
    await purgeDeletedGroups(new Db(env.DB), now, 500, 100);
    expect((await rows("SELECT purged_at FROM groups WHERE id = ?", g.id))[0].purged_at).not.toBeNull();
    for (const t of ["expenses", "settlements", "links", "changes", "group_cache", "members"]) {
      expect(await rows(`SELECT * FROM ${t} WHERE group_id = ?`, g.id)).toHaveLength(0);
    }
  });

  it("purging a deleted group reads only its own rows, however big the other groups are", async () => {
    // Another group with 2,000 expenses, 6,000 shares and 200 settlements stays.
    const a = await signIn();
    const big = await createGroup(a, { friends: [{ name: "P" }, { name: "Q" }] }, "Big");
    const [ma, mp, mq] = [big.members[a.id], big.members.P, big.members.Q];
    const now = Date.now();
    for (let batch = 0; batch < 4; batch++) {
      const stmts: D1PreparedStatement[] = [];
      for (let i = 0; i < 500; i++) {
        const eid = uuid();
        stmts.push(
          env.DB.prepare(
            `INSERT INTO expenses (id, group_id, description, category, amount_paise, occurred_on, paid_by_member_id, split_method,
               created_by_member_id, updated_by_member_id, version, created_at, updated_at) VALUES (?, ?, 'x', 'FOOD', 300, '2026-11-16', ?, 'EQUAL', ?, ?, 2, ?, ?)`,
          ).bind(eid, big.id, ma, ma, ma, now, now),
          env.DB.prepare("INSERT INTO expense_shares VALUES (?, ?, 100, NULL), (?, ?, 100, NULL), (?, ?, 100, NULL)").bind(eid, ma, eid, mp, eid, mq),
        );
        if (batch === 0 && i < 200) {
          stmts.push(
            env.DB.prepare(
              `INSERT INTO settlements (id, group_id, from_member_id, to_member_id, amount_paise, method, occurred_on,
                 recorded_by_member_id, version, created_at) VALUES (?, ?, ?, ?, 100, 'CASH', '2026-11-16', ?, 2, ?)`,
            ).bind(uuid(), big.id, mp, ma, ma, now),
          );
        }
      }
      await env.DB.batch(stmts);
    }
    // The deleted group: 3 members, 2 links, a few changes, no expenses.
    const gone = await createGroup(a, { friends: [{ name: "R" }, { name: "S" }] }, "Gone");
    for (const name of ["R", "S"]) {
      await call("PUT", `/v1/groups/${gone.id}/members/${gone.members[name]}/links/${uuid()}`, { token: a.token, body: { token: shareToken() } });
    }
    await env.DB.prepare("UPDATE groups SET deleted_at = ? WHERE id = ?").bind(now, gone.id).run();

    const db = new Db(env.DB);
    const deleted = await purgeDeletedGroups(db, now);
    expect(deleted).toBeGreaterThanOrEqual(3 + 2 + 1);
    expect((await rows("SELECT purged_at FROM groups WHERE id = ?", gone.id))[0].purged_at).not.toBeNull();
    // Without the members_no_fk rule (0001_init.sql) each deleted member row would read every expense, share and
    // settlement of every group: about 25,000 rows here.
    expect(db.rowsRead).toBeLessThan(500);
    expect((await rows("SELECT COUNT(*) AS n FROM expenses WHERE group_id = ?", big.id))[0].n).toBe(2000);
  });

  it("the scheduled handler runs", async () => {
    const ctx = createExecutionContext();
    await worker.scheduled(createScheduledController({ cron: "30 0 * * *" }), env, ctx);
    await waitOnExecutionContext(ctx);
  });
});
