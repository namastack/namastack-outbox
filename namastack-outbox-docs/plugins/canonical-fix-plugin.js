const fs = require('fs');
const path = require('path');

/**
 * Docusaurus plugin that:
 * 1. Rewrites canonical URLs on older/unreleased version pages to point to the
 *    equivalent latest-version (unversioned) URL.
 * 2. Generates a robots.txt that blocks crawling of old version paths.
 *
 * All version info is derived automatically from versions.json — no manual
 * configuration needed.
 */
module.exports = function canonicalFixPlugin(context) {
  const {siteConfig} = context;
  const siteUrl = siteConfig.url;
  const baseUrl = siteConfig.baseUrl;

  // Read versions.json; first entry is the latest version (served at root)
  const versionsPath = path.join(context.siteDir, 'versions.json');
  const versions = JSON.parse(fs.readFileSync(versionsPath, 'utf-8'));
  const olderVersions = versions.slice(1);

  // Version prefixes that need canonical rewrites (older versions + "next")
  const versionPrefixes = [...olderVersions, 'next'];

  return {
    name: 'canonical-fix-plugin',

    async postBuild({outDir}) {
      // 1. Rewrite canonical URLs in versioned HTML pages
      for (const prefix of versionPrefixes) {
        const versionDir = path.join(outDir, prefix);
        if (!fs.existsSync(versionDir)) continue;

        const htmlFiles = findHtmlFiles(versionDir);

        for (const filePath of htmlFiles) {
          let html = fs.readFileSync(filePath, 'utf-8');

          const canonicalRegex = new RegExp(
            `(<link[^>]*rel="canonical"[^>]*href=")${escapeRegExp(siteUrl + baseUrl)}${escapeRegExp(prefix)}/([^"]*")`
          );
          const replacement = `$1${siteUrl}${baseUrl}$2`;
          const newHtml = html.replace(canonicalRegex, replacement);

          if (newHtml !== html) {
            fs.writeFileSync(filePath, newHtml, 'utf-8');
          }
        }
      }

      // 2. Generate robots.txt
      const disallowRules = versionPrefixes
        .map((prefix) => `Disallow: ${baseUrl}${prefix}/`)
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
    },
  };
};

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
