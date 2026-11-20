// What the server logs (docs/server.md 10): a full flow with a console spy; no user value may appear.
import { afterEach, describe, expect, it, vi } from "vitest";
import { call, createGroup, expenseBody, putExpense, shareToken, signIn, uuid } from "./helpers";

afterEach(() => vi.restoreAllMocks());

describe("logging privacy", () => {
  it("logs method, route template, status, code and duration only", async () => {
    const lines: string[] = [];
    for (const level of ["log", "info", "warn", "error", "debug"] as const) {
      vi.spyOn(console, level).mockImplementation((...args: unknown[]) => {
        lines.push(args.map(String).join(" "));
      });
    }
    const a = await signIn("Zebulon Quixote");
    const b = await signIn("Yolanda Vexley");
    await call("PATCH", "/v1/me", { token: a.token, body: { phone: "+919000000004", upiId: "zebq77@okaxis" } });
    const g = await createGroup(a, { friends: [{ name: "Wilhelmina Frost", phone: "+919000000003" }] }, "Secret Cove");
    const ma = g.members[a.id];
    const mw = g.members["Wilhelmina Frost"];
    const { eid } = await putExpense(a, g.id, expenseBody(ma, 123457, [ma, mw], { description: "Truffle Banquet" }));
    // Import from Splitwise (4.17): past expenses and a payment, before anyone else joins.
    const importedId = uuid();
    const importedPayment = uuid();
    const imported = await call("POST", `/v1/groups/${g.id}/import`, {
      token: a.token,
      body: {
        items: [
          { kind: "expense", id: importedId, ...expenseBody(mw, 987653, [ma, mw], { description: "Saffron Regatta", occurredOn: "2025-03-14" }) },
          { kind: "settlement", id: importedPayment, fromMemberId: ma, toMemberId: mw, amountPaise: 24681, occurredOn: "2025-03-15" },
        ],
      },
    });
    expect(imported.status).toBe(200);
    const token = shareToken();
    const linkId = uuid();
    await call("PUT", `/v1/groups/${g.id}/members/${mw}/links/${linkId}`, { token: a.token, body: { token } });
    await call("GET", `/v1/links/${token}`);
    await call("GET", `/v1/invites/${token}`, { token: b.token });
    await call("POST", `/v1/invites/${token}/accept`, { token: b.token });
    const refusedImport = await call("POST", `/v1/groups/${g.id}/import`, {
      token: a.token,
      body: { items: [{ kind: "expense", id: uuid(), ...expenseBody(ma, 55551, [ma], { description: "Saffron Regatta" }) }] },
    });
    expect(refusedImport.status).toBe(403); // somebody else is on the app now
    await call("PUT", `/v1/groups/${g.id}/settlements/${uuid()}`, {
      token: b.token,
      body: { fromMemberId: mw, toMemberId: ma, amountPaise: 61729, method: "UPI", occurredOn: "2026-11-16" },
    });
    await call("GET", `/v1/groups/${g.id}/changes?since=0`, { token: b.token });
    await call("POST", "/v1/auth/refresh", { body: { refreshToken: a.refreshToken } });
    await call("PUT", `/v1/groups/${g.id}/expenses/${eid}`, { token: a.token, body: { description: "\u0007" } }); // an error
    await call("GET", `/v1/groups/${uuid()}/changes`, { token: a.token, headers: { "CF-Connecting-IP": "198.51.100.77" } });
    // A family (docs/server.md 4.18): made, invited, joined, an expense shared, then left.
    const fid = uuid();
    const fa = uuid();
    const fp = uuid();
    const made = await call("POST", "/v1/groups", {
      token: a.token,
      body: { id: fid, kind: "family", name: "Velvet Hearth", myMemberId: fa, members: [{ id: fp, name: "Partner" }] },
    });
    expect(made.status).toBe(201);
    const familyToken = shareToken();
    const familyLinkId = uuid();
    await call("PUT", `/v1/groups/${fid}/members/${fp}/links/${familyLinkId}`, { token: a.token, body: { token: familyToken } });
    await call("GET", `/v1/links/${familyToken}`);
    await call("GET", `/v1/invites/${familyToken}`, { token: b.token });
    expect((await call("POST", `/v1/invites/${familyToken}/accept`, { token: b.token })).status).toBe(200);
    const familyExpenseId = uuid();
    const shared = await call("PUT", `/v1/groups/${fid}/expenses/${familyExpenseId}`, {
      token: b.token,
      body: {
        description: "Moonlit Pantry", category: "GROCERIES", amountPaise: 765431, occurredOn: "2026-11-15", paidByMemberId: fp,
        splitMethod: "EQUAL", auto: false, shares: [{ memberId: fp, sharePaise: 765431, input: null }], baseVersion: null,
      },
    });
    expect(shared.status).toBe(201);
    await call("GET", `/v1/groups/${fid}/balances`, { token: a.token }); // refused: families do not settle up
    expect((await call("POST", `/v1/groups/${fid}/leave`, { token: b.token })).status).toBe(204);
    await call("POST", "/v1/auth/signout", { token: b.token, headers: { "X-Installation-Id": b.installationId } });

    expect(lines.length).toBeGreaterThan(10);
    const secrets = [
      a.id, b.id, a.token, b.token, a.refreshToken, a.installationId, b.installationId,
      "Zebulon", "Yolanda", "Wilhelmina", "Secret Cove", "Truffle", "+919000000004", "+919000000003", "zebq77",
      "123457", "61729", g.id, ma, mw, eid, token, linkId, "198.51.100.77",
      "Saffron", "Regatta", "987653", "24681", "55551", importedId, importedPayment, "2025-03-14",
      "Velvet", "Hearth", "Moonlit", "Pantry", "765431", "Partner", fid, fa, fp, familyToken, familyLinkId, familyExpenseId,
    ];
    const all = lines.join("\n");
    for (const s of secrets) expect(all, s).not.toContain(s);
    for (const line of lines) {
      const parsed = JSON.parse(line);
      const keys = Object.keys(parsed).sort();
      expect([["code", "method", "ms", "route", "status"], ["error", "where"]]).toContainEqual(keys);
    }
    expect(lines.some((l) => l.includes('"route":"/v1/groups/:gid/expenses/:eid"'))).toBe(true);
    expect(lines.filter((l) => l.includes('"route":"/v1/groups/:gid/import"'))).toHaveLength(2);
  });
});
