const fs = require('fs');
const path = require('path');

/**
 * Keeps versioned documentation SEO signals aligned with the latest docs,
 * emits robots.txt, and generates static fallback redirects for the former
 * /outbox/* documentation routes.
 *
 * Vercel handles redirects before static files in production. The generated
 * files keep the migration working on any static host as well.
 */
module.exports = function siteMigrationPlugin(context) {
  const {siteConfig} = context;
  const siteUrl = siteConfig.url;
  const baseUrl = siteConfig.baseUrl;
  const docsPath = 'docs';

  const versionsPath = path.join(context.siteDir, 'versions.json');
  const versions = JSON.parse(fs.readFileSync(versionsPath, 'utf-8'));
  const olderVersions = versions.slice(1);
  const versionPrefixes = [...olderVersions, 'next'];
  const builtVersions = [
    {version: versions[0], prefix: ''},
    ...olderVersions.map((version) => ({version, prefix: version})),
    {version: 'next', prefix: 'next'},
  ];

  return {
    name: 'site-migration-plugin',

    async postBuild({outDir}) {
      const docsDir = path.join(outDir, docsPath);

      rewriteVersionCanonicals(docsDir, versionPrefixes, siteUrl, baseUrl, docsPath);
      createLegacyDocumentationRedirects(docsDir, outDir, siteUrl, baseUrl, docsPath);
      createMergedReferenceRedirects(outDir, builtVersions, siteUrl, baseUrl, docsPath);
      writeRobotsTxt(outDir, versionPrefixes, siteUrl, baseUrl, docsPath);
    },
  };
};

function rewriteVersionCanonicals(docsDir, versionPrefixes, siteUrl, baseUrl, docsPath) {
  for (const prefix of versionPrefixes) {
    const versionDir = path.join(docsDir, prefix);
    if (!fs.existsSync(versionDir)) continue;

    for (const filePath of findHtmlFiles(versionDir)) {
      const html = fs.readFileSync(filePath, 'utf-8');
      const versionRoot = `${siteUrl}${baseUrl}${docsPath}/${prefix}/`;
      const latestRoot = `${siteUrl}${baseUrl}${docsPath}/`;
      const canonicalRegex = new RegExp(
        `(<link[^>]*rel="canonical"[^>]*href=")${escapeRegExp(versionRoot)}([^"]*")`
      );
      const nextHtml = html.replace(canonicalRegex, `$1${latestRoot}$2`);

      if (nextHtml !== html) {
        fs.writeFileSync(filePath, nextHtml, 'utf-8');
      }
    }
  }
}

function createLegacyDocumentationRedirects(docsDir, outDir, siteUrl, baseUrl, docsPath) {
  if (!fs.existsSync(docsDir)) return;

  for (const sourceFile of findHtmlFiles(docsDir)) {
    const relativePath = path.relative(docsDir, sourceFile);

    // /outbox/ is the product page in the new information architecture.
    if (relativePath === 'index.html') continue;

    const redirectFile = path.join(outDir, 'outbox', relativePath);
    const routePath = relativePath.replace(/index\.html$/, '').replace(/\\/g, '/');
    const targetPath = `${baseUrl}${docsPath}/${routePath}`;
    const canonicalUrl = new URL(targetPath, siteUrl).toString();

    fs.mkdirSync(path.dirname(redirectFile), {recursive: true});
    fs.writeFileSync(
      redirectFile,
      [
        '<!doctype html>',
        '<html lang="en">',
        '<head>',
        '<meta charset="utf-8">',
        '<meta name="robots" content="noindex">',
        `<link rel="canonical" href="${canonicalUrl}">`,
        `<meta http-equiv="refresh" content="0; url=${targetPath}">`,
        '<title>Documentation moved</title>',
        '</head>',
        '<body>',
        `<p>This documentation moved to <a href="${targetPath}">${targetPath}</a>.</p>`,
        `<script>location.replace(${JSON.stringify(targetPath)} + location.search + location.hash);</script>`,
        '</body>',
        '</html>',
      ].join('\n'),
      'utf-8'
    );
  }
}

function createMergedReferenceRedirects(
  outDir,
  builtVersions,
  siteUrl,
  baseUrl,
  docsPath
) {
  const redirects = [
    {
      source: 'mongodb-schema',
      target: 'database',
      anchor: 'mongodb-schema',
      sinceVersion: '1.5.x',
    },
    {
      source: 'rabbitmq',
      target: 'messaging',
      anchor: 'rabbitmq-integration',
      sinceVersion: '1.7.x',
    },
  ];

  for (const {version, prefix} of builtVersions) {
    for (const redirect of redirects) {
      if (!isVersionAtLeast(version, redirect.sinceVersion)) continue;

      const versionPath = prefix ? `${prefix}/` : '';
      const targetFile = path.join(
        outDir,
        docsPath,
        prefix,
        'reference',
        redirect.target,
        'index.html'
      );
      if (!fs.existsSync(targetFile)) continue;

      const targetPage = `${baseUrl}${docsPath}/${versionPath}reference/${redirect.target}/`;
      const targetPath = `${targetPage}#${redirect.anchor}`;
      const docsRedirectFile = path.join(
        outDir,
        docsPath,
        prefix,
        'reference',
        redirect.source,
        'index.html'
      );
      const outboxRedirectFile = path.join(
        outDir,
        'outbox',
        prefix,
        'reference',
        redirect.source,
        'index.html'
      );

      writeStaticRedirect(docsRedirectFile, targetPage, targetPath, siteUrl);
      writeStaticRedirect(outboxRedirectFile, targetPage, targetPath, siteUrl);
    }
  }
}

function writeStaticRedirect(filePath, targetPage, targetPath, siteUrl) {
  const canonicalUrl = new URL(targetPage, siteUrl).toString();

  fs.mkdirSync(path.dirname(filePath), {recursive: true});
  fs.writeFileSync(
    filePath,
    [
      '<!doctype html>',
      '<html lang="en">',
      '<head>',
      '<meta charset="utf-8">',
      '<meta name="robots" content="noindex">',
      `<link rel="canonical" href="${canonicalUrl}">`,
      `<meta http-equiv="refresh" content="0; url=${targetPath}">`,
      '<title>Documentation moved</title>',
      '</head>',
      '<body>',
      `<p>This documentation moved to <a href="${targetPath}">${targetPath}</a>.</p>`,
      `<script>const target=new URL(${JSON.stringify(targetPath)},location.origin);target.search=location.search;location.replace(target.toString());</script>`,
      '</body>',
      '</html>',
    ].join('\n'),
    'utf-8'
  );
}

function isVersionAtLeast(version, minimumVersion) {
  if (version === 'next') return true;

  const parse = (value) => value.split('.').map((part) => Number.parseInt(part, 10) || 0);
  const current = parse(version);
  const minimum = parse(minimumVersion);

  for (let index = 0; index < Math.max(current.length, minimum.length); index += 1) {
    const currentPart = current[index] || 0;
    const minimumPart = minimum[index] || 0;
    if (currentPart !== minimumPart) return currentPart > minimumPart;
  }

  return true;
}

function writeRobotsTxt(outDir, versionPrefixes, siteUrl, baseUrl, docsPath) {
  const disallowRules = versionPrefixes
    .map((prefix) => `Disallow: ${baseUrl}${docsPath}/${prefix}/`)
    .join('\n');

  const robotsTxt = [
    'User-agent: *',
    '',
    `Allow: ${baseUrl}`,
    '',
    '# Block old and unreleased versioned docs',
    disallowRules,
    '',
    `Sitemap: ${siteUrl}${baseUrl}sitemap.xml`,
    '',
  ].join('\n');

  fs.writeFileSync(path.join(outDir, 'robots.txt'), robotsTxt, 'utf-8');
}

function findHtmlFiles(dir) {
  const results = [];
  for (const entry of fs.readdirSync(dir, {withFileTypes: true})) {
    const fullPath = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      results.push(...findHtmlFiles(fullPath));
    } else if (entry.name.endsWith('.html')) {
      results.push(fullPath);
    }
  }
  return results;
}

function escapeRegExp(string) {
  return string.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
