// What build.mjs writes: headers, redirects, privacy meta tags, no inline code, no third-party URLs, tokens from
// design/spec, fonts and icons present, and the production checks. Run with `npm test` (node --test).

import assert from 'node:assert/strict';
import { mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { after, before, describe, it } from 'node:test';
import { appNameFromStrings, build, DEPLOY_BRANCH, getAppUrl, ICONS, summary } from '../build.mjs';
import { cssVar } from '../scripts/tokens.mjs';

const repo = new URL('../../../', import.meta.url);
const tokens = JSON.parse(await readFile(new URL('design/spec/tokens.json', repo), 'utf8'));
const typography = JSON.parse(await readFile(new URL('design/spec/typography.json', repo), 'utf8'));

const DOWNLOAD = 'https://github.com/akhilux-design/sumbuddy/releases/latest/download/sumbuddy.apk';

let out;
const read = (path) => readFile(join(out, path), 'utf8');

before(async () => {
  out = await mkdtemp(join(tmpdir(), 'xpense-web-'));
  await build({
    outDir: out,
    env: {
      API_BASE: 'http://localhost:8787',
      APP_DOWNLOAD_URL: DOWNLOAD,
      PLAY_STORE_URL: 'https://play.google.com/store/apps/details?id=com.expenses.app',
      ANDROID_CERT_SHA256: Array.from({ length: 32 }, () => 'AB').join(':'),
    },
  });
});
after(() => rm(out, { recursive: true, force: true }));

describe('build output', () => {
  it('has every file of docs/server.md section 7', async () => {
    const top = await readdir(out);
    for (const file of ['pay.html', 'pay.js', 'pay.css', 'tokens.css', 'config.js', 'index.html', '404.html',
      'robots.txt', '_redirects', '_headers', '.well-known', 'fonts', 'icons']) {
      assert.ok(top.includes(file), file);
    }
    const fonts = await readdir(join(out, 'fonts'));
    assert.equal(fonts.filter((f) => f.endsWith('.woff2')).length, 12);
    assert.ok(fonts.includes('OFL-BricolageGrotesque.txt') && fonts.includes('OFL-DMSans.txt'));
    assert.deepEqual((await readdir(join(out, 'icons'))).sort(), ICONS.map((i) => `${i}.svg`).sort());
  });

  it('rewrites /pay/* to the page', async () => {
    // /pay, not /pay.html: Pages answers /pay.html with a 308 to /pay, which would drop the token from the address.
    assert.equal((await read('_redirects')).trim(), '/pay/*  /pay  200');
  });

  it('sends the privacy and security headers, with the API origin in connect-src', async () => {
    const headers = await read('_headers');
    const block = (path) => {
      const lines = headers.split('\n');
      const startAt = lines.indexOf(path);
      assert.ok(startAt >= 0, path);
      const rest = lines.slice(startAt + 1);
      const end = rest.findIndex((l) => !l.startsWith('  '));
      return rest.slice(0, end < 0 ? rest.length : end).map((l) => l.trim());
    };
    const all = block('/*');
    assert.ok(all.includes('X-Robots-Tag: noindex, nofollow'));
    assert.ok(all.includes('Referrer-Policy: no-referrer'));
    assert.ok(all.includes('X-Frame-Options: DENY'));
    assert.ok(all.includes('X-Content-Type-Options: nosniff'));
    assert.ok(all.includes("Content-Security-Policy: default-src 'self'; connect-src http://localhost:8787; img-src 'self' data:; " +
      "style-src 'self'; font-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"));
    assert.deepEqual(block('/pay/*'), ['Cache-Control: no-store']);
    assert.ok(!headers.includes('%API_ORIGIN%'));
  });

  it('keeps crawlers out', async () => {
    assert.equal(await read('robots.txt'), 'User-agent: *\nDisallow: /\n');
    for (const page of ['pay.html', 'index.html', '404.html']) {
      const html = await read(page);
      assert.match(html, /<meta name="robots" content="noindex, nofollow">/, page);
      assert.match(html, /<meta name="referrer" content="no-referrer">/, page);
      assert.ok(!html.includes('%APP_NAME%'), page);
    }
  });

  it('has no inline script or style, and loads nothing from another site', async () => {
    for (const page of ['pay.html', 'index.html', '404.html']) {
      const html = await read(page);
      for (const tag of html.match(/<script\b[^>]*>/g) ?? []) assert.match(tag, /\ssrc="\/[a-z]+\.js"/, `${page}: ${tag}`);
      assert.doesNotMatch(html, /<style\b|\sstyle=|\son[a-z]+=/i, page);
      assert.doesNotMatch(html, /(src|href)="(https?:)?\/\//i, page);
    }
    for (const file of ['pay.js', 'pay.css', 'tokens.css']) {
      assert.doesNotMatch(await read(file), /https?:\/\/[a-z0-9]/i, file);
    }
    const js = (await read('pay.js')).replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
    assert.doesNotMatch(js, /innerHTML|outerHTML|insertAdjacentHTML|document\.write|eval\(|new Function/);
    assert.doesNotMatch(js, /localStorage|sessionStorage|indexedDB|document\.cookie|console\./);
  });

  it('writes config.js from the environment', async () => {
    const config = await read('config.js');
    const json = config.slice(config.indexOf('freeze(') + 7, config.lastIndexOf(')'));
    assert.deepEqual(JSON.parse(json), {
      apiBase: 'http://localhost:8787',
      appDownloadUrl: DOWNLOAD,
      playStoreUrl: 'https://play.google.com/store/apps/details?id=com.expenses.app',
      appName: 'SumBuddy',
    });
  });

  it('fills assetlinks.json for App Links', async () => {
    const [entry] = JSON.parse(await read('.well-known/assetlinks.json'));
    assert.deepEqual(entry.relation, ['delegate_permission/common.handle_all_urls']);
    assert.equal(entry.target.package_name, 'com.expenses.app');
    assert.deepEqual(entry.target.sha256_cert_fingerprints, [Array.from({ length: 32 }, () => 'AB').join(':')]);
  });
});

describe('design tokens', () => {
  it('tokens.css has every colour token, light on :root and dark under prefers-color-scheme', async () => {
    const css = await read('tokens.css');
    const darkAt = css.indexOf('@media (prefers-color-scheme: dark)');
    assert.ok(darkAt > 0);
    const light = css.slice(0, darkAt);
    const dark = css.slice(darkAt, css.indexOf('}', css.indexOf('{', css.indexOf('{', darkAt) + 1)));
    for (const [name, value] of Object.entries(tokens.color)) {
      assert.ok(light.includes(`${cssVar(name)}: ${value.light};`), `${name} light`);
      assert.ok(dark.includes(`${cssVar(name)}: ${value.dark};`), `${name} dark`);
    }
    for (const [name, value] of Object.entries(tokens.size)) assert.ok(light.includes(`${cssVar(name)}: ${value}px;`), name);
  });

  it('every text style is at least 14px (0.875rem)', async () => {
    const css = await read('tokens.css');
    for (const style of typography) assert.ok(style.fontSize >= 14, style.name);
    for (const [, rem] of css.matchAll(/font-size: ([\d.]+)rem/g)) assert.ok(Number(rem) >= 0.875, rem);
  });

  it('pay.css uses only theme tokens for colours, and every variable it uses exists', async () => {
    const css = await read('pay.css');
    const defined = new Set(Array.from((await read('tokens.css')).matchAll(/(--[a-z0-9-]+):/g), (m) => m[1]));
    assert.doesNotMatch(css.replace(/\/\*[\s\S]*?\*\//g, ''), /#[0-9a-f]{3,8}\b|rgba?\(|hsla?\(|(?<![-\w])(white|black)(?![-\w])/i);
    for (const [, name] of css.matchAll(/var\((--[a-z0-9-]+)\)/g)) assert.ok(defined.has(name), name);
    for (const [, size] of css.matchAll(/font-size:\s*([\d.]+)(px|rem)/g)) assert.ok(Number(size) >= 0.875, size);
  });

  it('pay.css only points at fonts that are shipped', async () => {
    const css = await read('pay.css');
    const fonts = new Set(await readdir(join(out, 'fonts')));
    const used = Array.from(css.matchAll(/url\("\/fonts\/([^"]+)"\)/g), (m) => m[1]);
    assert.equal(used.length, 12);
    for (const file of used) assert.ok(fonts.has(file), file);
  });
});

describe('production build', () => {
  const attempt = (env) => build({ production: true, env, outDir: join(out, 'prod') });
  const base = { API_BASE: 'https://xpense-api.someone.workers.dev', ANDROID_PACKAGE: 'com.someone.split' };
  const fingerprint = (pair) => Array.from({ length: 32 }, () => pair).join(':');

  it('refuses a missing, local, plain-http or placeholder API address', async () => {
    for (const API_BASE of [undefined, 'http://localhost:8787', 'http://xpense-api.someone.workers.dev',
      'https://xpense-api.example.workers.dev', 'https://localhost', 'https://api.test/v1']) {
      const env = { ANDROID_PACKAGE: base.ANDROID_PACKAGE, ...(API_BASE === undefined ? {} : { API_BASE }) };
      await assert.rejects(attempt(env), /API_BASE/, String(API_BASE));
    }
  });
  it('refuses a malformed Play Store link or fingerprint', async () => {
    await assert.rejects(attempt({ ...base, PLAY_STORE_URL: 'https://evil.example/app' }), /PLAY_STORE_URL/);
    await assert.rejects(attempt({ ...base, ANDROID_CERT_SHA256: 'AB:CD' }), /ANDROID_CERT_SHA256/);
    // A SHA-1 (20 pairs), easy to paste from the sign-in setup by mistake.
    await assert.rejects(attempt({ ...base, ANDROID_CERT_SHA256: Array.from({ length: 20 }, () => 'AB').join(':') }), /SHA-1 has 20/);
  });
  it('needs ANDROID_PACKAGE set: no silent com.expenses.app', async () => {
    for (const ANDROID_PACKAGE of [undefined, '', '  ']) {
      const env = { API_BASE: base.API_BASE, ...(ANDROID_PACKAGE === undefined ? {} : { ANDROID_PACKAGE }) };
      await assert.rejects(attempt(env), /ANDROID_PACKAGE is required/, String(ANDROID_PACKAGE));
    }
    await assert.rejects(attempt({ ...base, ANDROID_PACKAGE: 'not a package' }), /ANDROID_PACKAGE is not a package name/);
    const result = await attempt({ ...base, ANDROID_PACKAGE: 'com.expenses.app' });
    assert.equal(result.config.androidPackage, 'com.expenses.app');
  });
  it('refuses a Play Store link for another package', async () => {
    await assert.rejects(
      attempt({ ...base, PLAY_STORE_URL: 'https://play.google.com/store/apps/details?id=com.expenses.app' }),
      /PLAY_STORE_URL is for com\.expenses\.app, but ANDROID_PACKAGE is com\.someone\.split/,
    );
    const result = await attempt({ ...base, PLAY_STORE_URL: 'https://play.google.com/store/apps/details?id=com.someone.split' });
    assert.equal(result.config.playStoreUrl, 'https://play.google.com/store/apps/details?id=com.someone.split');
  });
  it('takes an https download link, and refuses one that is not https, has a fragment or a user, or is a placeholder', async () => {
    const result = await attempt({ ...base, APP_DOWNLOAD_URL: ` ${DOWNLOAD} ` });
    assert.equal(result.config.appDownloadUrl, DOWNLOAD);
    for (const APP_DOWNLOAD_URL of ['http://github.com/a/b.apk', 'github.com/a/b.apk', 'ftp://github.com/a/b.apk',
      'https://github.com/a/b.apk#x', 'https://me:pw@github.com/a/b.apk', 'https://github.com/a b.apk',
      'https://github.com/a/"b.apk', 'https://localhost/b.apk', 'https://downloads.example.com/b.apk', 'javascript:alert(1)']) {
      await assert.rejects(attempt({ ...base, APP_DOWNLOAD_URL }), /APP_DOWNLOAD_URL/, APP_DOWNLOAD_URL);
    }
  });
  it('a development build takes a local https download link, never a plain http one', async () => {
    const dev = (env) => build({ env: { API_BASE: 'http://localhost:8787', ...env }, outDir: join(out, 'dev') });
    assert.equal((await dev({ APP_DOWNLOAD_URL: 'https://localhost/app.apk' })).config.appDownloadUrl, 'https://localhost/app.apk');
    await assert.rejects(dev({ APP_DOWNLOAD_URL: 'http://localhost/app.apk' }), /APP_DOWNLOAD_URL must use https/);
  });
  it('the download link wins over the Play Store link; with neither the button is hidden and the build warns', async () => {
    const play = 'https://play.google.com/store/apps/details?id=com.someone.split';
    const both = await attempt({ ...base, APP_DOWNLOAD_URL: DOWNLOAD, PLAY_STORE_URL: play });
    assert.equal(getAppUrl(both.config), DOWNLOAD);
    assert.equal(getAppUrl((await attempt({ ...base, PLAY_STORE_URL: play })).config), play);
    const neither = await attempt(base);
    assert.equal(getAppUrl(neither.config), '');
    assert.ok(neither.warnings.some((w) => w.includes('APP_DOWNLOAD_URL and PLAY_STORE_URL are empty')));
    assert.ok(!both.warnings.some((w) => w.includes('APP_DOWNLOAD_URL')));
    const config = await readFile(join(out, 'prod', 'config.js'), 'utf8');
    assert.match(config, /"appDownloadUrl":""/);
  });
  it('builds with real-looking values, and warns while the placeholder fingerprint stays', async () => {
    const result = await attempt({ ...base, API_BASE: 'https://xpense-api.someone.workers.dev/' });
    assert.equal(result.config.apiOrigin, 'https://xpense-api.someone.workers.dev');
    assert.ok(result.warnings.some((w) => w.includes('placeholder fingerprint') && w.includes('ANDROID_CERT_SHA256')));
    const [entry] = JSON.parse(await readFile(join(out, 'prod', '.well-known', 'assetlinks.json'), 'utf8'));
    assert.equal(entry.target.package_name, 'com.someone.split');
    assert.deepEqual(entry.target.sha256_cert_fingerprints, ['REPLACE_WITH_RELEASE_SIGNING_CERT_SHA256']);
  });
  it('takes every signing key, the debug key too, in order and without repeats', async () => {
    const play = fingerprint('AA'), upload = fingerprint('BB'), debug = fingerprint('cd');
    const result = await attempt({ ...base, ANDROID_CERT_SHA256: `${play}, ${upload},${debug},${play},` });
    assert.deepEqual(result.warnings.filter((w) => w.includes('fingerprint')), []);
    const [entry] = JSON.parse(await readFile(join(out, 'prod', '.well-known', 'assetlinks.json'), 'utf8'));
    assert.equal(entry.target.package_name, 'com.someone.split');
    assert.deepEqual(entry.target.sha256_cert_fingerprints, [play, upload, debug.toUpperCase()]);
  });
});

describe('build summary', () => {
  it('prints the package, every fingerprint and, for a deploy, the project and branch', () => {
    const certs = [Array.from({ length: 32 }, () => 'AA').join(':'), Array.from({ length: 32 }, () => 'BB').join(':')];
    const config = {
      apiOrigin: 'https://xpense-api.someone.workers.dev', appDownloadUrl: '', playStoreUrl: '', appName: 'SumBuddy',
      androidPackage: 'com.someone.split', certs, production: true, project: 'xpense-link',
    };
    const text = summary({ outDir: 'dist', config }).join('\n');
    assert.match(text, /Built dist \(production\)/);
    assert.match(text, new RegExp(`Pages project +xpense-link, branch ${DEPLOY_BRANCH}`));
    assert.match(text, /package com\.someone\.split/);
    assert.match(text, /2 fingerprints:/);
    for (const cert of certs) assert.ok(text.includes(cert), cert);
    assert.match(text, /none \(button hidden\)/);

    const local = summary({ outDir: 'dist', config: { ...config, certs: [], production: false, project: undefined } }).join('\n');
    assert.doesNotMatch(local, /production|Pages project/);
    assert.match(local, /no fingerprint \(placeholder kept\)/);

    const play = 'https://play.google.com/store/apps/details?id=com.someone.split';
    const withPlay = summary({ outDir: 'dist', config: { ...config, playStoreUrl: play } }).join('\n');
    assert.ok(withPlay.includes(`Get the app     ${play} (Play Store)`));
    const withBoth = summary({ outDir: 'dist', config: { ...config, playStoreUrl: play, appDownloadUrl: DOWNLOAD } }).join('\n');
    assert.ok(withBoth.includes(`Get the app     ${DOWNLOAD} (download)`));
  });
});

describe('app name', () => {
  it('comes from app_name in the Android strings.xml unless APP_NAME is set', async () => {
    const strings = await readFile(new URL('app/src/main/res/values/strings.xml', repo), 'utf8');
    const expected = /<string name="app_name">([^<]*)<\/string>/.exec(strings)[1];
    assert.equal(appNameFromStrings(), expected);
    const file = join(out, 'strings.xml');
    await writeFile(file, '<resources>\n  <string name="app_name">Kharcha \\\'n &amp; Co</string>\n</resources>\n');
    assert.equal(appNameFromStrings(file), "Kharcha 'n & Co");
    const html = await read('index.html');
    assert.ok(!html.includes('%APP_NAME%'));
  });
});
