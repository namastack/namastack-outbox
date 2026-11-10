import assert from 'node:assert/strict';
import {mkdtempSync, readFileSync, rmSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {spawnSync} from 'node:child_process';
import {test} from 'node:test';
import {compareVersions, parseVersion, readProjectVersion} from './release-version.mjs';

test('stable and prerelease versions share the same parsing rules', () => {
  for (const version of ['1.11.1', '2.0.0-RC1', '2.0.0-rc.1', '2.0.0-beta.2', '2.0.0-0']) {
    assert.equal(parseVersion(version).version, version);
    assert.equal(parseVersion(version).isPrerelease, version.includes('-'));
  }
  assert.equal(parseVersion('2.0.0-RC1').releaseLine, '2.0.x');
  for (const version of [undefined, '', 'v1.11.1', '1.11', '01.11.1', '2.0.0-01',
    '2.0.0-RC1..x', '2.0.0-SNAPSHOT', '2.0.0-RC1-SNAPSHOT', '2.0.0-RC1\nother']) {
    assert.throws(() => parseVersion(version), /Invalid release version/);
  }
});

test('version ordering permits RC promotion and compares numeric identifiers numerically', () => {
  const ordered = ['1.11.1', '1.12.0', '2.0.0-alpha', '2.0.0-alpha.1',
    '2.0.0-alpha.2', '2.0.0-alpha.10', '2.0.0-alpha.beta', '2.0.0-beta', '2.0.0-rc.1',
    '2.0.0-rc.2', '2.0.0', '2.0.1'];
  for (let i = 0; i < ordered.length; i++) {
    assert.equal(compareVersions(ordered[i], ordered[i]), 0);
    for (let j = i + 1; j < ordered.length; j++) {
      assert.equal(compareVersions(ordered[i], ordered[j]), -1);
      assert.equal(compareVersions(ordered[j], ordered[i]), 1);
    }
  }
  assert.equal(compareVersions('2.0.0-RC1', '2.0.0-RC2'), -1);
  assert.equal(compareVersions('2.0.0-RC2', '2.0.0'), -1);
});

test('project version extraction validates one declaration and ignores comments', () => {
  const build = version => `    version = "${version}" + if (!isRelease) "-SNAPSHOT" else ""\n`;
  assert.equal(readProjectVersion(build('1.11.1')), '1.11.1');
  assert.equal(readProjectVersion(build('2.0.0-RC1')), '2.0.0-RC1');
  assert.equal(readProjectVersion('// '+build('1.11.0')+build('1.11.1')), '1.11.1');
  assert.throws(() => readProjectVersion(''), /exactly one/);
  assert.throws(() => readProjectVersion(build('1.11.1')+build('1.11.1')), /exactly one/);
  assert.throws(() => readProjectVersion(build('2.0.0-01')), /Invalid release version/);
});

test('publishing checks older tagged sources using a standalone copy of the helper', t => {
  const directory = mkdtempSync(join(tmpdir(), 'outbox-version-'));
  t.after(() => rmSync(directory, {recursive: true, force: true}));
  const helper = join(directory, 'release-version.mjs');
  const build = join(directory, 'build.gradle.kts');
  writeFileSync(helper, readFileSync(new URL('./release-version.mjs', import.meta.url)));
  const run = (...args) => spawnSync(process.execPath, [helper, ...args], {encoding: 'utf8'});
  for (const version of ['1.11.1', '2.0.0-RC1']) {
    writeFileSync(build, `version = "${version}" + if (!isRelease) "-SNAPSHOT" else ""\n`);
    assert.equal(run('validate', version).status, 0);
    assert.equal(run('current', build).stdout.trim(), version);
    assert.equal(run('check', version, build).status, 0);
    const mismatch = run('check', '3.0.0', build);
    assert.notEqual(mismatch.status, 0);
    assert.match(mismatch.stderr, /does not match project version/);
  }
  assert.notEqual(run('validate', '2.0.0-SNAPSHOT').status, 0);
});
