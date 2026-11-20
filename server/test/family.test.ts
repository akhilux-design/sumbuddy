// Family sharing (docs/server.md 4.18, docs/family.md 3 and 4): a group of kind "family" with one owner, at most 2
// active members (the placeholder of an invite included), one live family per user, expenses that are the payer's own
// payment and never split. Pushes are checked in push.test.ts, account deletion in me.test.ts, the pull in sync.test.ts.
import { describe, expect, it } from "vitest";
import { testHooks } from "../src/testing";
import {
  call,
  createGroup,
  env,
  expenseBody,
  putExpense,
  rows,
  shareToken,
  signIn,
  today,
  uuid,
  type Result,
  type TestUser,
} from "./helpers";

const DAY = 86_400_000;

interface Family {
  id: string;
  /** the owner's member id */
  mine: string;
  /** the placeholder's member id (the partner's place once they join) */
  placeholder: string;
  r: Result;
}

async function createFamily(owner: TestUser, name = "Home"): Promise<Family> {
  const id = uuid();
  const mine = uuid();
  const placeholder = uuid();
  const r = await call("POST", "/v1/groups", {
    token: owner.token,
    body: { id, kind: "family", name, myMemberId: mine, members: [{ id: placeholder, name: "Partner" }] },
  });
  if (r.status !== 201) throw new Error("create family failed " + r.status + JSON.stringify(r.body));
  return { id, mine, placeholder, r };
}

async function familyLink(user: TestUser, gid: string, mid: string) {
  const token = shareToken();
  const linkId = uuid();
  const put = await call("PUT", `/v1/groups/${gid}/members/${mid}/links/${linkId}`, { token: user.token, body: { token } });
  return { token, linkId, put };
}

/** `owner` makes a family, `partner` joins it through the placeholder's link. */
async function familyOfTwo(owner: TestUser, partner: TestUser, name = "Home"): Promise<Family> {
  const fam = await createFamily(owner, name);
  const { token, put } = await familyLink(owner, fam.id, fam.placeholder);
  expect(put.status).toBe(201);
  const acc = await call("POST", `/v1/invites/${token}/accept`, { token: partner.token });
  if (acc.status !== 200) throw new Error("accept failed " + acc.status + JSON.stringify(acc.body));
  return fam;
}

/** A family expense: the caller's own payment, one share, theirs, for the full amount. */
function familyExpense(mid: string, amount: number, extra: Record<string, unknown> = {}) {
  return {
    description: "BigBasket",
    category: "GROCERIES",
    amountPaise: amount,
    occurredOn: today(),
    paidByMemberId: mid,
    splitMethod: "EQUAL",
    auto: false,
    shares: [{ memberId: mid, sharePaise: amount, input: null }],
    baseVersion: null,
    ...extra,
  };
}

/** Writes a second live family for `user` straight into the database (as another phone would have). */
async function otherFamilyOf(user: TestUser): Promise<string> {
  const gid = uuid();
  const now = Date.now();
  await env.DB.batch([
    env.DB.prepare("INSERT INTO groups (id, kind, name, owner_user_id, version, created_at, updated_at) VALUES (?, 'family', 'Other', ?, 1, ?, ?)").bind(
      gid,
      user.id,
      now,
      now,
    ),
    env.DB.prepare("INSERT INTO members (id, group_id, user_id, name, joined_at, version, created_at) VALUES (?, ?, ?, 'X', ?, 1, ?)").bind(
      uuid(),
      gid,
      user.id,
      now,
      now,
    ),
  ]);
  return gid;
}

const groupVersion = async (gid: string) => (await rows("SELECT version FROM groups WHERE id = ?", gid))[0].version as number;

describe("create a family (POST /v1/groups, kind family)", () => {
  it("201 with the owner and the placeholder; /me/groups and the pull show it", async () => {
    const a = await signIn("Arjun Mehta");
    const fam = await createFamily(a);
    expect(fam.r.body.group).toEqual({
      id: fam.id,
      kind: "family",
      name: "Home",
      ownerUserId: a.id,
      ownerMemberId: fam.mine,
      version: 1,
      createdAt: expect.any(Number),
    });
    const byId = Object.fromEntries(fam.r.body.members.map((m: any) => [m.id, m]));
    expect(byId[fam.mine]).toMatchObject({ userId: a.id, name: "Arjun Mehta", onApp: true, removed: false, version: 1 });
    expect(byId[fam.placeholder]).toMatchObject({ userId: null, name: "Partner", phone: null, onApp: false, removed: false });
    expect(await rows("SELECT kind, op, entity_id, actor_member_id FROM changes WHERE group_id = ?", fam.id)).toEqual([
      { kind: "group", op: "create", entity_id: fam.id, actor_member_id: fam.mine },
    ]);
    const list = await call("GET", "/v1/me/groups", { token: a.token });
    expect(list.body.groups).toContainEqual({ id: fam.id, kind: "family", name: "Home", version: 1, myMemberId: fam.mine, ownerUserId: a.id });
    const pull = await call("GET", `/v1/groups/${fam.id}/changes?since=0`, { token: a.token });
    expect(pull.status).toBe(200);
    expect(pull.body.group.kind).toBe("family");
    expect(pull.body.members.map((m: any) => m.id).sort()).toEqual([fam.mine, fam.placeholder].sort());

    // The same request again answers 200 with the same content (a lost answer, 4.3).
    const again = await call("POST", "/v1/groups", {
      token: a.token,
      body: { id: fam.id, kind: "family", name: "Home", myMemberId: fam.mine, members: [{ id: fam.placeholder, name: "Partner" }] },
    });
    expect(again.status).toBe(200);
    expect(again.body.group.id).toBe(fam.id);
  });

  it("validates the placeholder and the name", async () => {
    const a = await signIn();
    const body = () => ({ id: uuid(), kind: "family", name: "Home", myMemberId: uuid(), members: [{ id: uuid(), name: "Partner" }] as any[] });
    const b = await signIn();
    const cases: [any, string][] = [
      [{ ...body(), members: [] }, "members"],
      [{ ...body(), members: [{ id: uuid(), name: "Partner" }, { id: uuid(), name: "Kid" }] }, "members"],
      [{ ...body(), members: [{ id: uuid(), userId: b.id }] }, "members[0].userId"],
      [{ ...body(), members: [{ id: uuid(), name: "Partner", phone: "+919000000001" }] }, "members[0].phone"],
      [{ ...body(), members: [{ id: uuid(), name: "" }] }, "members[0].name"],
      [{ ...body(), name: "  " }, "name"],
      [{ ...body(), name: "x".repeat(41) }, "name"],
      [{ ...body(), name: undefined }, "name"],
    ];
    for (const [bad, field] of cases) {
      const r = await call("POST", "/v1/groups", { token: a.token, body: bad });
      expect(r.status, field).toBe(400);
      expect(r.body.error.code, field).toBe("bad_request");
      expect(r.body.error.field).toBe(field);
    }
    const userId = await call("POST", "/v1/groups", { token: a.token, body: { ...body(), members: [{ id: uuid(), userId: b.id }] } });
    expect(userId.body.error.message).toBe("Family members join with a link.");
    // Ids are random (v4), as for groups.
    const v1 = await call("POST", "/v1/groups", { token: a.token, body: { ...body(), id: "6f1d2a3c-0000-1000-8000-000000000000" } });
    expect(v1.body.error.field).toBe("id");
    expect(await rows("SELECT id FROM members WHERE user_id = ?", a.id)).toHaveLength(0);
  });

  it("one live family per user: a second create, or one racing from another phone, is 409 already_in_family", async () => {
    const a = await signIn();
    const first = await createFamily(a);
    const second = await call("POST", "/v1/groups", {
      token: a.token,
      body: { id: uuid(), kind: "family", name: "Home 2", myMemberId: uuid(), members: [{ id: uuid(), name: "Partner" }] },
    });
    expect(second.status).toBe(409);
    expect(second.body.error.code).toBe("already_in_family");
    // A normal group is still fine.
    expect((await createGroup(a, { friends: [{ name: "Priya" }] })).id).toBeTruthy();

    // Another phone of the same person makes a family between the check and the batch: the guard fails the batch.
    const u = await signIn();
    const raceId = uuid();
    testHooks.beforeBatch = async (label) => {
      if (label !== "group.create") return;
      testHooks.beforeBatch = undefined;
      await otherFamilyOf(u);
    };
    const raced = await call("POST", "/v1/groups", {
      token: u.token,
      body: { id: raceId, kind: "family", name: "Home", myMemberId: uuid(), members: [{ id: uuid(), name: "Partner" }] },
    });
    expect(raced.status).toBe(409);
    expect(raced.body.error.code).toBe("already_in_family");
    expect(await rows("SELECT id FROM groups WHERE id = ?", raceId)).toHaveLength(0);

    // Once out of the family (it is tombstoned: nobody else is on the app), a new one can be made.
    expect((await call("POST", `/v1/groups/${first.id}/leave`, { token: a.token })).status).toBe(204);
    expect((await createFamily(a, "New home")).r.status).toBe(201);
  });
});

describe("invites to a family", () => {
  it("previews kind family with no money, the members on the app, and alreadyInFamily", async () => {
    const a = await signIn("Arjun Mehta");
    const fam = await createFamily(a);
    const ma = fam.mine;
    // A family expense must not show in the preview.
    expect((await putExpense(a, fam.id, familyExpense(ma, 234000))).r.status).toBe(201);
    const { token } = await familyLink(a, fam.id, fam.placeholder);
    const b = await signIn("Meera Iyer");
    const prev = await call("GET", `/v1/invites/${token}`, { token: b.token });
    expect(prev.status).toBe(200);
    expect(prev.body).toEqual({
      groupId: fam.id,
      kind: "family",
      groupName: "Home",
      inviterName: "Arjun Mehta",
      memberNames: ["Arjun"],
      memberCount: 1,
      youOwePaise: 0,
      owedToYouPaise: 0,
      expensesThisMonth: 0,
      alreadyMember: false,
      alreadyInFamily: false,
    });
    // Not cached: nothing in group_cache for the family.
    expect(await rows("SELECT cache_key FROM group_cache WHERE group_id = ?", fam.id)).toHaveLength(0);

    const c = await signIn("Kiran");
    await otherFamilyOf(c);
    expect((await call("GET", `/v1/invites/${token}`, { token: c.token })).body.alreadyInFamily).toBe(true);
    // The owner previewing their own link.
    expect((await call("GET", `/v1/invites/${token}`, { token: a.token })).body).toMatchObject({ alreadyMember: true, alreadyInFamily: false });
  });

  it("group and direct links now say their kind; being in a family does not matter for them", async () => {
    const a = await signIn("Arjun");
    const g = await createGroup(a, { friends: [{ name: "Priya" }] });
    const { token: groupToken } = await familyLink(a, g.id, g.members.Priya);
    const direct = uuid();
    const friend = uuid();
    await call("POST", "/v1/groups", { token: a.token, body: { id: direct, kind: "direct", myMemberId: uuid(), members: [{ id: friend, name: "Priya" }] } });
    const { token: directToken } = await familyLink(a, direct, friend);
    const b = await signIn();
    await createFamily(b);
    expect((await call("GET", `/v1/invites/${groupToken}`, { token: b.token })).body).toMatchObject({ kind: "group", alreadyInFamily: false });
    expect((await call("GET", `/v1/invites/${directToken}`, { token: b.token })).body).toMatchObject({ kind: "direct", alreadyInFamily: false });
    expect((await call("POST", `/v1/invites/${groupToken}/accept`, { token: b.token })).status).toBe(200);
  });

  it("accept joins the family; someone in another family gets 409 already_in_family (checked and guarded)", async () => {
    const a = await signIn("Arjun");
    const b = await signIn("Meera");
    const fam = await createFamily(a);
    const { token } = await familyLink(a, fam.id, fam.placeholder);

    const c = await signIn();
    await otherFamilyOf(c);
    const refused = await call("POST", `/v1/invites/${token}/accept`, { token: c.token });
    expect(refused.status).toBe(409);
    expect(refused.body.error.code).toBe("already_in_family");

    // b makes a family on another phone between the check and the batch.
    let raceFamily = "";
    testHooks.beforeBatch = async (label) => {
      if (label !== "invite.accept") return;
      testHooks.beforeBatch = undefined;
      raceFamily = await otherFamilyOf(b);
    };
    const raced = await call("POST", `/v1/invites/${token}/accept`, { token: b.token });
    expect(raced.status).toBe(409);
    expect(raced.body.error.code).toBe("already_in_family");
    expect((await rows("SELECT user_id FROM members WHERE id = ?", fam.placeholder))[0].user_id).toBeNull();

    // Out of that one again, b joins.
    await env.DB.prepare("UPDATE groups SET deleted_at = ? WHERE id = ?").bind(Date.now(), raceFamily).run();
    const acc = await call("POST", `/v1/invites/${token}/accept`, { token: b.token });
    expect(acc.status).toBe(200);
    expect(acc.body).toEqual({ groupId: fam.id, memberId: fam.placeholder, version: expect.any(Number) });
    const pull = await call("GET", `/v1/groups/${fam.id}/changes`, { token: a.token });
    expect(pull.body.members.find((m: any) => m.id === fam.placeholder)).toMatchObject({ userId: b.id, name: "Meera", onApp: true });
    expect(pull.body.changes.at(-1)).toMatchObject({ kind: "member", op: "join", entityId: fam.placeholder });
    // The link is used up.
    expect((await call("GET", `/v1/links/${token}`)).body.error.code).toBe("link_not_found");
  });
});

describe("family expenses (PUT and DELETE .../expenses/{eid})", () => {
  it("only the payer's own payment, one share for the full amount, not split, not auto", async () => {
    const a = await signIn("Arjun");
    const b = await signIn("Meera");
    const fam = await familyOfTwo(a, b);
    const [ma, mb] = [fam.mine, fam.placeholder];

    const notMine = await putExpense(a, fam.id, familyExpense(mb, 1000, { shares: [{ memberId: mb, sharePaise: 1000, input: null }] }));
    expect(notMine.r.status).toBe(403);
    expect(notMine.r.body.error).toMatchObject({ code: "not_allowed", message: "In a family you share your own payments only." });

    const split = await putExpense(a, fam.id, expenseBody(ma, 2000, [ma, mb]));
    expect(split.r.status).toBe(400);
    expect(split.r.body.error).toMatchObject({ code: "bad_request", field: "shares", message: "Family expenses are not split." });
    const theirShare = await putExpense(a, fam.id, familyExpense(ma, 1000, { shares: [{ memberId: mb, sharePaise: 1000, input: null }] }));
    expect(theirShare.r.body.error.field).toBe("shares");
    const byShares = await putExpense(a, fam.id, familyExpense(ma, 1000, { splitMethod: "SHARES", shares: [{ memberId: ma, sharePaise: 1000, input: 1 }] }));
    expect(byShares.r.status).toBe(400);
    expect(byShares.r.body.error.field).toBe("shares");
    const byAmounts = await putExpense(a, fam.id, familyExpense(ma, 1000, { splitMethod: "AMOUNTS", shares: [{ memberId: ma, sharePaise: 1000, input: 1000 }] }));
    expect(byAmounts.r.body.error.field).toBe("shares");
    const auto = await putExpense(a, fam.id, familyExpense(ma, 1000, { auto: true }));
    expect(auto.r.status).toBe(400);
    expect(auto.r.body.error.field).toBe("auto");
    // Everything else as 4.7.
    expect((await putExpense(a, fam.id, familyExpense(ma, 1000, { description: "" }))).r.body.error.field).toBe("description");
    expect((await putExpense(a, fam.id, familyExpense(ma, 1000, { occurredOn: "1999-12-31" }))).r.body.error.field).toBe("occurredOn");
    expect(await rows("SELECT id FROM expenses WHERE group_id = ?", fam.id)).toHaveLength(0);

    // Happy path: create, update, delete.
    const created = await putExpense(a, fam.id, familyExpense(ma, 234000));
    expect(created.r.status).toBe(201);
    expect(created.r.body).toMatchObject({
      groupId: fam.id,
      description: "BigBasket",
      category: "GROCERIES",
      amountPaise: 234000,
      paidByMemberId: ma,
      splitMethod: "EQUAL",
      auto: false,
      shares: [{ memberId: ma, sharePaise: 234000, input: null }],
      deleted: false,
    });
    const v1 = created.r.body.version;
    const updated = await putExpense(a, fam.id, familyExpense(ma, 234000, { category: "FOOD", baseVersion: v1 }), created.eid);
    expect(updated.r.status).toBe(200);
    expect(updated.r.body).toMatchObject({ category: "FOOD", overwrote: false, version: v1 + 1 });

    // The partner may not change or delete it.
    const theirsUpdate = await putExpense(b, fam.id, familyExpense(mb, 234000), created.eid);
    expect(theirsUpdate.r.status).toBe(403);
    expect(theirsUpdate.r.body.error.code).toBe("not_allowed");
    const theirsDelete = await call("DELETE", `/v1/groups/${fam.id}/expenses/${created.eid}`, { token: b.token });
    expect(theirsDelete.status).toBe(403);
    expect(theirsDelete.body.error.code).toBe("not_allowed");
    // Nor can the payer hand it to the partner.
    expect((await putExpense(a, fam.id, familyExpense(mb, 234000), created.eid)).r.status).toBe(403);
    // The partner's own expense is theirs.
    expect((await putExpense(b, fam.id, familyExpense(mb, 45000, { description: "Swiggy", category: "FOOD" }))).r.status).toBe(201);

    const del = await call("DELETE", `/v1/groups/${fam.id}/expenses/${created.eid}`, { token: a.token });
    expect(del.status).toBe(200);
    expect(await rows("SELECT * FROM expense_shares WHERE expense_id = ?", created.eid)).toHaveLength(0);
    const after = await putExpense(a, fam.id, familyExpense(ma, 234000), created.eid);
    expect(after.r.status).toBe(410);
    expect(after.r.body.error.code).toBe("expense_deleted");
    // Nets are always 0, so nothing is owed (balances are refused on a family; computed here from the rows).
    const nets = await rows(
      `SELECT e.paid_by_member_id AS payer, e.amount_paise - s.share_paise AS net FROM expenses e
       JOIN expense_shares s ON s.expense_id = e.id WHERE e.group_id = ? AND e.deleted_at IS NULL`,
      fam.id,
    );
    expect(nets.every((n) => n.net === 0)).toBe(true);
  });
});

describe("what a family never does", () => {
  it("balances, settlements, reminders, undo-join, delete, edit member and import answer 403 not_allowed", async () => {
    const a = await signIn();
    const b = await signIn();
    const fam = await familyOfTwo(a, b);
    const [ma, mb] = [fam.mine, fam.placeholder];
    const settlement = { fromMemberId: mb, toMemberId: ma, amountPaise: 100, method: "CASH", occurredOn: today() };
    const sid = uuid();
    const refused: [string, string, unknown, string][] = [
      ["GET", `/v1/groups/${fam.id}/balances`, undefined, "Families do not settle up."],
      ["PUT", `/v1/groups/${fam.id}/settlements/${sid}`, settlement, "Families do not settle up."],
      ["POST", `/v1/groups/${fam.id}/settlements/${sid}/confirm`, undefined, "Families do not settle up."],
      ["DELETE", `/v1/groups/${fam.id}/settlements/${sid}`, undefined, "Families do not settle up."],
      ["POST", `/v1/groups/${fam.id}/reminders`, { memberIds: [mb] }, "Families do not settle up."],
      ["POST", `/v1/groups/${fam.id}/members/${mb}/undo-join`, undefined, "In a family the owner removes instead."],
      ["DELETE", `/v1/groups/${fam.id}`, undefined, "A family ends when its last member leaves."],
      ["PATCH", `/v1/groups/${fam.id}/members/${mb}`, { name: "Meera" }, "Family members join with a link."],
      [
        "POST",
        `/v1/groups/${fam.id}/import`,
        { items: [{ kind: "expense", id: uuid(), ...familyExpense(ma, 100, { occurredOn: "2025-01-01" }) }] },
        "Only a group can be imported into.",
      ],
    ];
    for (const [method, path, body, message] of refused) {
      const r = await call(method, path, { token: a.token, body });
      expect(r.status, `${method} ${path}`).toBe(403);
      expect(r.body.error, `${method} ${path}`).toEqual({ code: "not_allowed", message });
    }
    expect(await rows("SELECT id FROM settlements WHERE group_id = ?", fam.id)).toHaveLength(0);
    expect((await rows("SELECT deleted_at FROM groups WHERE id = ?", fam.id))[0].deleted_at).toBeNull();
    expect((await rows("SELECT user_id FROM members WHERE id = ?", mb))[0].user_id).toBe(b.id);
  });
});

describe("leave a family", () => {
  it("the leaver's family expenses leave with them; the partner's stay; ownership passes; the last one out ends it", async () => {
    const a = await signIn("Arjun");
    const b = await signIn("Meera");
    const fam = await familyOfTwo(a, b);
    const [ma, mb] = [fam.mine, fam.placeholder];
    const { eid: a1 } = await putExpense(a, fam.id, familyExpense(ma, 234000));
    const { eid: a2 } = await putExpense(a, fam.id, familyExpense(ma, 45000, { description: "Swiggy", category: "FOOD" }));
    const { eid: b1 } = await putExpense(b, fam.id, familyExpense(mb, 120000));
    const before = await groupVersion(fam.id);

    expect((await call("POST", `/v1/groups/${fam.id}/leave`, { token: a.token })).status).toBe(204);
    const v = await groupVersion(fam.id);
    expect(v).toBe(before + 1);
    expect(await rows("SELECT id, deleted_at IS NOT NULL AS gone, version, updated_by_member_id FROM expenses WHERE group_id = ? ORDER BY id", fam.id)).toEqual(
      [
        { id: a1, gone: 1, version: v, updated_by_member_id: ma },
        { id: a2, gone: 1, version: v, updated_by_member_id: ma },
        { id: b1, gone: 0, version: expect.any(Number), updated_by_member_id: mb },
      ].sort((x, y) => (x.id < y.id ? -1 : 1)),
    );
    expect(await rows(`SELECT * FROM expense_shares WHERE expense_id IN (?, ?)`, a1, a2)).toHaveLength(0);
    expect(await rows("SELECT * FROM expense_shares WHERE expense_id = ?", b1)).toHaveLength(1);
    expect(await rows("SELECT reason FROM access_ended WHERE user_id = ? AND group_id = ?", a.id, fam.id)).toEqual([{ reason: "left" }]);
    expect((await rows("SELECT owner_user_id, deleted_at FROM groups WHERE id = ?", fam.id))[0]).toEqual({ owner_user_id: b.id, deleted_at: null });
    // The partner's next pull carries the tombstones and the leave.
    const pull = await call("GET", `/v1/groups/${fam.id}/changes?since=${before}`, { token: b.token });
    expect(pull.body.expenses).toEqual(
      expect.arrayContaining([
        { id: a1, groupId: fam.id, deleted: true, version: v },
        { id: a2, groupId: fam.id, deleted: true, version: v },
      ]),
    );
    expect(pull.body.expenses).toHaveLength(2);
    expect(pull.body.changes).toEqual([{ version: v, kind: "member", op: "leave", entityId: ma, actorMemberId: ma, at: expect.any(Number) }]);
    expect(pull.body.group).toMatchObject({ ownerUserId: b.id, ownerMemberId: mb });
    // The leaver is out.
    expect((await call("GET", `/v1/groups/${fam.id}/changes`, { token: a.token })).body.error).toMatchObject({ code: "not_member", reason: "left" });

    // b, the last member on the app, leaves: the family is tombstoned.
    expect((await call("POST", `/v1/groups/${fam.id}/leave`, { token: b.token })).status).toBe(204);
    expect((await rows("SELECT deleted_at FROM groups WHERE id = ?", fam.id))[0].deleted_at).not.toBeNull();
    expect((await rows("SELECT deleted_at FROM expenses WHERE id = ?", b1))[0].deleted_at).not.toBeNull();
  });

  it("a placeholder left alone does not keep the family alive; its link stops working", async () => {
    const a = await signIn();
    const fam = await createFamily(a);
    const { token } = await familyLink(a, fam.id, fam.placeholder);
    const key = uuid();
    expect((await call("POST", `/v1/groups/${fam.id}/leave`, { token: a.token, idem: key })).status).toBe(204);
    expect((await rows("SELECT deleted_at, owner_user_id FROM groups WHERE id = ?", fam.id))[0]).toEqual({
      deleted_at: expect.any(Number),
      owner_user_id: null,
    });
    expect((await call("GET", `/v1/links/${token}`)).body.error.code).toBe("link_not_found");
    const b = await signIn();
    expect((await call("POST", `/v1/invites/${token}/accept`, { token: b.token })).body.error.code).toBe("link_not_found");
    // A retry gets its 204.
    const replay = await call("POST", `/v1/groups/${fam.id}/leave`, { token: a.token, idem: key });
    expect(replay.status).toBe(204);
    expect(replay.headers.get("Idempotent-Replay")).toBe("true");
  });
});

describe("remove from a family", () => {
  it("the owner removes the partner: their expenses leave, access_ended removed, their socket is kicked", async () => {
    const a = await signIn("Arjun");
    const b = await signIn("Meera");
    const fam = await familyOfTwo(a, b);
    const [ma, mb] = [fam.mine, fam.placeholder];
    const { eid: a1 } = await putExpense(a, fam.id, familyExpense(ma, 1000));
    const { eid: b1 } = await putExpense(b, fam.id, familyExpense(mb, 2000));

    // The partner may not remove the owner.
    const notOwner = await call("DELETE", `/v1/groups/${fam.id}/members/${ma}`, { token: b.token });
    expect(notOwner.status).toBe(403);
    expect(notOwner.body.error.code).toBe("not_owner");
    // Nobody removes themselves.
    expect((await call("DELETE", `/v1/groups/${fam.id}/members/${ma}`, { token: a.token })).body.error.code).toBe("not_allowed");

    const live = await call("GET", `/v1/groups/${fam.id}/live`, { token: b.token, headers: { Upgrade: "websocket", "X-Installation-Id": b.installationId } });
    expect(live.status).toBe(101);
    const ws = live.res.webSocket!;
    const got: any[] = [];
    const closed: { code: number | null } = { code: null };
    ws.addEventListener("message", (e) => got.push(JSON.parse(e.data as string)));
    ws.addEventListener("close", (e) => {
      closed.code = e.code;
    });
    ws.accept();

    expect((await call("DELETE", `/v1/groups/${fam.id}/members/${mb}`, { token: a.token })).status).toBe(204);
    const v = await groupVersion(fam.id);
    expect((await rows("SELECT deleted_at IS NOT NULL AS gone, version FROM expenses WHERE id = ?", b1))[0]).toEqual({ gone: 1, version: v });
    expect((await rows("SELECT deleted_at FROM expenses WHERE id = ?", a1))[0].deleted_at).toBeNull();
    expect(await rows("SELECT * FROM expense_shares WHERE expense_id = ?", b1)).toHaveLength(0);
    expect(await rows("SELECT reason FROM access_ended WHERE user_id = ? AND group_id = ?", b.id, fam.id)).toEqual([{ reason: "removed" }]);
    const end = Date.now() + 2000;
    while (closed.code === null && Date.now() < end) await new Promise((r) => setTimeout(r, 10));
    expect(got.at(-1)).toEqual({ type: "revoked" });
    expect(closed.code).toBe(4403);
    expect((await call("GET", `/v1/groups/${fam.id}/changes`, { token: b.token })).body.error).toMatchObject({ code: "not_member", reason: "removed" });
    // Already removed: 204 again.
    expect((await call("DELETE", `/v1/groups/${fam.id}/members/${mb}`, { token: a.token })).status).toBe(204);
  });

  it("cancel invite: any member removes the placeholder, and its links stop working", async () => {
    const a = await signIn();
    const fam = await createFamily(a);
    const { token } = await familyLink(a, fam.id, fam.placeholder);
    // Any member, not only the owner: here the caller no longer owns it.
    await env.DB.prepare("UPDATE groups SET owner_user_id = NULL WHERE id = ?").bind(fam.id).run();
    expect((await call("DELETE", `/v1/groups/${fam.id}/members/${fam.placeholder}`, { token: a.token })).status).toBe(204);
    expect((await rows("SELECT removed_at FROM members WHERE id = ?", fam.placeholder))[0].removed_at).not.toBeNull();
    expect((await call("GET", `/v1/links/${token}`)).body.error.code).toBe("link_not_found");
    expect((await rows("SELECT deleted_at FROM groups WHERE id = ?", fam.id))[0].deleted_at).toBeNull();
  });

  it("a partner who joins between the checks and the batch is not removed by a member who is not the owner", async () => {
    const a = await signIn();
    const b = await signIn();
    const fam = await createFamily(a);
    const { token } = await familyLink(a, fam.id, fam.placeholder);
    await env.DB.prepare("UPDATE groups SET owner_user_id = NULL WHERE id = ?").bind(fam.id).run();
    testHooks.beforeBatch = async (label) => {
      if (label !== "member.remove") return;
      testHooks.beforeBatch = undefined;
      expect((await call("POST", `/v1/invites/${token}/accept`, { token: b.token })).status).toBe(200);
    };
    const r = await call("DELETE", `/v1/groups/${fam.id}/members/${fam.placeholder}`, { token: a.token });
    expect(r.status).toBe(403);
    expect(r.body.error.code).toBe("not_owner");
    expect((await rows("SELECT user_id, removed_at FROM members WHERE id = ?", fam.placeholder))[0]).toEqual({ user_id: b.id, removed_at: null });
  });
});

describe("add the placeholder again (POST .../members on a family)", () => {
  it("placeholder only, never a phone, at most 2 active members", async () => {
    const a = await signIn();
    const b = await signIn();
    const fam = await createFamily(a);
    const full = await call("POST", `/v1/groups/${fam.id}/members`, { token: a.token, body: { id: uuid(), name: "Partner" } });
    expect(full.status).toBe(409);
    expect(full.body.error.code).toBe("limit_reached");

    expect((await call("DELETE", `/v1/groups/${fam.id}/members/${fam.placeholder}`, { token: a.token })).status).toBe(204);
    const withUser = await call("POST", `/v1/groups/${fam.id}/members`, { token: a.token, body: { id: uuid(), userId: b.id } });
    expect(withUser.status).toBe(403);
    expect(withUser.body.error).toEqual({ code: "not_allowed", message: "Family members join with a link." });
    const withPhone = await call("POST", `/v1/groups/${fam.id}/members`, { token: a.token, body: { id: uuid(), name: "Partner", phone: "+919000000001" } });
    expect(withPhone.status).toBe(400);
    expect(withPhone.body.error.field).toBe("phone");

    const id = uuid();
    const added = await call("POST", `/v1/groups/${fam.id}/members`, { token: a.token, body: { id, name: "Partner" } });
    expect(added.status).toBe(201);
    expect(added.body).toMatchObject({ id, groupId: fam.id, userId: null, name: "Partner", phone: null, onApp: false, removed: false });
    // The new place takes a link and a partner.
    const { token } = await familyLink(a, fam.id, id);
    expect((await call("POST", `/v1/invites/${token}/accept`, { token: b.token })).status).toBe(200);
    const third = await call("POST", `/v1/groups/${fam.id}/members`, { token: b.token, body: { id: uuid(), name: "Partner" } });
    expect(third.status).toBe(409);
    expect(third.body.error.code).toBe("limit_reached");
  });

  it("the guard keeps the count at 2 when two adds race", async () => {
    const a = await signIn();
    const fam = await createFamily(a);
    expect((await call("DELETE", `/v1/groups/${fam.id}/members/${fam.placeholder}`, { token: a.token })).status).toBe(204);
    testHooks.beforeBatch = async (label) => {
      if (label !== "member.create") return;
      testHooks.beforeBatch = undefined;
      expect((await call("POST", `/v1/groups/${fam.id}/members`, { token: a.token, body: { id: uuid(), name: "Partner" } })).status).toBe(201);
    };
    const r = await call("POST", `/v1/groups/${fam.id}/members`, { token: a.token, body: { id: uuid(), name: "Partner" } });
    expect(r.status).toBe(409);
    expect(r.body.error.code).toBe("limit_reached");
    expect(await rows("SELECT id FROM members WHERE group_id = ? AND removed_at IS NULL", fam.id)).toHaveLength(2);
  });
});

describe("family links", () => {
  it("only for the placeholder; they live 7 days; an 8-day-old link works nowhere", async () => {
    const a = await signIn();
    const b = await signIn();
    const fam = await createFamily(a);
    const first = await familyLink(a, fam.id, fam.placeholder);
    expect(first.put.status).toBe(201);
    expect(first.put.body.expiresAt).toBe(first.put.body.createdAt + 7 * DAY);
    const stored = (await rows("SELECT created_at, expires_at FROM links WHERE id = ?", first.linkId))[0];
    expect(stored.expires_at).toBe(stored.created_at + 7 * DAY);
    // A lost answer sent again keeps the stored expiry.
    const again = await call("PUT", `/v1/groups/${fam.id}/members/${fam.placeholder}/links/${first.linkId}`, { token: a.token, body: { token: first.token } });
    expect(again.status).toBe(200);
    expect(again.body.expiresAt).toBe(stored.expires_at);

    // 8 days later.
    const old = Date.now() - 8 * DAY;
    await env.DB.prepare("UPDATE links SET created_at = ?, expires_at = ? WHERE id = ?").bind(old, old + 7 * DAY, first.linkId).run();
    expect((await call("GET", `/v1/invites/${first.token}`, { token: b.token })).body.error.code).toBe("link_not_found");
    expect((await call("POST", `/v1/invites/${first.token}/accept`, { token: b.token })).body.error.code).toBe("link_not_found");
    expect((await call("GET", `/v1/links/${first.token}`)).body.error.code).toBe("link_not_found");

    // A new link works; once the partner is on the app, no more links for their place.
    const second = await familyLink(a, fam.id, fam.placeholder);
    expect((await call("POST", `/v1/invites/${second.token}/accept`, { token: b.token })).status).toBe(200);
    const onApp = await familyLink(a, fam.id, fam.placeholder);
    expect(onApp.put.status).toBe(409);
    expect(onApp.put.body.error.code).toBe("member_on_app");
    expect((await familyLink(b, fam.id, fam.mine)).put.body.error.code).toBe("member_on_app");
  });

  it("the public page of a family link holds the family's name and the sender's name only", async () => {
    const a = await signIn("Arjun Mehta");
    await call("PATCH", "/v1/me", { token: a.token, body: { phone: "+919000000007", upiId: "arjun@okhdfc" } });
    const fam = await createFamily(a);
    await putExpense(a, fam.id, familyExpense(fam.mine, 234000));
    const { token, linkId } = await familyLink(a, fam.id, fam.placeholder);
    const r = await call("GET", `/v1/links/${token}`);
    expect(r.status).toBe(200);
    expect(Object.keys(r.body).sort()).toEqual(["family", "familyName", "senderName"]);
    expect(r.body).toEqual({ family: true, familyName: "Home", senderName: "Arjun" });
    expect(r.headers.get("X-Robots-Tag")).toBe("noindex, nofollow");
    expect(r.headers.get("Access-Control-Allow-Origin")).toBe("https://xpense.app");
    const text = JSON.stringify(r.body);
    for (const secret of [fam.id, fam.mine, fam.placeholder, linkId, a.id, "234000", "BigBasket", "+919000000007", "arjun@okhdfc"]) {
      expect(text).not.toContain(secret);
    }
    expect(await rows("SELECT cache_key FROM group_cache WHERE group_id = ?", fam.id)).toHaveLength(0);
  });
});

describe("rename a family", () => {
  it("the partner renames it and the version moves", async () => {
    const a = await signIn();
    const b = await signIn();
    const fam = await familyOfTwo(a, b);
    const before = await groupVersion(fam.id);
    const r = await call("PATCH", `/v1/groups/${fam.id}`, { token: b.token, body: { name: "Our home" } });
    expect(r.status).toBe(200);
    expect(r.body).toMatchObject({ id: fam.id, kind: "family", name: "Our home", version: before + 1 });
    expect((await rows("SELECT name, version FROM groups WHERE id = ?", fam.id))[0]).toEqual({ name: "Our home", version: before + 1 });
    const pull = await call("GET", `/v1/groups/${fam.id}/changes?since=${before}`, { token: a.token });
    expect(pull.body.changes).toEqual([{ version: before + 1, kind: "group", op: "update", entityId: fam.id, actorMemberId: fam.placeholder, at: expect.any(Number) }]);
    expect((await call("PATCH", `/v1/groups/${fam.id}`, { token: b.token, body: { name: "" } })).body.error.field).toBe("name");
  });
});
