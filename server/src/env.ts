import type { GroupRoom } from "./room";

/** Bindings, vars and secrets (wrangler.toml, `.dev.vars`, `wrangler secret put`). */
export interface Env {
  DB: D1Database;
  GROUP_ROOM: DurableObjectNamespace<GroupRoom>;

  /** "production" in wrangler.toml; "development" only on the `npm run dev` command line. */
  ENVIRONMENT: string;
  GOOGLE_WEB_CLIENT_ID: string;
  /** Comma list; when not empty, the ID token's `azp` must be one of them. */
  GOOGLE_ANDROID_CLIENT_IDS: string;
  /** Empty: push is off. */
  FCM_PROJECT_ID: string;
  LINK_HOST: string;
  WEB_ORIGIN: string;

  /** Secret, required (32+ characters). Without it every route answers 500. */
  SESSION_SECRET?: string;
  /** Secret, optional. Empty: push is off. */
  FCM_SERVICE_ACCOUNT_JSON?: string;

  RL_AUTH?: RateLimit;
  RL_REFRESH?: RateLimit;
  RL_PUBLIC?: RateLimit;
  RL_USER?: RateLimit;
  RL_WRITE?: RateLimit;
}
