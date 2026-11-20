// Thin wrapper over D1 that counts queries and rows (D1's meta.rows_read / rows_written). All group-owned SQL lives
// in this folder (docs/server.md 8), so a later split of the database touches one place.

type Param = string | number | null;

interface Meta {
  rows_read?: number;
  rows_written?: number;
  changes?: number;
}

export class Db {
  queries = 0;
  batches = 0;
  rowsRead = 0;
  rowsWritten = 0;

  constructor(readonly d1: D1Database) {}

  stmt(sql: string, ...params: Param[]): D1PreparedStatement {
    return this.d1.prepare(sql).bind(...params);
  }

  private track(meta: Meta | undefined): void {
    this.rowsRead += meta?.rows_read ?? 0;
    this.rowsWritten += meta?.rows_written ?? 0;
  }

  async all<T>(sql: string, ...params: Param[]): Promise<T[]> {
    this.queries++;
    const r = await this.stmt(sql, ...params).all<T>();
    this.track(r.meta as Meta);
    return r.results;
  }

  async first<T>(sql: string, ...params: Param[]): Promise<T | null> {
    return (await this.all<T>(sql, ...params))[0] ?? null;
  }

  async run(sql: string, ...params: Param[]): Promise<number> {
    this.queries++;
    const r = await this.stmt(sql, ...params).run();
    this.track(r.meta as Meta);
    return (r.meta as Meta).changes ?? 0;
  }

  /** One atomic batch; counts as one query against the 50-per-invocation limit. */
  async batch(stmts: D1PreparedStatement[]): Promise<D1Result[]> {
    this.queries++;
    this.batches++;
    const results = await this.d1.batch(stmts);
    for (const r of results) this.track(r.meta as Meta);
    return results;
  }
}

/** `?n, ?n+1, ...` placeholders for an IN list starting at parameter number `start`. */
export function placeholders(count: number, start: number): string {
  return Array.from({ length: count }, (_, i) => `?${start + i}`).join(", ");
}

/** True when a D1 error is a constraint failure on the named table (or table.column). */
export function constraintOn(err: unknown, what: string): boolean {
  const msg = err instanceof Error ? err.message : String(err);
  return msg.includes("constraint failed") && msg.includes(what);
}

/** The 3.1 guard failed: the change row's version or entity id came out NULL. */
export function guardFailed(err: unknown): boolean {
  return constraintOn(err, "changes.version") || constraintOn(err, "changes.entity_id");
}

export function chunk<T>(items: T[], size: number): T[][] {
  const out: T[][] = [];
  for (let i = 0; i < items.length; i += size) out.push(items.slice(i, i + size));
  return out;
}
