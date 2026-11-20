// The only place that calls console.log (docs/server.md 10). Lines hold the method, the route template, the
// status, the error code and the duration. Never ids, tokens, names, phones, UPI IDs, amounts, descriptions,
// bodies, headers or IP addresses.

export function logRequest(method: string, route: string, status: number, code: string | null, ms: number): void {
  console.log(JSON.stringify({ method, route, status, code, ms }));
}

/** Uncaught exceptions: the error class name only (messages can hold SQL values). */
export function logError(where: string, err: unknown): void {
  const name = err instanceof Error ? err.constructor.name : typeof err;
  console.log(JSON.stringify({ error: name, where }));
}
