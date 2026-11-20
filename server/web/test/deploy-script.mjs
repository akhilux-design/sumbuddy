// npm run deploy: the build checks PAGES_PROJECT before anything else, and wrangler gets the project name and
// --branch main. The real wrangler never runs here: a shell function named wrangler stands in for it and prints its
// arguments. Run with `npm test` (node --test).

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { after, before, describe, it } from 'node:test';
import { DEPLOY_BRANCH, pagesProject } from '../build.mjs';

const web = join(dirname(fileURLToPath(import.meta.url)), '..');
const script = JSON.parse(await readFile(join(web, 'package.json'), 'utf8')).scripts.deploy;
const steps = script.split(' && ');
const fakeWrangler =
  'wrangler() { printf "WRANGLER metrics=%s" "$WRANGLER_SEND_METRICS"; for a in "$@"; do printf " [%s]" "$a"; done; printf "\\n"; }; ';
const fingerprint = (pair) => Array.from({ length: 32 }, () => pair).join(':');

/** Runs shell text the way npm does, with only the variables given (no PAGES_PROJECT from the caller's shell). */
function sh(text, vars) {
  return spawnSync('/bin/sh', ['-c', fakeWrangler + text], {
    cwd: web,
    env: { PATH: process.env.PATH, HOME: process.env.HOME, ...vars },
    encoding: 'utf8',
  });
}

/** Runs build.mjs as npm run deploy does, into a scratch folder. */
function buildCli(args, vars) {
  return spawnSync(process.execPath, ['build.mjs', ...args], {
    cwd: web,
    env: { PATH: process.env.PATH, HOME: process.env.HOME, ...vars },
    encoding: 'utf8',
  });
}

let scratch;
before(async () => {
  scratch = await mkdtemp(join(tmpdir(), 'xpense-web-deploy-'));
});
after(() => rm(scratch, { recursive: true, force: true }));

describe('npm run deploy', () => {
  it('builds with --deploy first, then deploys dist to the project on branch main', () => {
    assert.equal(steps.length, 2, script);
    assert.equal(steps[0], 'node build.mjs --production --deploy');
    assert.equal(steps[1],
      `WRANGLER_SEND_METRICS=false wrangler pages deploy dist --project-name "$PAGES_PROJECT" --branch ${DEPLOY_BRANCH}`);
    assert.equal(DEPLOY_BRANCH, 'main');
    // --force skips wrangler's own checks; the owner decides on it, never the script.
    assert.doesNotMatch(script, /--force/);
  });

  it('stops before the build and before wrangler when PAGES_PROJECT is missing or not a project name', () => {
    // API_BASE is left out on purpose: if the PAGES_PROJECT check were gone, the build would still stop, on API_BASE,
    // and the message check below would fail. Nothing is written to dist/ either way.
    for (const PAGES_PROJECT of [undefined, '', 'xpense.pages.dev', 'https://xpense.pages.dev', ' xpense', 'Xpense',
      '-xpense', 'xpense-']) {
      const run = sh(script, PAGES_PROJECT === undefined ? {} : { PAGES_PROJECT });
      assert.equal(run.status, 1, String(PAGES_PROJECT));
      assert.match(run.stderr, /PAGES_PROJECT/, String(PAGES_PROJECT));
      assert.doesNotMatch(run.stdout, /WRANGLER/, String(PAGES_PROJECT));
    }
  });

  it('hands wrangler the project name, branch main and no metrics', () => {
    const run = sh(steps[1], { PAGES_PROJECT: 'xpense-link' });
    assert.equal(run.status, 0, run.stderr);
    assert.equal(run.stdout,
      'WRANGLER metrics=false [pages] [deploy] [dist] [--project-name] [xpense-link] [--branch] [main]\n');
  });
});

describe('build.mjs --deploy', () => {
  const ready = {
    API_BASE: 'https://xpense-api.someone.workers.dev',
    ANDROID_PACKAGE: 'com.someone.split',
    ANDROID_CERT_SHA256: `${fingerprint('AA')},${fingerprint('BB')}`,
  };

  it('accepts a plain project name', () => {
    for (const name of ['xpense', 'xpense-link', 'x1', 'a']) assert.equal(pagesProject({ PAGES_PROJECT: name }), name);
    assert.throws(() => pagesProject({}), /PAGES_PROJECT is empty/);
    assert.throws(() => pagesProject({ PAGES_PROJECT: 'xpense.pages.dev' }), /not a host or a URL/);
    assert.throws(() => pagesProject({ PAGES_PROJECT: 'x'.repeat(64) }), /PAGES_PROJECT/);
  });

  it('fails before writing anything when PAGES_PROJECT is empty, even with every other value set', () => {
    const out = join(scratch, 'empty-project');
    const run = buildCli(['--deploy', '--out', out], { ...ready, PAGES_PROJECT: '' });
    assert.equal(run.status, 1);
    assert.match(run.stderr, /PAGES_PROJECT is empty/);
    assert.ok(!existsSync(out), 'nothing built');
  });

  it('is a production build: refuses a local API and needs ANDROID_PACKAGE', () => {
    const local = buildCli(['--deploy', '--out', join(scratch, 'local')],
      { ...ready, PAGES_PROJECT: 'xpense-link', API_BASE: 'http://localhost:8787' });
    assert.equal(local.status, 1);
    assert.match(local.stderr, /API_BASE must use https/);
    const noPackage = buildCli(['--deploy', '--out', join(scratch, 'no-package')],
      { ...ready, PAGES_PROJECT: 'xpense-link', ANDROID_PACKAGE: '' });
    assert.equal(noPackage.status, 1);
    assert.match(noPackage.stderr, /ANDROID_PACKAGE is required/);
    assert.match(noPackage.stderr, /app\.applicationId in gradle\.properties/, 'says where the value is');
  });

  it('prints the project, branch, package and fingerprints it built for', async () => {
    const out = join(scratch, 'ready');
    const run = buildCli(['--production', '--deploy', '--out', out], { ...ready, PAGES_PROJECT: 'xpense-link' });
    assert.equal(run.status, 0, run.stderr);
    assert.match(run.stdout, /Pages project +xpense-link, branch main/);
    assert.match(run.stdout, /package com\.someone\.split/);
    assert.ok(run.stdout.includes(fingerprint('AA')) && run.stdout.includes(fingerprint('BB')));
    assert.match(run.stderr, /APP_DOWNLOAD_URL and PLAY_STORE_URL are empty/);
    assert.doesNotMatch(run.stderr, /placeholder fingerprint/);
    const [entry] = JSON.parse(await readFile(join(out, '.well-known', 'assetlinks.json'), 'utf8'));
    assert.equal(entry.target.package_name, 'com.someone.split');
    assert.deepEqual(entry.target.sha256_cert_fingerprints, [fingerprint('AA'), fingerprint('BB')]);
  });

  it('prints the download link and puts it in config.js (docs/server-setup.md 4.3)', async () => {
    const out = join(scratch, 'download');
    const APP_DOWNLOAD_URL = 'https://github.com/akhilux-design/sumbuddy/releases/latest/download/sumbuddy.apk';
    const run = buildCli(['--production', '--deploy', '--out', out], { ...ready, PAGES_PROJECT: 'xpense-link', APP_DOWNLOAD_URL });
    assert.equal(run.status, 0, run.stderr);
    assert.ok(run.stdout.includes(`Get the app     ${APP_DOWNLOAD_URL} (download)`), run.stdout);
    assert.doesNotMatch(run.stderr, /APP_DOWNLOAD_URL/);
    assert.ok((await readFile(join(out, 'config.js'), 'utf8')).includes(`"appDownloadUrl":"${APP_DOWNLOAD_URL}"`));
    const bad = buildCli(['--production', '--deploy', '--out', join(scratch, 'bad-download')],
      { ...ready, PAGES_PROJECT: 'xpense-link', APP_DOWNLOAD_URL: 'http://github.com/a.apk' });
    assert.notEqual(bad.status, 0);
    assert.match(bad.stderr, /APP_DOWNLOAD_URL must use https/);
  });

  it('still builds without fingerprints, with the placeholder warning', () => {
    const run = buildCli(['--deploy', '--out', join(scratch, 'no-certs')],
      { ...ready, PAGES_PROJECT: 'xpense-link', ANDROID_CERT_SHA256: '' });
    assert.equal(run.status, 0, run.stderr);
    assert.match(run.stdout, /no fingerprint \(placeholder kept\)/);
    assert.match(run.stderr, /warning: assetlinks\.json keeps the placeholder fingerprint/);
  });
});
