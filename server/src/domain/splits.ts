// Split maths and balances (docs/server.md 6.1-6.3). Integers only. The Kotlin module `:splits` implements the same
// functions; both run the vectors in server/test/vectors (contract location: shared/vectors).

export type SplitMethod = "EQUAL" | "AMOUNTS" | "PERCENT" | "SHARES";

export interface ShareInput {
  memberId: string;
  /** EQUAL: null; AMOUNTS: paise; PERCENT: basis points; SHARES: weight. */
  input: number | null;
}

export interface Share {
  memberId: string;
  sharePaise: number;
}

const byId = (a: string, b: string) => (a < b ? -1 : a > b ? 1 : 0);

/** 6.1: shares from a method, sorted by member id. Inputs are assumed valid (validated by the caller). */
export function computeShares(method: SplitMethod, amountPaise: number, inputs: ShareInput[]): Share[] {
  const parts = [...inputs].sort((a, b) => byId(a.memberId, b.memberId));
  switch (method) {
    case "EQUAL": {
      const n = parts.length;
      const base = Math.floor(amountPaise / n);
      const r = amountPaise % n;
      return parts.map((p, i) => ({ memberId: p.memberId, sharePaise: base + (i < r ? 1 : 0) }));
    }
    case "AMOUNTS":
      return parts.map((p) => ({ memberId: p.memberId, sharePaise: p.input ?? 0 }));
    case "PERCENT":
    case "SHARES": {
      const w = method === "PERCENT" ? 10_000 : parts.reduce((s, p) => s + (p.input ?? 0), 0);
      const raws = parts.map((p) => ({ memberId: p.memberId, raw: amountPaise * (p.input ?? 0) }));
      const out = raws.map((r) => ({ memberId: r.memberId, sharePaise: Math.floor(r.raw / w) }));
      let rest = amountPaise - out.reduce((s, x) => s + x.sharePaise, 0);
      const order = raws
        .map((r, i) => ({ i, rem: r.raw % w, memberId: r.memberId }))
        .sort((a, b) => b.rem - a.rem || byId(a.memberId, b.memberId));
      for (const o of order) {
        if (rest <= 0) break;
        out[o.i].sharePaise += 1;
        rest -= 1;
      }
      return out;
    }
  }
}

export interface BalanceExpense {
  paidBy: string;
  amountPaise: number;
  shares: Share[];
}

export interface BalanceSettlement {
  from: string;
  to: string;
  amountPaise: number;
}

export interface Net {
  memberId: string;
  netPaise: number;
}

export interface Transfer {
  fromMemberId: string;
  toMemberId: string;
  amountPaise: number;
}

/** 6.2: net per member (positive: the group owes them), every member id seen, sorted by id. */
export function nets(expenses: BalanceExpense[], settlements: BalanceSettlement[]): Net[] {
  const m = new Map<string, number>();
  const add = (id: string, v: number) => m.set(id, (m.get(id) ?? 0) + v);
  for (const e of expenses) {
    add(e.paidBy, e.amountPaise);
    for (const s of e.shares) add(s.memberId, -s.sharePaise);
  }
  for (const s of settlements) {
    add(s.from, s.amountPaise);
    add(s.to, -s.amountPaise);
  }
  return [...m.entries()].sort((a, b) => byId(a[0], b[0])).map(([memberId, netPaise]) => ({ memberId, netPaise }));
}

/** 6.3: greedy "fewest payments" within one group. Deterministic; at most n - 1 transfers. */
export function simplify(input: Net[]): Transfer[] {
  const cred = new Map<string, number>();
  const debt = new Map<string, number>();
  for (const n of input) {
    if (n.netPaise > 0) cred.set(n.memberId, n.netPaise);
    else if (n.netPaise < 0) debt.set(n.memberId, -n.netPaise);
  }
  const largest = (m: Map<string, number>) => {
    let best: string | null = null;
    for (const [id, v] of m) {
      const bv = best === null ? -1 : m.get(best)!;
      if (v > bv || (v === bv && byId(id, best!) < 0)) best = id;
    }
    return best!;
  };
  const out: Transfer[] = [];
  while (cred.size > 0 && debt.size > 0) {
    const c = largest(cred);
    const d = largest(debt);
    const amount = Math.min(cred.get(c)!, debt.get(d)!);
    out.push({ fromMemberId: d, toMemberId: c, amountPaise: amount });
    const cr = cred.get(c)! - amount;
    const dr = debt.get(d)! - amount;
    if (cr === 0) cred.delete(c);
    else cred.set(c, cr);
    if (dr === 0) debt.delete(d);
    else debt.set(d, dr);
  }
  return out;
}
