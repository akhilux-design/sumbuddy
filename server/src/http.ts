/** Error answers (docs/server.md 4.1). `message` is fixed English text and never holds user data. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly field?: string,
    readonly headers?: Record<string, string>,
    /** `not_member` only: why the caller is no longer in the group (4.1), when the server knows. */
    readonly reason?: string,
  ) {
    super(message);
  }
}

export const badRequest = (field: string, message = "Invalid value.") =>
  new ApiError(400, "bad_request", message, field);
export const notFound = (message = "Not found.") => new ApiError(404, "not_found", message);
/** Why a person lost a group (docs/server.md 4.5 `lost`, 0002 `access_ended`). */
export type LostReason = "join_undone" | "removed" | "left" | "deleted";

export const notMember = (reason?: LostReason | null) =>
  new ApiError(403, "not_member", "Caller is not an active member of the group.", undefined, undefined, reason ?? undefined);
export const notAllowed = (message = "Not allowed.") => new ApiError(403, "not_allowed", message);
export const notOwner = () => new ApiError(403, "not_owner", "Only the group owner may do this.");
export const groupDeleted = () => new ApiError(410, "group_deleted", "The group was deleted.");
export const expenseDeleted = () => new ApiError(410, "expense_deleted", "The expense was deleted.");
export const conflict = (code: string, message: string) => new ApiError(409, code, message);
/** A family route, or a family invite, for someone active in another live family (docs/server.md 4.18). */
export const alreadyInFamily = () => conflict("already_in_family", "Already in a family.");
export const linkNotFound = () => new ApiError(404, "link_not_found", "The link does not work.");
export const unauthorized = (code = "unauthorized", message = "Not signed in.") => new ApiError(401, code, message);

const BASE_HEADERS: Record<string, string> = {
  "Cache-Control": "no-store",
  "X-Content-Type-Options": "nosniff",
};

export function json(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...BASE_HEADERS, "Content-Type": "application/json; charset=utf-8", ...headers },
  });
}

export function noContent(headers: Record<string, string> = {}): Response {
  return new Response(null, { status: 204, headers: { ...BASE_HEADERS, ...headers } });
}

export function errorResponse(err: ApiError, extraHeaders: Record<string, string> = {}): Response {
  const error: Record<string, string> = { code: err.code, message: err.message };
  if (err.field !== undefined) error.field = err.field;
  if (err.reason !== undefined) error.reason = err.reason;
  return json({ error }, err.status, { ...extraHeaders, ...(err.headers ?? {}) });
}

/** Adds the common headers to a response made elsewhere (for example by a handler that built its own). */
export function withBaseHeaders(res: Response, extra: Record<string, string> = {}): Response {
  if (res.status === 101) return res;
  const headers = new Headers(res.headers);
  for (const [k, v] of Object.entries({ ...BASE_HEADERS, ...extra })) headers.set(k, v);
  return new Response(res.body, { status: res.status, headers });
}
