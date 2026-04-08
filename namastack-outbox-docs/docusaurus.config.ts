import {themes as prismThemes} from 'prism-react-renderer';
import type {Config} from '@docusaurus/types';
import type * as Preset from '@docusaurus/preset-classic';
import versions from './versions.json';

// First entry in versions.json is the latest stable version
const latestVersion = versions[0];
const olderVersions = versions.slice(1);

// Build version config: latest gets indexed, all others get noIndex
const versionConfig: Record<string, {label: string; noIndex?: boolean}> = {
  current: {label: 'Next', noIndex: true},
  [latestVersion]: {label: latestVersion},
};
for (const v of olderVersions) {
  versionConfig[v] = {label: v, noIndex: true};
}

// Sitemap ignore patterns: exclude old versions and "next"
const sitemapIgnorePatterns = [
  '/outbox/next/**',
  ...olderVersions.map((v) => `/outbox/${v}/**`),
];

const config: Config = {
  title: 'Namastack Outbox',
  tagline: 'for Spring Boot',
  favicon: 'img/favicon.png',

  // Future flags, see https://docusaurus.io/docs/api/docusaurus-config#future
  future: {
    v4: false, // Improve compatibility with the upcoming Docusaurus v4
  },

  // Set the production url of your site here
  url: 'https://www.namastack.io',
  // Set the /<baseUrl>/ pathname under which your site is served
  // For GitHub pages deployment, it is often '/<projectName>/'
  baseUrl: '/outbox/',

  // GitHub pages deployment config.
  // If you aren't using GitHub pages, you don't need these.
  organizationName: 'namastack', // Usually your GitHub org/user name.
  projectName: 'namstack-outbox', // Usually your repo name.

  onBrokenLinks: 'ignore',

  trailingSlash: true,

  // Even if you don't use internationalization, you can use this field to set
  // useful metadata like html lang. For example, if your site is Chinese, you
  // may want to replace "en" with "zh-Hans".
  i18n: {
    defaultLocale: 'en',
    locales: ['en'],
  },

  markdown: {
    mermaid: true,
  },
  themes: ['@docusaurus/theme-mermaid'],

  presets: [
    [
      'classic',
      {
        blog: false,
        docs: {
          routeBasePath: '/',
          sidebarPath: './sidebars.ts',
          lastVersion: latestVersion,
          versions: versionConfig,
        },
        sitemap: {
          changefreq: 'weekly' as const,
          priority: 0.5,
          ignorePatterns: [
            ...sitemapIgnorePatterns,
            '/outbox/search/**',
          ],
          async createSitemapItems({defaultCreateSitemapItems, ...params}) {
            const items = await defaultCreateSitemapItems({...params});
            return items.map((item) => {
              // Give the homepage and quickstart higher priority
              if (item.url.endsWith('/outbox/') || item.url.includes('/quickstart/')) {
                return {...item, priority: 0.8};
              }
              return item;
            });
          },
        },
        theme: {
          customCss: './src/css/custom.css',
        },
      } satisfies Preset.Options,
    ],
  ],

  plugins: [
    [
      '@easyops-cn/docusaurus-search-local',
      {
        hashed: true,
        language: 'en',
        indexDocs: true,
        indexBlog: false,
        indexPages: false,
        highlightSearchTermsOnTargetPage: true,
        docsRouteBasePath: '/',
      },
    ],
    [
      './plugins/canonical-fix-plugin',
      {},
    ],
  ],

  themeConfig: {
    // Replace with your project's social card
    image: 'img/og-image-v3.png',
    colorMode: {
      defaultMode: 'dark',
      disableSwitch: false,
      respectPrefersColorScheme: true,
    },
    navbar: {
      title: 'Namastack Outbox',
      logo: {
        alt: 'Namastack Outbox Logo',
        src: 'img/namastack_logo.svg',
      },
      items: [
        {to: 'quickstart', label: 'Quickstart', position: 'left'},
        {to: 'reference', label: 'Reference', position: 'left'},
        {to: 'contributing', label: 'Contributing', position: 'left'},
        {
          href: 'https://github.com/namastack/namastack-outbox',
          label: 'GitHub',
          position: 'right',
        },
        {
          type: 'docsVersionDropdown',
        },
      ],
    },
    footer: {
      style: 'dark',
      links: [
        {
          title: 'Quick Links',
          items: [
            {
              label: 'Quickstart',
              to: '/quickstart',
            },
            {
              label: 'Reference',
              to: '/reference',
            },
          ],
        },
        {
          title: 'Community',
          items: [
            {
              label: 'LinkedIn',
              href: 'https://www.linkedin.com/in/roland-beisel/',
            },
            {
              label: 'Discord',
              href: 'https://discord.gg/XNHP5Yhxy7',
            },
          ],
        },
        {
          title: 'More',
          items: [
            {
              label: 'GitHub',
              href: 'https://github.com/namastack/namastack-outbox',
            },
            {
              label: 'Legal Notice',
              href: '/legal-notice',
            },
          ],
        },
      ],
      copyright: `Copyright © ${new Date().getFullYear()} Namastack. Built with Docusaurus.`,
    },
    prism: {
      theme: prismThemes.github,
      darkTheme: prismThemes.dracula,
      additionalLanguages: ['java'],
    },
  } satisfies Preset.ThemeConfig,
};

export default config;
