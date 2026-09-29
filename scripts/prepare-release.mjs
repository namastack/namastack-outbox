#!/usr/bin/env node

import {execFileSync} from 'node:child_process';
import {readFileSync, rmSync, writeFileSync} from 'node:fs';
import {dirname, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';

const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const version = process.argv[2];

if (!version || !/^\d+\.\d+\.\d+$/.test(version)) {
  throw new Error('Usage: node scripts/prepare-release.mjs <major.minor.patch>');
}

const files = {
  build: resolve(repositoryRoot, 'build.gradle.kts'),
  examples: resolve(repositoryRoot, 'namastack-outbox-examples/gradle/libs.versions.toml'),
  performance: resolve(repositoryRoot, 'namastack-outbox-performance-test/build.gradle.kts'),
  security: resolve(repositoryRoot, 'SECURITY.md'),
  docsVersions: resolve(repositoryRoot, 'namastack-outbox-docs/versions.json'),
  vercel: resolve(repositoryRoot, 'namastack-outbox-docs/vercel.json'),
};

const contents = Object.fromEntries(
  Object.entries(files).map(([name, path]) => [name, readFileSync(path, 'utf8')]),
);

const buildVersionPattern =
  /version = "(\d+\.\d+\.\d+)" \+ if \(!isRelease\) "-SNAPSHOT" else ""/;
const currentVersion = matchExactlyOnce(contents.build, buildVersionPattern, 'root build version')[1];

if (compareVersions(version, currentVersion) <= 0) {
  throw new Error(`Release version ${version} must be newer than ${currentVersion}`);
}

const releaseLine = `${version.split('.').slice(0, 2).join('.')}.x`;
const docsVersions = JSON.parse(contents.docsVersions);
const createsDocumentationVersion = !docsVersions.includes(releaseLine);
const versionedDocsPath = resolve(
  repositoryRoot,
  'namastack-outbox-docs/versioned_docs',
  `version-${releaseLine}`,
);
const versionedSidebarPath = resolve(
  repositoryRoot,
  'namastack-outbox-docs/versioned_sidebars',
  `version-${releaseLine}-sidebars.json`,
);

matchExactlyOnce(
  contents.examples,
  /namastackOutbox = "\d+\.\d+\.\d+-SNAPSHOT"/,
  'example dependency version',
);
matchExactlyOnce(
  contents.performance,
  /version = "\d+\.\d+\.\d+-SNAPSHOT"/,
  'performance-test version',
);

let security = contents.security;
let vercel = contents.vercel;
if (createsDocumentationVersion) {
  const previousReleaseLine = docsVersions[0];
  if (typeof previousReleaseLine !== 'string') {
    throw new Error('Expected versions.json to contain the current documentation release line');
  }

  const currentSecurityLine =
    /^\|\s*(\d+\.\d+\.x)\s*\|\s*Current release line \(starting with (\d+\.\d+\.\d+)\)\s*\|\s*:white_check_mark:\s*\|$/m;
  const securityMatch = matchExactlyOnce(security, currentSecurityLine, 'current security release line');
  if (securityMatch[1] !== previousReleaseLine) {
    throw new Error(
      `Current security line ${securityMatch[1]} does not match latest documentation line ${previousReleaseLine}`,
    );
  }
  const currentStatus = `Current release line (starting with ${version})`;

  security = security.replace(
    currentSecurityLine,
    `${securityRow(releaseLine, currentStatus, ':white_check_mark:')}\n` +
      securityRow(previousReleaseLine, 'End of security support', ':x:'),
  );

  const latestRedirectPattern = new RegExp(
    `^(\\s*)\\{"source": "/outbox/${escapeRegExp(previousReleaseLine)}/:path\\*", ` +
      '"destination": "/docs/:path\\*", "permanent": false\\},$',
    'm',
  );
  const latestRedirectMatch = matchExactlyOnce(
    vercel,
    latestRedirectPattern,
    `latest documentation redirect for ${previousReleaseLine}`,
  );
  const redirectIndent = latestRedirectMatch[1];
  vercel = vercel.replace(
    latestRedirectPattern,
    `${redirectIndent}{"source": "/outbox/${releaseLine}/:path*", "destination": "/docs/:path*", "permanent": false},\n` +
      `${redirectIndent}{"source": "/outbox/${previousReleaseLine}/:path*", ` +
      `"destination": "/docs/${previousReleaseLine}/:path*", "permanent": true},`,
  );
} else {
  rmSync(versionedDocsPath, {recursive: true});
  rmSync(versionedSidebarPath);
  writeFileSync(
    files.docsVersions,
    `${JSON.stringify(docsVersions.filter((existingLine) => existingLine !== releaseLine), null, 2)}\n`,
  );
}

execFileSync('npm', ['run', 'docusaurus', '--', 'docs:version', releaseLine], {
  cwd: resolve(repositoryRoot, 'namastack-outbox-docs'),
  stdio: 'inherit',
});

writeFileSync(
  files.build,
  contents.build.replace(
    buildVersionPattern,
    `version = "${version}" + if (!isRelease) "-SNAPSHOT" else ""`,
  ),
);
writeFileSync(
  files.examples,
  contents.examples.replace(
    /namastackOutbox = "\d+\.\d+\.\d+-SNAPSHOT"/,
    `namastackOutbox = "${version}-SNAPSHOT"`,
  ),
);
writeFileSync(
  files.performance,
  contents.performance.replace(
    /version = "\d+\.\d+\.\d+-SNAPSHOT"/,
    `version = "${version}-SNAPSHOT"`,
  ),
);
if (createsDocumentationVersion) {
  writeFileSync(files.security, security);
  writeFileSync(files.vercel, vercel);
}

console.log(`Prepared Namastack Outbox ${version}`);
console.log(
  createsDocumentationVersion
    ? `Created documentation version ${releaseLine}`
    : `Refreshed documentation version ${releaseLine}`,
);

function matchExactlyOnce(content, pattern, description) {
  const globalPattern = new RegExp(pattern.source, `${pattern.flags.replace('g', '')}g`);
  const matches = [...content.matchAll(globalPattern)];
  if (matches.length !== 1) {
    throw new Error(`Expected exactly one ${description}, found ${matches.length}`);
  }
  return matches[0];
}

function compareVersions(left, right) {
  const leftParts = left.split('.').map(Number);
  const rightParts = right.split('.').map(Number);
  for (let index = 0; index < leftParts.length; index += 1) {
    if (leftParts[index] !== rightParts[index]) {
      return leftParts[index] - rightParts[index];
    }
  }
  return 0;
}

function securityRow(releaseLine, status, supported) {
  return `| ${releaseLine.padEnd(17)} | ${status.padEnd(42)} | ${supported.padEnd(18)} |`;
}

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
