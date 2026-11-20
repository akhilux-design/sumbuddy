// GroupRoom: one live room per group (docs/server.md 4.12). SQLite-backed class, WebSocket hibernation API. The
// room stores nothing; it is reachable only through the GROUP_ROOM binding (the Worker never forwards outside paths).
import { DurableObject } from "cloudflare:workers";
import type { Env } from "./env";

/** Close code for a socket whose session ended (sign-out, account deletion) or whose access token expired. */
export const CLOSE_SIGNED_OUT = 4401;
/** Close code for a user who left, was removed, or whose group was deleted. */
export const CLOSE_REVOKED = 4403;

/** A socket open now, as its tags describe it. */
export interface Connected {
  userId: string;
  installationId: string;
}

interface Tagged extends Connected {
  ws: WebSocket;
  sessionId: string;
  expiresAt: number;
}

/** At most 99 bound parameters per D1 statement; one is the time. */
const SESSION_CHUNK = 90;

export class GroupRoom extends DurableObject<Env> {
  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    // App-level pings are answered without waking the object. Protocol pings never reach it either.
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
  }

  override async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    if (url.pathname === "/connect" && request.headers.get("Upgrade")?.toLowerCase() === "websocket") {
      const userId = request.headers.get("X-User-Id");
      const sessionId = request.headers.get("X-Session-Id");
      const expiresAt = Number(request.headers.get("X-Token-Expires-At"));
      const installationId = request.headers.get("X-Installation-Id") ?? "";
      if (!userId || !sessionId || !Number.isFinite(expiresAt) || expiresAt <= 0) return new Response(null, { status: 400 });
      const pair = new WebSocketPair();
      const [client, server] = Object.values(pair);
      // The session and the token's expiry go with the socket: the room checks both before it sends anything.
      this.ctx.acceptWebSocket(server, ["u:" + userId, "i:" + installationId, "s:" + sessionId, "e:" + expiresAt]);
      return new Response(null, { status: 101, webSocket: client });
    }
    if (url.pathname === "/notify" && request.method === "POST") {
      const body = (await request.json()) as { groupId: string; version: number; kick?: string[] };
      const changed = JSON.stringify({ type: "changed", groupId: body.groupId, version: body.version });
      const kicked = new Set(body.kick ?? []);
      const sockets = this.ctx.getWebSockets().map((ws) => this.tagged(ws));
      const live = await this.liveSessions(sockets);
      const connected: Connected[] = [];
      for (const s of sockets) {
        try {
          // Signed out, account deleted or token expired: nothing more reaches this socket. The phone reconnects
          // with a fresh token if it still can.
          if (!live.has(s.sessionId) || s.expiresAt <= Date.now()) {
            s.ws.close(CLOSE_SIGNED_OUT, "signed out");
            continue;
          }
          s.ws.send(changed);
          if (kicked.has(s.userId)) {
            s.ws.send(JSON.stringify({ type: "revoked" }));
            s.ws.close(CLOSE_REVOKED, "revoked");
            continue;
          }
        } catch {
          continue; // a socket that is already closing
        }
        if (s.installationId) connected.push({ userId: s.userId, installationId: s.installationId });
      }
      return Response.json({ connected });
    }
    return new Response(null, { status: 404 });
  }

  private tagged(ws: WebSocket): Tagged {
    const tags = this.ctx.getTags(ws);
    const tag = (p: string) => tags.find((t) => t.startsWith(p))?.slice(p.length) ?? "";
    return { ws, userId: tag("u:"), installationId: tag("i:"), sessionId: tag("s:"), expiresAt: Number(tag("e:")) || 0 };
  }

  /**
   * The session ids among the open sockets that are still live (not revoked, not expired, not deleted with the
   * account): one small read per notify, only when sockets are open. If the read fails, no session counts as live,
   * so the sockets are closed rather than kept on a session that may have ended.
   */
  private async liveSessions(sockets: Tagged[]): Promise<Set<string>> {
    const ids = [...new Set(sockets.map((s) => s.sessionId).filter((id) => id.length > 0))];
    const live = new Set<string>();
    const now = Date.now();
    try {
      for (let i = 0; i < ids.length; i += SESSION_CHUNK) {
        const part = ids.slice(i, i + SESSION_CHUNK);
        const ph = part.map((_, j) => `?${j + 2}`).join(", ");
        const rows = await this.env.DB.prepare(
          `SELECT id FROM sessions WHERE id IN (${ph}) AND revoked_at IS NULL AND expires_at > ?1`,
        )
          .bind(now, ...part)
          .all<{ id: string }>();
        for (const r of rows.results) live.add(r.id);
      }
    } catch {
      live.clear();
    }
    return live;
  }

  override async webSocketMessage(): Promise<void> {
    // Anything other than the auto-answered ping is ignored.
  }

  override async webSocketClose(ws: WebSocket, code: number): Promise<void> {
    try {
      ws.close(code === 1005 || code === 1006 ? 1000 : code, "closed");
    } catch {
      // already closed
    }
  }

  override async webSocketError(ws: WebSocket): Promise<void> {
    try {
      ws.close(1011, "error");
    } catch {
      // already closed
    }
  }
}
