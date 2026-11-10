import {existsSync, readFileSync, realpathSync} from 'node:fs';
import {fileURLToPath} from 'node:url';

// Release versions omit the Git tag's v prefix and the development -SNAPSHOT suffix.
const releaseVersionPattern = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$/;
export const projectVersionPattern = /^[ \t]*version = "([^"]+)" \+ if \(!isRelease\) "-SNAPSHOT" else ""[ \t]*$/m;

export function parseVersion(version) {
  const match = typeof version === 'string' && version.match(releaseVersionPattern);
  const prerelease = match?.[4]?.split('.') ?? [];
  if (!match || version.endsWith('-SNAPSHOT') || prerelease.some(id => /^0\d+$/.test(id))) {
    throw new Error(`Invalid release version: ${version}. Expected e.g. 1.11.1 or 2.0.0-RC1`);
  }
  return {
    version,
    core: match.slice(1, 4),
    prerelease,
    releaseLine: `${match[1]}.${match[2]}.x`,
    isPrerelease: prerelease.length > 0,
  };
}

export function compareVersions(left, right) {
  const a = parseVersion(left);
  const b = parseVersion(right);
  const compare = (x, y) => x < y ? -1 : x > y ? 1 : 0;
  for (let i = 0; i < 3; i++) {
    const order = compare(BigInt(a.core[i]), BigInt(b.core[i]));
    if (order) return order;
  }
  if (!a.isPrerelease || !b.isPrerelease) {
    return compare(Number(b.isPrerelease), Number(a.isPrerelease));
  }
  for (let i = 0; i < Math.max(a.prerelease.length, b.prerelease.length); i++) {
    const x = a.prerelease[i];
    const y = b.prerelease[i];
    if (x === undefined) return -1;
    if (y === undefined) return 1;
    const numericX = /^\d+$/.test(x);
    const numericY = /^\d+$/.test(y);
    let order;
    if (numericX && numericY) order = compare(BigInt(x), BigInt(y));
    else if (numericX !== numericY) order = numericX ? -1 : 1;
    else order = compare(x, y);
    if (order) return order;
  }
  return 0;
}

export function readProjectVersion(contents) {
  const matches = [...contents.matchAll(new RegExp(projectVersionPattern.source, 'gm'))];
  if (matches.length !== 1) {
    throw new Error('Could not determine exactly one release version from build.gradle.kts');
  }
  return parseVersion(matches[0][1]).version;
}

if (process.argv[1] && existsSync(process.argv[1]) && realpathSync(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const [command, value, file] = process.argv.slice(2);
    if (command === 'validate') {
      parseVersion(value);
    } else if (command === 'current') {
      console.log(readProjectVersion(readFileSync(value, 'utf8')));
    } else if (command === 'check') {
      const expected = parseVersion(value).version;
      const actual = readProjectVersion(readFileSync(file, 'utf8'));
      if (expected !== actual) {
        throw new Error(`Tag version ${expected} does not match project version ${actual}`);
      }
    } else {
      throw new Error('Usage: release-version.mjs validate <version> | current <build-file> | check <version> <build-file>');
    }
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
