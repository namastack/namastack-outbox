#!/usr/bin/env node

import {readFileSync, writeFileSync} from 'node:fs';
import {dirname, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {compareVersions, parseVersion, projectVersionPattern, readProjectVersion} from './release-version.mjs';

const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const version = process.argv[2];

const targetVersion = parseVersion(version);

const files = {
  build: resolve(repositoryRoot, 'build.gradle.kts'),
  examples: resolve(repositoryRoot, 'namastack-outbox-examples/gradle/libs.versions.toml'),
  performance: resolve(repositoryRoot, 'namastack-outbox-performance-test/build.gradle.kts'),
  security: resolve(repositoryRoot, 'SECURITY.md'),
};

const contents = Object.fromEntries(
  Object.entries(files).map(([name, path]) => [name, readFileSync(path, 'utf8')]),
);

const currentVersion = readProjectVersion(contents.build);

if (compareVersions(version, currentVersion) <= 0) {
  throw new Error(`Release version ${version} must be newer than ${currentVersion}`);
}

const releaseLine = targetVersion.releaseLine;
const exampleVersionPattern = /namastackOutbox = "([^"]+)-SNAPSHOT"/;
const performanceVersionPattern = /version = "([^"]+)-SNAPSHOT"/;
parseVersion(matchExactlyOnce(contents.examples, exampleVersionPattern, 'example dependency version')[1]);
parseVersion(matchExactlyOnce(contents.performance, performanceVersionPattern, 'performance-test version')[1]);

if (!targetVersion.isPrerelease) {
  const currentSecurityLine =
    /^\|\s*(\d+\.\d+\.x)\s*\|\s*Current release line \(starting with ([^\s)]+)\)\s*\|\s*:white_check_mark:\s*\|$/m;
  const securityMatch = matchExactlyOnce(contents.security, currentSecurityLine, 'current security release line');
  const previousReleaseLine = securityMatch[1];
  if (releaseLine !== previousReleaseLine) {
    const security = contents.security.replace(
      currentSecurityLine,
      `${securityRow(releaseLine, `Current release line (starting with ${version})`, ':white_check_mark:')}\n` +
        securityRow(previousReleaseLine, 'End of security support', ':x:'),
    );
    writeFileSync(files.security, security);
  }
}

writeFileSync(
  files.build,
  contents.build.replace(projectVersionPattern, line =>
    line.replace(`"${currentVersion}"`, `"${version}"`),
  ),
);
writeFileSync(
  files.examples,
  contents.examples.replace(
    exampleVersionPattern,
    `namastackOutbox = "${version}-SNAPSHOT"`,
  ),
);
writeFileSync(
  files.performance,
  contents.performance.replace(
    performanceVersionPattern,
    `version = "${version}-SNAPSHOT"`,
  ),
);
console.log(`Prepared Namastack Outbox ${version}`);
console.log('Website documentation is maintained and published independently in namastack/namastack-website');

function matchExactlyOnce(content, pattern, description) {
  const globalPattern = new RegExp(pattern.source, `${pattern.flags.replace('g', '')}g`);
  const matches = [...content.matchAll(globalPattern)];
  if (matches.length !== 1) {
    throw new Error(`Expected exactly one ${description}, found ${matches.length}`);
  }
  return matches[0];
}

function securityRow(releaseLine, status, supported) {
  return `| ${releaseLine.padEnd(17)} | ${status.padEnd(42)} | ${supported.padEnd(18)} |`;
}
