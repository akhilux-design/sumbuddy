// Pure rules for scripts/check-remote-migrations.mjs, kept free of Node APIs so the Worker tests can run them too.

/**
 * Reads what `wrangler d1 migrations list xpense --remote` printed and says whether the Worker may be deployed.
 * Deploying a Worker that reads columns the live database does not have yet makes every group route fail (500), so
 * the deploy goes ahead only when wrangler said in so many words that nothing is left to apply. Anything else (a
 * migration listed, an error, not signed in to Cloudflare, an output this rule does not know) stops it.
 *
 * @param {string} output what wrangler printed (stdout and stderr together)
 * @param {number | null} exitCode wrangler's exit code
 * @returns {string[]} problems; empty when every migration is applied to the live database
 */
export function checkRemoteMigrations(output, exitCode) {
  const pending = [...new Set(output.match(/\b\d{4}_[A-Za-z0-9_-]+\.sql\b/g) ?? [])];
  if (exitCode === 0 && /No migrations to apply/.test(output) && pending.length === 0) return [];
  if (pending.length > 0) {
    return [
      `The live database still needs ${pending.join(", ")}. Apply it first: npx wrangler d1 migrations apply xpense --remote`,
    ];
  }
  return [
    "Could not tell whether the live database has every migration (wrangler said something else, or failed). " +
      "Run npx wrangler d1 migrations list xpense --remote and look.",
  ];
}
