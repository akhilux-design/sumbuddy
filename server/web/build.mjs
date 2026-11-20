// Builds the friends' web link page (docs/server.md section 7) into dist/:
//   - copies public/ (pages, styles, script, fonts, _headers, _redirects, robots.txt, .well-known/)
//   - writes tokens.css from design/spec (colours per theme, sizes, text styles)
//   - copies the 3D icons the page uses from design/icons/svg
//   - writes config.js and fills the placeholders in _headers, the HTML files and assetlinks.json
//
// Environment:
//   API_BASE             API origin, e.g. https://xpense-api.<account>.workers.dev (default http://localhost:8787)
//   APP_DOWNLOAD_URL     optional https link to the app's APK (GitHub Releases), for the "Get <app name>" button
//                        on the pay page and the family invite page. It wins over PLAY_STORE_URL when both are set
//   PLAY_STORE_URL       optional Play Store link for the same button. With neither set, the button is hidden
//   APP_NAME             the app's name as shown on the page (default: app_name in app/src/main/res/values/strings.xml,
//                        the one place the app keeps its name)
//   ANDROID_PACKAGE      package for assetlinks.json: the app's applicationId (app.applicationId). Required for
//                        --production; other builds use com.expenses.app
//   ANDROID_CERT_SHA256  comma list of signing certificate SHA-256 fingerprints (AA:BB:...) for assetlinks.json: the
//                        Play app signing key(s), the upload key and, while testing, the debug key. Empty: the
//                        placeholder stays and the build prints a warning
//   PAGES_PROJECT        the Cloudflare Pages project name, for --deploy (npm run deploy hands it to wrangler)
//
// Flags: --production (refuses an http, local or placeholder API_BASE and needs ANDROID_PACKAGE),
//        --deploy (--production plus a check of PAGES_PROJECT, used by npm run deploy), --out <dir> (default dist).

import { readFileSync } from 'node:fs';
import { cp, mkdir, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { tokensCss } from './scripts/tokens.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const repo = join(here, '..', '..');

/** Icons the page draws, by file name in design/icons/svg. Category icons follow app/.../Category.kt. */
export const ICONS = [
  'food_3d', 'cart_3d', 'car_3d', 'home_3d', 'bag_3d', 'film_3d', 'plus_3d',
  'user_plus_3d', 'alert_3d', 'link_3d', 'sparkle_3d', 'cash_3d', 'heart_3d',
];

const PLACEHOLDER_CERT = 'REPLACE_WITH_RELEASE_SIGNING_CERT_SHA256';
const DEFAULT_PACKAGE = 'com.expenses.app';

/** The branch npm run deploy passes to wrangler. The Pages project is created with --production-branch main. */
export const DEPLOY_BRANCH = 'main';

function fail(message) {
  throw new Error(`build.mjs: ${message}`);
}

function escapeHtml(text) {
  return text.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
}

/**
 * The app's name from the Android string resource `app_name`, so renaming the app there renames it on the page too
 * (CLAUDE.md: the name lives only in strings.xml).
 */
export function appNameFromStrings(path = join(repo, 'app', 'src', 'main', 'res', 'values', 'strings.xml')) {
  let xml;
  try {
    xml = readFileSync(path, 'utf8');
  } catch {
    fail(`cannot read ${path} for app_name; set APP_NAME`);
  }
  const match = /<string\s+name="app_name"[^>]*>([^<]*)<\/string>/.exec(xml);
  if (!match) fail(`no <string name="app_name"> in ${path}; set APP_NAME`);
  return match[1]
    .replace(/\\(['"@?])/g, '$1')
    .replace(/&(amp|lt|gt|quot|apos);/g, (_, e) => ({ amp: '&', lt: '<', gt: '>', quot: '"', apos: "'" })[e])
    .trim();
}

/**
 * PAGES_PROJECT for npm run deploy. Checked as given, not trimmed, because the shell hands the same text to wrangler.
 * An empty name would make wrangler ignore wrangler.toml and ask which project to use.
 */
export function pagesProject(env) {
  const name = env.PAGES_PROJECT ?? '';
  if (!name) fail('PAGES_PROJECT is empty: set it to the Pages project name, as in PAGES_PROJECT=<name> npm run deploy');
  if (!/^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/.test(name)) {
    fail(`PAGES_PROJECT must be the project name (lowercase letters, digits and dashes), not a host or a URL: "${name}"`);
  }
  return name;
}

/** Reads and checks the settings. Throws with a clear message when one is wrong. --deploy implies --production. */
export function readConfig(env, { production = false, deploy = false } = {}) {
  const project = deploy ? pagesProject(env) : undefined;
  production ||= deploy;

  const rawApi = (env.API_BASE ?? (production ? '' : 'http://localhost:8787')).trim();
  if (!rawApi) fail('API_BASE is required for a production build');
  let api;
  try {
    api = new URL(rawApi);
  } catch {
    fail(`API_BASE is not a URL: ${rawApi}`);
  }
  if (api.pathname !== '/' || api.search || api.hash || api.username || api.password) {
    fail('API_BASE must be an origin only, like https://xpense-api.example.workers.dev');
  }
  const local = ['localhost', '127.0.0.1', '[::1]', '10.0.2.2'].includes(api.hostname);
  if (production) {
    if (api.protocol !== 'https:') fail('API_BASE must use https in a production build');
    if (local || /(^|\.)example\.|REPLACE/i.test(api.hostname)) fail(`API_BASE still holds a placeholder: ${api.origin}`);
  } else if (api.protocol !== 'https:' && !(api.protocol === 'http:' && local)) {
    fail('API_BASE must use https, or http on a local address');
  }

  const playStoreUrl = (env.PLAY_STORE_URL ?? '').trim();
  if (playStoreUrl && !/^https:\/\/play\.google\.com\/store\/apps\/details\?id=[A-Za-z0-9._]+$/.test(playStoreUrl)) {
    fail('PLAY_STORE_URL must look like https://play.google.com/store/apps/details?id=<package>');
  }

  const appDownloadUrl = downloadUrl(env.APP_DOWNLOAD_URL, production);

  const appName = (env.APP_NAME ?? appNameFromStrings()).trim();
  if (!appName || appName.length > 30 || /[<>&"'\u0000-\u001f]/.test(appName)) fail('APP_NAME is empty or has characters it should not');

  // No silent default in production: the package must be the app's applicationId, or App Links never verify.
  const androidPackage = (env.ANDROID_PACKAGE ?? '').trim() || (production
    ? fail('ANDROID_PACKAGE is required for a production build: set it to app.applicationId in gradle.properties')
    : DEFAULT_PACKAGE);
  if (!/^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$/.test(androidPackage)) fail(`ANDROID_PACKAGE is not a package name: ${androidPackage}`);
  if (production && playStoreUrl) {
    const id = new URL(playStoreUrl).searchParams.get('id');
    if (id !== androidPackage) fail(`PLAY_STORE_URL is for ${id}, but ANDROID_PACKAGE is ${androidPackage}`);
  }

  // Any signing key may be listed, the debug key included: one entry per key that signs a build that opens links.
  const certs = [...new Set((env.ANDROID_CERT_SHA256 ?? '').split(',').map((s) => s.trim().toUpperCase()).filter(Boolean))];
  for (const cert of certs) {
    if (!/^([0-9A-F]{2}:){31}[0-9A-F]{2}$/.test(cert)) {
      fail(`ANDROID_CERT_SHA256 entry is not a SHA-256 fingerprint (32 pairs like AB:CD:..., a SHA-1 has 20): ${cert}`);
    }
  }

  return { apiOrigin: api.origin, appDownloadUrl, playStoreUrl, appName, androidPackage, certs, production, project };
}

/**
 * APP_DOWNLOAD_URL: where the "Get <app name>" button downloads the app without Google Play, for example
 * https://github.com/<owner>/<repo>/releases/latest/download/<name>.apk. Empty is allowed (no download link). It must
 * be a full https URL with a host, no user name or password and no fragment; a production build also refuses a local
 * or placeholder host.
 */
function downloadUrl(value, production) {
  const raw = (value ?? '').trim();
  if (!raw) return '';
  const wrong = (why) => fail(`APP_DOWNLOAD_URL ${why}, like https://github.com/<owner>/<repo>/releases/latest/download/<name>.apk`);
  if (raw.length > 2000 || /[\s<>"'`#\u0000-\u001f]/.test(raw)) wrong('has characters it should not');
  let url;
  try {
    url = new URL(raw);
  } catch {
    wrong('is not a URL');
  }
  if (url.protocol !== 'https:') wrong('must use https');
  if (!url.hostname || url.username || url.password || url.hash) wrong('must be a plain https link with no user name or fragment');
  if (production && (['localhost', '127.0.0.1', '[::1]', '10.0.2.2'].includes(url.hostname) || /(^|\.)example\.|REPLACE/i.test(url.hostname))) {
    wrong(`still holds a placeholder (${url.hostname})`);
  }
  return url.href;
}

/** Where "Get <app name>" points: the download link when set, else the Play Store link, else nowhere (''). */
export function getAppUrl(config) {
  return config.appDownloadUrl || config.playStoreUrl || '';
}

/** What a build made, for the terminal: the values that are easy to get wrong and hard to see later. */
export function summary({ outDir, config }) {
  const lines = [`Built ${outDir}${config.production ? ' (production)' : ''}`];
  if (config.project) lines.push(`  Pages project   ${config.project}, branch ${DEPLOY_BRANCH}`);
  lines.push(`  API_BASE        ${config.apiOrigin}`);
  lines.push(`  App name        ${config.appName}`);
  const getApp = getAppUrl(config);
  const source = config.appDownloadUrl ? 'download' : 'Play Store';
  lines.push(`  Get the app     ${getApp ? `${getApp} (${source})` : 'none (button hidden)'}`);
  lines.push(`  App Links       package ${config.androidPackage}`);
  if (config.certs.length) {
    lines.push(`                  ${config.certs.length} fingerprint${config.certs.length === 1 ? '' : 's'}:`);
    for (const cert of config.certs) lines.push(`                  ${cert}`);
  } else {
    lines.push(`                  no fingerprint (placeholder kept)`);
  }
  return lines;
}

export async function build({ env = process.env, production = false, deploy = false, outDir = join(here, 'dist') } = {}) {
  const config = readConfig(env, { production, deploy });
  const warnings = [];

  await rm(outDir, { recursive: true, force: true });
  await mkdir(outDir, { recursive: true });
  await cp(join(here, 'public'), outDir, { recursive: true, filter: (src) => !src.endsWith('.DS_Store') });

  const tokens = JSON.parse(await readFile(join(repo, 'design', 'spec', 'tokens.json'), 'utf8'));
  const typography = JSON.parse(await readFile(join(repo, 'design', 'spec', 'typography.json'), 'utf8'));
  await writeFile(join(outDir, 'tokens.css'), tokensCss(tokens, typography));

  await mkdir(join(outDir, 'icons'), { recursive: true });
  for (const icon of ICONS) {
    await cp(join(repo, 'design', 'icons', 'svg', `${icon}.svg`), join(outDir, 'icons', `${icon}.svg`));
  }

  const runtime = {
    apiBase: config.apiOrigin,
    appDownloadUrl: config.appDownloadUrl,
    playStoreUrl: config.playStoreUrl,
    appName: config.appName,
  };
  await writeFile(
    join(outDir, 'config.js'),
    `// Written by build.mjs. Read by pay.js.\nglobalThis.XPENSE_CONFIG = Object.freeze(${JSON.stringify(runtime)});\n`,
  );

  const headersPath = join(outDir, '_headers');
  const headers = await readFile(headersPath, 'utf8');
  if (!headers.includes('%API_ORIGIN%')) fail('public/_headers lost its %API_ORIGIN% placeholder');
  await writeFile(headersPath, headers.replaceAll('%API_ORIGIN%', config.apiOrigin));

  for (const file of await readdir(outDir)) {
    if (!file.endsWith('.html')) continue;
    const path = join(outDir, file);
    const html = await readFile(path, 'utf8');
    await writeFile(path, html.replaceAll('%APP_NAME%', escapeHtml(config.appName)));
  }

  const assetLinksPath = join(outDir, '.well-known', 'assetlinks.json');
  const assetLinks = JSON.parse(await readFile(assetLinksPath, 'utf8'));
  assetLinks[0].target.package_name = config.androidPackage;
  if (config.certs.length) {
    assetLinks[0].target.sha256_cert_fingerprints = config.certs;
  } else {
    warnings.push(`assetlinks.json keeps the placeholder fingerprint (${PLACEHOLDER_CERT}): App Links will not ` +
      'verify. Set ANDROID_CERT_SHA256 to the SHA-256 of each signing key and build again');
  }
  await writeFile(assetLinksPath, JSON.stringify(assetLinks, null, 2) + '\n');

  if (!getAppUrl(config)) warnings.push(`APP_DOWNLOAD_URL and PLAY_STORE_URL are empty: the "Get ${config.appName}" button is hidden`);
  return { outDir, config, warnings };
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  const args = process.argv.slice(2);
  const outIndex = args.indexOf('--out');
  const outDir = outIndex >= 0 ? args[outIndex + 1] : undefined;
  try {
    const result = await build({
      production: args.includes('--production'),
      deploy: args.includes('--deploy'),
      ...(outDir ? { outDir } : {}),
    });
    console.log(summary(result).join('\n'));
    for (const warning of result.warnings) console.warn(`warning: ${warning}`);
  } catch (error) {
    console.error(error.message);
    process.exit(1);
  }
}
