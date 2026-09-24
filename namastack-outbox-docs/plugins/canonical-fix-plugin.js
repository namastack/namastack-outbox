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

  return {
    name: 'site-migration-plugin',

    async postBuild({outDir}) {
      const docsDir = path.join(outDir, docsPath);

      rewriteVersionCanonicals(docsDir, versionPrefixes, siteUrl, baseUrl, docsPath);
      createLegacyDocumentationRedirects(docsDir, outDir, siteUrl, baseUrl, docsPath);
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
