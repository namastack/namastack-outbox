import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {chmodSync, copyFileSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {dirname, join} from 'node:path';
import {test} from 'node:test';

function fixture(t, version = '1.11.0') {
  const root = mkdtempSync(join(tmpdir(), 'outbox-prepare-'));
  t.after(() => rmSync(root, {recursive: true, force: true}));
  const write = (name, value) => {
    mkdirSync(dirname(join(root, name)), {recursive: true});
    writeFileSync(join(root, name), value);
  };
  write('scripts/.keep', '');
  for (const name of ['prepare-release.mjs', 'release-version.mjs', 'current-version.sh']) {
    copyFileSync(new URL(name, import.meta.url), join(root, 'scripts', name));
  }
  write('build.gradle.kts', `    version = "${version}" + if (!isRelease) "-SNAPSHOT" else ""\n`);
  write('namastack-outbox-examples/gradle/libs.versions.toml', `namastackOutbox = "${version}-SNAPSHOT"\n`);
  write('namastack-outbox-performance-test/build.gradle.kts', `version = "${version}-SNAPSHOT"\n`);
  write('SECURITY.md', '| 1.11.x | Current release line (starting with 1.11.0) | :white_check_mark: |\n');
  write('namastack-outbox-docs/versions.json', '["1.11.x"]\n');
  write('namastack-outbox-docs/versioned_docs/version-1.11.x/old.md', 'published docs');
  write('namastack-outbox-docs/versioned_sidebars/version-1.11.x-sidebars.json', '{}');
  write('namastack-outbox-docs/vercel.json', '{"redirects":[\n  {"source": "/outbox/1.11.x/:path*", "destination": "/docs/:path*", "permanent": false},\n  {"source": "/other", "destination": "/other", "permanent": true}\n]}\n');
  // Simulate only Docusaurus' filesystem effects; no npm/network access in these tests.
  write('bin/npm', `#!/usr/bin/env node
const fs = require('fs');
const version = process.argv.at(-1);
const versions = JSON.parse(fs.readFileSync('versions.json'));
fs.mkdirSync('versioned_docs/version-'+version, {recursive: true});
fs.writeFileSync('versioned_docs/version-'+version+'/new.md', 'new docs');
fs.writeFileSync('versioned_sidebars/version-'+version+'-sidebars.json', '{}');
fs.writeFileSync('versions.json', JSON.stringify([version, ...versions]));
fs.writeFileSync('versioned-marker', version);
`);
  chmodSync(join(root, 'bin/npm'), 0o755);
  return {
    root,
    read: name => readFileSync(join(root, name), 'utf8'),
    run: target => spawnSync(process.execPath, [join(root, 'scripts/prepare-release.mjs'), target], {
      encoding: 'utf8', env: {...process.env, PATH: `${join(root, 'bin')}:${process.env.PATH}`},
    }),
  };
}

function assertVersion(f, version) {
  assert.equal(f.read('build.gradle.kts'), `    version = "${version}" + if (!isRelease) "-SNAPSHOT" else ""\n`);
  assert.equal(f.read('namastack-outbox-examples/gradle/libs.versions.toml'), `namastackOutbox = "${version}-SNAPSHOT"\n`);
  assert.equal(f.read('namastack-outbox-performance-test/build.gradle.kts'), `version = "${version}-SNAPSHOT"\n`);
  const current = spawnSync('bash', [join(f.root, 'scripts/current-version.sh')], {encoding: 'utf8'});
  assert.equal(current.status, 0, current.stderr);
  assert.equal(current.stdout.trim(), version);
}

test('1.11.1 preparation retains stable patch behavior and indentation', t => {
  const f = fixture(t);
  const security = f.read('SECURITY.md');
  const redirects = f.read('namastack-outbox-docs/vercel.json');
  const result = f.run('1.11.1');
  assert.equal(result.status, 0, result.stderr);
  assertVersion(f, '1.11.1');
  assert.equal(f.read('SECURITY.md'), security);
  assert.equal(f.read('namastack-outbox-docs/vercel.json'), redirects);
  assert.equal(f.read('namastack-outbox-docs/versioned-marker'), '1.11.x');
  assert.deepEqual(JSON.parse(f.read('namastack-outbox-docs/versions.json')), ['1.11.x']);
});

test('RC1, RC2, and final preparation update versions without promoting prerelease documentation', t => {
  const f = fixture(t);
  const security = f.read('SECURITY.md');
  const redirects = f.read('namastack-outbox-docs/vercel.json');
  for (const version of ['2.0.0-RC1', '2.0.0-RC2']) {
    const result = f.run(version);
    assert.equal(result.status, 0, result.stderr);
    assertVersion(f, version);
    assert.equal(f.read('SECURITY.md'), security);
    assert.equal(f.read('namastack-outbox-docs/vercel.json'), redirects);
    assert.deepEqual(JSON.parse(f.read('namastack-outbox-docs/versions.json')), ['1.11.x']);
    assert.equal(f.read('namastack-outbox-docs/versioned_docs/version-1.11.x/old.md'), 'published docs');
  }
  const final = f.run('2.0.0');
  assert.equal(final.status, 0, final.stderr);
  assertVersion(f, '2.0.0');
  assert.deepEqual(JSON.parse(f.read('namastack-outbox-docs/versions.json')), ['2.0.x', '1.11.x']);
  assert.match(f.read('SECURITY.md'), /2\.0\.x.*Current release line \(starting with 2\.0\.0\)/);
  assert.match(f.read('SECURITY.md'), /1\.11\.x.*End of security support/);
  assert.match(f.read('namastack-outbox-docs/vercel.json'), /\/outbox\/2\.0\.x/);
});

test('invalid, repeated, and backwards releases fail before changing files', t => {
  const f = fixture(t, '2.0.0-RC2');
  const before = f.read('build.gradle.kts');
  for (const version of ['1.11.1', '2.0.0-RC1', '2.0.0-RC2', '2.0.0-01', '2.0.0-SNAPSHOT']) {
    assert.notEqual(f.run(version).status, 0, version);
    assert.equal(f.read('build.gradle.kts'), before);
    assert.deepEqual(JSON.parse(f.read('namastack-outbox-docs/versions.json')), ['1.11.x']);
  }
});
