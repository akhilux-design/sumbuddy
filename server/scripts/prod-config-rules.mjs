// Pure rules for scripts/check-prod-config.mjs, kept free of Node APIs so the Worker tests can run them too.

/**
 * Checks the text of wrangler.toml before a deploy (docs/server.md 4.4, 10, 11).
 * @param {string} text the file's contents
 * @param {{ allowPlaceholders?: boolean }} [options] placeholders are allowed only for the committed copy in tests
 * @returns {string[]} problems; empty when the config may be deployed
 */
export function checkProdConfig(text, options = {}) {
  const problems = [];
  if (/development/i.test(text)) {
    problems.push('wrangler.toml must not contain "development" anywhere (dev sign-in would be possible).');
  }
  if (/^\s*\[env\./m.test(text)) {
    problems.push("wrangler.toml must not have [env.*] sections (they could change ENVIRONMENT).");
  }
  if (!/^\s*ENVIRONMENT\s*=\s*"production"\s*(#.*)?$/m.test(text)) {
    problems.push('wrangler.toml must set ENVIRONMENT = "production" in [vars].');
  }
  const logs = section(text, "observability.logs");
  if (logs === null || !/^\s*invocation_logs\s*=\s*false\s*(#.*)?$/m.test(logs)) {
    problems.push("wrangler.toml must have [observability.logs] invocation_logs = false (URLs hold ids and tokens).");
  }
  if (!options.allowPlaceholders) {
    if (/REPLACE_ME/.test(text)) problems.push("GOOGLE_WEB_CLIENT_ID still holds its placeholder.");
    if (/database_id\s*=\s*"0{8}-0{4}-0{4}-0{4}-0{12}"/.test(text)) {
      problems.push("database_id still holds its placeholder (run wrangler d1 create xpense).");
    }
    if (/^\s*LINK_HOST\s*=\s*"xpense\.app"/m.test(text) || /^\s*WEB_ORIGIN\s*=\s*"https:\/\/xpense\.app"/m.test(text)) {
      problems.push("LINK_HOST / WEB_ORIGIN still hold the xpense.app placeholder, a domain nobody here owns.");
    }
  }
  return problems;
}

/** The body of a [name] table up to the next table header, or null. */
function section(text, name) {
  const lines = text.split(/\r?\n/);
  const start = lines.findIndex((l) => l.trim() === `[${name}]`);
  if (start < 0) return null;
  const body = [];
  for (let i = start + 1; i < lines.length; i++) {
    if (/^\s*\[/.test(lines[i])) break;
    body.push(lines[i]);
  }
  return body.join("\n");
}
