// 0003_family.sql on a populated database (docs/family.md 2, docs/server.md 3): groups is rebuilt to let kind be
// 'family'. Every row of every table, the columns, the foreign keys and both partial indexes must come through as they
// were. Runs on its own database (MIGRATION_DB), with 0001 and 0002 applied and filled before 0003.
import { applyD1Migrations } from "cloudflare:test";
import { env } from "cloudflare:workers";
import { describe, expect, it } from "vitest";

const db = env.MIGRATION_DB;
const DAY = 86_400_000;

const TABLES: Record<string, string> = {
  users: "id",
  sessions: "id",
  devices: "installation_id",
  groups: "id",
  members: "id",
  expenses: "id",
  expense_shares: "expense_id, member_id",
  settlements: "id",
  changes: "group_id, version",
  links: "id",
  group_cache: "group_id, cache_key",
  idempotency_keys: "user_id, key",
  access_ended: "user_id, group_id",
};

async function all(sql: string, ...params: unknown[]): Promise<Record<string, unknown>[]> {
  return (await db.prepare(sql).bind(...params).all<Record<string, unknown>>()).results;
}

async function snapshot() {
  const out: Record<string, unknown> = {};
  for (const [table, order] of Object.entries(TABLES)) out[table] = await all(`SELECT * FROM ${table} ORDER BY ${order}`);
  return out;
}

/** The schema the rebuild must keep: columns, foreign keys and the indexes of groups, and every child's foreign keys. */
async function shape() {
  return {
    columns: await all("PRAGMA table_info(groups)"),
    foreignKeys: await all("PRAGMA foreign_key_list(groups)"),
    indexes: await all("SELECT name, sql FROM sqlite_master WHERE type = 'index' AND tbl_name = 'groups' ORDER BY name"),
    children: {
      members: await all("PRAGMA foreign_key_list(members)"),
      expenses: await all("PRAGMA foreign_key_list(expenses)"),
      settlements: await all("PRAGMA foreign_key_list(settlements)"),
      links: await all("PRAGMA foreign_key_list(links)"),
    },
  };
}

/** Rows of every kind 0001 and 0002 hold, with ids that are easy to read in a failure. */
async function fill() {
  const t = 1_763_000_000_000;
  const id = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, "0")}`;
  const [arjun, priya, rahul] = [id(1), id(2), id(3)];
  const [flat, direct, gone, orphan] = [id(10), id(11), id(12), id(13)];
  const m = { arjunFlat: id(20), priyaFlat: id(21), kiranFlat: id(22), rahulFlat: id(23), arjunDirect: id(24), friendDirect: id(25), arjunGone: id(26), arjunOrphan: id(27) };
  const [e1, e2, e3, e4] = [id(30), id(31), id(32), id(33)];
  const [s1, s2, s3] = [id(40), id(41), id(42)];
  const [l1, l2, l3] = [id(50), id(51), id(52)];
  const stmts = [
    db.prepare("INSERT INTO users (id, google_sub, name, phone, upi_id, created_at, updated_at) VALUES (?, 'g-1', 'Arjun Mehta', '+919000000007', 'arjun@okhdfc', ?, ?)").bind(arjun, t, t),
    db.prepare("INSERT INTO users (id, dev_key, name, created_at, updated_at) VALUES (?, 'priya', 'Priya Nair', ?, ?)").bind(priya, t, t),
    db.prepare("INSERT INTO users (id, google_sub, name, created_at, updated_at) VALUES (?, 'g-3', 'Rahul Shah', ?, ?)").bind(rahul, t, t + 1),
    db.prepare("INSERT INTO sessions (id, user_id, refresh_hash, prev_refresh_hash, rotated_at, nonce_hash, created_at, expires_at, revoked_at) VALUES (?, ?, 'r1', 'r0', ?, 'n1', ?, ?, NULL)").bind(id(60), arjun, t, t, t + 60 * DAY),
    db.prepare("INSERT INTO sessions (id, user_id, refresh_hash, created_at, expires_at, revoked_at) VALUES (?, ?, 'r2', ?, ?, ?)").bind(id(61), priya, t, t + DAY, t + 5),
    db.prepare("INSERT INTO devices (installation_id, user_id, fcm_token, updated_at) VALUES (?, ?, 'fcm-1', ?)").bind(id(70), arjun, t),

    // groups: a live group, a direct split (no name), a deleted and purged group, a group whose owner is gone.
    db.prepare("INSERT INTO groups (id, kind, name, owner_user_id, version, created_at, updated_at) VALUES (?, 'group', 'Flat 302', ?, 9, ?, ?)").bind(flat, arjun, t, t + 9),
    db.prepare("INSERT INTO groups (id, kind, name, owner_user_id, version, created_at, updated_at) VALUES (?, 'direct', NULL, ?, 3, ?, ?)").bind(direct, arjun, t, t + 3),
    db.prepare("INSERT INTO groups (id, kind, name, owner_user_id, version, created_at, updated_at, deleted_at, purged_at) VALUES (?, 'group', 'Goa', ?, 4, ?, ?, ?, ?)").bind(gone, arjun, t, t + 4, t + 5, t + 6),
    db.prepare("INSERT INTO groups (id, kind, name, owner_user_id, version, created_at, updated_at, deleted_at) VALUES (?, 'group', 'Old', NULL, 2, ?, ?, ?)").bind(orphan, t, t + 2, t + 7),

    // members: on the app, joined by link (0002 columns), a friend without the app, removed, reminded.
    db.prepare("INSERT INTO members (id, group_id, user_id, name, phone, added_by_member_id, joined_at, last_reminded_at, version, created_at) VALUES (?, ?, ?, 'Arjun Mehta', NULL, NULL, ?, NULL, 1, ?)").bind(m.arjunFlat, flat, arjun, t, t),
    db.prepare("INSERT INTO members (id, group_id, user_id, name, phone, added_by_member_id, joined_at, last_reminded_at, version, created_at, join_link_id, join_prev_name, join_prev_phone) VALUES (?, ?, ?, 'Priya', NULL, ?, ?, ?, 6, ?, ?, 'Priya N', '+919000000001')").bind(m.priyaFlat, flat, priya, m.arjunFlat, t + 6, t + 2, t, l1),
    db.prepare("INSERT INTO members (id, group_id, user_id, name, phone, added_by_member_id, version, created_at) VALUES (?, ?, NULL, 'Kiran', '+919800000001', ?, 1, ?)").bind(m.kiranFlat, flat, m.arjunFlat, t),
    db.prepare("INSERT INTO members (id, group_id, user_id, name, added_by_member_id, joined_at, version, created_at, removed_at) VALUES (?, ?, ?, 'Rahul Shah', ?, ?, 8, ?, ?)").bind(m.rahulFlat, flat, rahul, m.arjunFlat, t, t, t + 8),
    db.prepare("INSERT INTO members (id, group_id, user_id, name, joined_at, version, created_at) VALUES (?, ?, ?, 'Arjun Mehta', ?, 1, ?)").bind(m.arjunDirect, direct, arjun, t, t),
    db.prepare("INSERT INTO members (id, group_id, user_id, name, version, created_at) VALUES (?, ?, NULL, 'Asha', 1, ?)").bind(m.friendDirect, direct, t),
    db.prepare("INSERT INTO members (id, group_id, user_id, name, joined_at, version, created_at) VALUES (?, ?, ?, 'Arjun Mehta', ?, 1, ?)").bind(m.arjunGone, gone, arjun, t, t),
    db.prepare("INSERT INTO members (id, group_id, user_id, name, joined_at, version, created_at, removed_at) VALUES (?, ?, NULL, 'Former member', NULL, 2, ?, ?)").bind(m.arjunOrphan, orphan, t, t + 2),

    // expenses with shares, one deleted (no shares), one in the direct split, one in the deleted group.
    db.prepare("INSERT INTO expenses (id, group_id, description, category, amount_paise, occurred_on, paid_by_member_id, split_method, auto, created_by_member_id, updated_by_member_id, version, created_at, updated_at) VALUES (?, ?, 'Swiggy', 'FOOD', 45000, '2026-11-16', ?, 'EQUAL', 0, ?, ?, 2, ?, ?)").bind(e1, flat, m.arjunFlat, m.arjunFlat, m.arjunFlat, t, t),
    db.prepare("INSERT INTO expenses (id, group_id, description, category, amount_paise, occurred_on, paid_by_member_id, split_method, auto, created_by_member_id, updated_by_member_id, version, created_at, updated_at, deleted_at) VALUES (?, ?, 'Rent', 'RENT_AND_BILLS', 2400000, '2026-11-01', ?, 'SHARES', 1, ?, ?, 7, ?, ?, ?)").bind(e2, flat, m.priyaFlat, m.priyaFlat, m.arjunFlat, t, t + 7, t + 7),
    db.prepare("INSERT INTO expenses (id, group_id, description, category, amount_paise, occurred_on, paid_by_member_id, split_method, created_by_member_id, updated_by_member_id, version, created_at, updated_at) VALUES (?, ?, 'Movie', 'FUN', 40000, '2026-11-15', ?, 'AMOUNTS', ?, ?, 2, ?, ?)").bind(e3, direct, m.arjunDirect, m.arjunDirect, m.arjunDirect, t, t),
    db.prepare("INSERT INTO expenses (id, group_id, description, category, amount_paise, occurred_on, paid_by_member_id, split_method, created_by_member_id, updated_by_member_id, version, created_at, updated_at) VALUES (?, ?, 'Villa', 'TRAVEL', 90000, '2025-12-28', ?, 'PERCENT', ?, ?, 2, ?, ?)").bind(e4, gone, m.arjunGone, m.arjunGone, m.arjunGone, t, t),
    db.prepare("INSERT INTO expense_shares (expense_id, member_id, share_paise, input) VALUES (?, ?, 15000, NULL), (?, ?, 15000, NULL), (?, ?, 15000, NULL)").bind(e1, m.arjunFlat, e1, m.priyaFlat, e1, m.kiranFlat),
    db.prepare("INSERT INTO expense_shares (expense_id, member_id, share_paise, input) VALUES (?, ?, 25000, 25000), (?, ?, 15000, 15000)").bind(e3, m.arjunDirect, e3, m.friendDirect),
    db.prepare("INSERT INTO expense_shares (expense_id, member_id, share_paise, input) VALUES (?, ?, 90000, 10000)").bind(e4, m.arjunGone),

    // settlements: confirmed from SMS, unconfirmed, deleted.
    db.prepare("INSERT INTO settlements (id, group_id, from_member_id, to_member_id, amount_paise, method, matched_from_sms, occurred_on, recorded_by_member_id, confirmed_at, version, created_at) VALUES (?, ?, ?, ?, 15000, 'UPI', 1, '2026-11-17', ?, ?, 4, ?)").bind(s1, flat, m.priyaFlat, m.arjunFlat, m.arjunFlat, t + 4, t + 4),
    db.prepare("INSERT INTO settlements (id, group_id, from_member_id, to_member_id, amount_paise, method, occurred_on, recorded_by_member_id, version, created_at) VALUES (?, ?, ?, ?, 5000, 'CASH', '2026-11-17', ?, 5, ?)").bind(s2, flat, m.kiranFlat, m.arjunFlat, m.arjunFlat, t + 5),
    db.prepare("INSERT INTO settlements (id, group_id, from_member_id, to_member_id, amount_paise, method, occurred_on, recorded_by_member_id, version, created_at, deleted_at) VALUES (?, ?, ?, ?, 15000, 'OTHER', '2026-11-16', ?, 3, ?, ?)").bind(s3, direct, m.friendDirect, m.arjunDirect, m.arjunDirect, t + 3, t + 3),

    // change log of each group.
    ...[1, 2, 3, 4, 5, 6, 7, 8, 9].map((v) =>
      db.prepare("INSERT INTO changes (group_id, version, kind, op, entity_id, actor_member_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)").bind(
        flat,
        v,
        ["group", "expense", "member", "settlement", "settlement", "member", "expense", "member", "group"][v - 1],
        ["create", "create", "create", "create", "confirm", "join", "delete", "leave", "update"][v - 1],
        v === 1 || v === 9 ? flat : m.arjunFlat,
        m.arjunFlat,
        t + v,
      ),
    ),
    db.prepare("INSERT INTO changes (group_id, version, kind, op, entity_id, actor_member_id, created_at) VALUES (?, 1, 'group', 'create', ?, ?, ?), (?, 4, 'group', 'delete', ?, NULL, ?)").bind(direct, direct, m.arjunDirect, t, gone, gone, t + 5),

    // links: live, revoked by an accept, expired.
    db.prepare("INSERT INTO links (id, token_hash, group_id, member_id, created_by_member_id, created_at, expires_at, revoked_at) VALUES (?, 'h1', ?, ?, ?, ?, ?, ?)").bind(l1, flat, m.priyaFlat, m.arjunFlat, t, t + 90 * DAY, t + 6),
    db.prepare("INSERT INTO links (id, token_hash, group_id, member_id, created_by_member_id, created_at, expires_at) VALUES (?, 'h2', ?, ?, ?, ?, ?)").bind(l2, flat, m.kiranFlat, m.arjunFlat, t, t + 90 * DAY),
    db.prepare("INSERT INTO links (id, token_hash, group_id, member_id, created_by_member_id, created_at, expires_at) VALUES (?, 'h3', ?, ?, ?, ?, ?)").bind(l3, direct, m.friendDirect, m.arjunDirect, t - 100 * DAY, t - 10 * DAY),

    db.prepare("INSERT INTO group_cache (group_id, cache_key, version, body) VALUES (?, 'balances', 9, '{\"version\":9}'), (?, ?, 9, '{}')").bind(flat, flat, `link:${l2}`),
    db.prepare("INSERT INTO idempotency_keys (user_id, key, request_hash, status, entity_id, created_at) VALUES (?, ?, 'abc', 201, ?, ?)").bind(arjun, id(80), flat, t),
    db.prepare("INSERT INTO access_ended (user_id, group_id, reason, at) VALUES (?, ?, 'left', ?), (?, ?, 'deleted', ?)").bind(rahul, flat, t + 8, arjun, gone, t + 5),
  ];
  await db.batch(stmts);
  return { arjun, flat, direct };
}

describe("0003_family.sql on a populated database", () => {
  it("keeps every row, column, foreign key and index; lets kind be family; foreign keys still hold", async () => {
    const migrations = env.TEST_MIGRATIONS;
    expect(migrations.map((x) => x.name).slice(0, 3)).toEqual(["0001_init.sql", "0002_undo_join.sql", "0003_family.sql"]);
    await applyD1Migrations(db, migrations.slice(0, 2));
    const { arjun } = await fill();
    const before = await snapshot();
    const schemaBefore = await shape();
    for (const rowsOf of Object.values(before)) expect((rowsOf as unknown[]).length).toBeGreaterThan(0);
    // 0001 refuses a family.
    await expect(
      db.prepare("INSERT INTO groups (id, kind, name, version, created_at, updated_at) VALUES ('f0', 'family', 'Home', 1, 1, 1)").run(),
    ).rejects.toThrow(/CHECK constraint failed/);

    await applyD1Migrations(db, migrations);
    expect((await all("SELECT name FROM d1_migrations ORDER BY id")).map((r) => r.name)).toEqual(migrations.map((x) => x.name));

    expect(await snapshot()).toEqual(before);
    const schemaAfter = await shape();
    expect(schemaAfter.columns).toEqual(schemaBefore.columns);
    expect(schemaAfter.foreignKeys).toEqual(schemaBefore.foreignKeys);
    expect(schemaAfter.indexes).toEqual(schemaBefore.indexes);
    expect(schemaAfter.indexes.map((i) => i.name)).toEqual(expect.arrayContaining(["groups_owner", "groups_to_purge"]));
    expect(schemaAfter.children).toEqual(schemaBefore.children);
    for (const fks of Object.values(schemaAfter.children)) expect(fks.some((fk) => fk.table === "groups")).toBe(true);
    expect(await all("SELECT name FROM sqlite_master WHERE name = 'groups_new'")).toEqual([]);
    expect(await all("PRAGMA foreign_key_check")).toEqual([]);
    // The planner still finds the partial indexes.
    const plan = JSON.stringify(await all("EXPLAIN QUERY PLAN SELECT id FROM groups WHERE deleted_at IS NOT NULL AND purged_at IS NULL ORDER BY deleted_at, id"));
    expect(plan).toContain("groups_to_purge");

    // A family row goes in; a family without a name, a direct split with one, and an unknown kind do not.
    const now = Date.now();
    await db.prepare("INSERT INTO groups (id, kind, name, owner_user_id, version, created_at, updated_at) VALUES ('fam-1', 'family', 'Home', ?, 1, ?, ?)").bind(arjun, now, now).run();
    await db.prepare("INSERT INTO members (id, group_id, user_id, name, version, created_at) VALUES ('fam-m1', 'fam-1', ?, 'Arjun', 1, ?)").bind(arjun, now).run();
    expect(await all("SELECT kind, name FROM groups WHERE id = 'fam-1'")).toEqual([{ kind: "family", name: "Home" }]);
    for (const [gid, kind, name] of [["fam-2", "family", null], ["dir-2", "direct", "Named"], ["grp-2", "group", null], ["pool-1", "pool", "Pool"]] as const) {
      await expect(
        db.prepare("INSERT INTO groups (id, kind, name, version, created_at, updated_at) VALUES (?, ?, ?, 1, ?, ?)").bind(gid, kind, name, now, now).run(),
        `${kind} ${name}`,
      ).rejects.toThrow(/CHECK constraint failed/);
    }
    // Foreign keys are still enforced on the rebuilt table, both ways.
    await expect(
      db.prepare("INSERT INTO members (id, group_id, name, version, created_at) VALUES ('m-x', 'no-such-group', 'X', 1, ?)").bind(now).run(),
    ).rejects.toThrow(/FOREIGN KEY constraint failed/);
    await expect(
      db.prepare("INSERT INTO groups (id, kind, name, owner_user_id, version, created_at, updated_at) VALUES ('grp-3', 'group', 'G', 'no-such-user', 1, ?, ?)").bind(now, now).run(),
    ).rejects.toThrow(/FOREIGN KEY constraint failed/);
    await expect(db.prepare("DELETE FROM groups WHERE id = 'fam-1'").run()).rejects.toThrow(/FOREIGN KEY constraint failed/);
    // ON DELETE SET NULL on the owner survived the rebuild.
    await db.prepare("DELETE FROM users WHERE id = ?").bind(arjun).run();
    expect(await all("SELECT owner_user_id FROM groups WHERE owner_user_id IS NOT NULL")).toEqual([]);
  });

  it("is safe to run on an empty database, as on a new install", async () => {
    // The main test database (env.DB) got every migration before any test ran (test/setup.ts).
    const kinds = await env.DB.prepare("SELECT sql FROM sqlite_master WHERE name = 'groups'").first<{ sql: string }>();
    expect(kinds!.sql).toContain("'family'");
    expect((await env.DB.prepare("SELECT COUNT(*) AS n FROM sqlite_master WHERE name IN ('groups_owner', 'groups_to_purge')").first<{ n: number }>())!.n).toBe(2);
  });
});
