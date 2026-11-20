// Shared test vectors (docs/server.md 6). The Kotlin module :splits runs the same files.
import { describe, expect, it } from "vitest";
import { computeShares, nets, simplify, type SplitMethod } from "../src/domain/splits";
import { DIRECT_NS, directGroupId, directMemberId } from "../src/ids";
import balances from "./vectors/balances.json";
import directIds from "./vectors/direct_ids.json";
import splits from "./vectors/splits.json";

describe("split vectors (6.1)", () => {
  it("has at least 30 cases including rounding ties", () => {
    expect(splits.cases.length).toBeGreaterThanOrEqual(30);
    expect(splits.cases.filter((c) => c.name.includes("tie")).length).toBeGreaterThanOrEqual(3);
  });
  for (const c of splits.cases) {
    it(c.name, () => {
      const got = computeShares(c.method as SplitMethod, c.amountPaise, c.inputs as { memberId: string; input: number | null }[]);
      expect(got).toEqual(c.expected);
      expect(got.reduce((s, x) => s + x.sharePaise, 0)).toBe(c.amountPaise);
    });
  }
});

describe("balance vectors (6.2, 6.3)", () => {
  it("has at least 15 cases", () => expect(balances.cases.length).toBeGreaterThanOrEqual(15));
  for (const c of balances.cases) {
    it(c.name, () => {
      const n = nets(c.expenses, c.settlements);
      expect(n).toEqual(c.expectedNets);
      expect(simplify(n)).toEqual(c.expectedTransfers);
      expect(n.reduce((s, x) => s + x.netPaise, 0)).toBe(0);
    });
  }
});

describe("direct ids (4.6)", () => {
  it("uses the contract namespace", () => expect(directIds.namespace).toBe(DIRECT_NS));
  for (const c of directIds.cases) {
    it(`${c.userA.slice(0, 8)} + ${c.userB.slice(0, 8)}`, async () => {
      expect(await directGroupId(c.userA, c.userB)).toBe(c.groupId);
      expect(await directGroupId(c.userB, c.userA)).toBe(c.groupId);
      expect(await directMemberId(c.groupId, c.userA)).toBe(c.memberIdA);
      expect(await directMemberId(c.groupId, c.userB)).toBe(c.memberIdB);
    });
  }
});
