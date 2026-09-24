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

// Sitemap ignore patterns: exclude old versions, "next", and low-value pages
const sitemapIgnorePatterns = [
  '/docs/next/**',
  '/docs/legal-notice/**',
  ...olderVersions.map((v) => `/docs/${v}/**`),
];

const config: Config = {
  title: 'Namastack',
  tagline: 'Reliable foundations for event-driven systems',
  favicon: 'img/favicon.png',

  // Future flags, see https://docusaurus.io/docs/api/docusaurus-config#future
  future: {
    v4: false, // Improve compatibility with the upcoming Docusaurus v4
  },

  // Set the production url of your site here
  url: 'https://www.namastack.io',
  // Set the /<baseUrl>/ pathname under which your site is served
  // For GitHub pages deployment, it is often '/<projectName>/'
  baseUrl: '/',

  trailingSlash: true,

  // GitHub pages deployment config.
  // If you aren't using GitHub pages, you don't need these.
  organizationName: 'namastack', // Usually your GitHub org/user name.
  projectName: 'namstack-outbox', // Usually your repo name.

  onBrokenLinks: 'warn',

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
          routeBasePath: '/docs',
          sidebarPath: './sidebars.ts',
          lastVersion: latestVersion,
          versions: versionConfig,
          editUrl: 'https://github.com/namastack/namastack-outbox/edit/main/namastack-outbox-docs/',
        },
        sitemap: {
          changefreq: 'weekly' as const,
          priority: 0.5,
          ignorePatterns: [
            ...sitemapIgnorePatterns,
            '/search/**',
          ],
          async createSitemapItems({defaultCreateSitemapItems, ...params}) {
            const items = await defaultCreateSitemapItems({...params});
            return items.map((item) => {
              // Give the homepage and quickstart higher priority
              const pathname = new URL(item.url).pathname;
              if (
                pathname === '/' ||
                pathname === '/outbox/' ||
                pathname === '/docs/' ||
                pathname.includes('/quickstart/')
              ) {
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
    './plugins/canonical-fix-plugin.js',
    [
      '@easyops-cn/docusaurus-search-local',
      {
        hashed: true,
        language: 'en',
        indexDocs: true,
        indexBlog: false,
        indexPages: true,
        highlightSearchTermsOnTargetPage: true,
        docsRouteBasePath: '/docs',
      },
    ],
  ],

  themeConfig: {
    image: 'img/og-image-v3.png',
    metadata: [
      {
        name: 'keywords',
        content:
          'Namastack, outbox pattern, spring boot, transactional outbox, transactional messaging, ' +
          'distributed systems, event-driven architecture, at-least-once delivery, ' +
          'Java, Kotlin, Spring, microservices, reliable messaging',
      },
    ],
    colorMode: {
      defaultMode: 'dark',
      disableSwitch: false,
      respectPrefersColorScheme: true,
    },
    navbar: {
      title: 'Namastack',
      logo: {
        alt: 'Namastack',
        src: 'img/namastack_logo.svg',
        href: '/',
        width: 28,
        height: 28,
      },
      items: [
        {to: '/outbox/', label: 'Outbox', position: 'left', activeBaseRegex: '^/outbox/'},
        {
          type: 'dropdown',
          to: '/docs/',
          label: 'Docs',
          position: 'left',
          activeBaseRegex: '^/docs/',
          items: [
            {
              label: 'Quickstart',
              to: '/docs/quickstart/',
              activeBaseRegex: '^/docs/(?:[^/]+/)?quickstart/?$',
            },
            {
              label: 'Reference',
              to: '/docs/reference/',
              activeBaseRegex: '^/docs/(?:[^/]+/)?reference/?$',
            },
            {
              label: 'Spring Modulith',
              to: '/docs/reference/spring-modulith/',
              activeBaseRegex: '^/docs/(?:[^/]+/)?reference/spring-modulith/?$',
            },
          ],
        },
        {
          label: 'Community',
          position: 'left',
          items: [
            {label: 'Contributing', to: '/docs/contributing/'},
            {label: 'GitHub Discussions', href: 'https://github.com/namastack/namastack-outbox/discussions'},
            {label: 'GitHub Issues', href: 'https://github.com/namastack/namastack-outbox/issues'},
            {label: 'Discord', href: 'https://discord.gg/XNHP5Yhxy7'},
          ],
        },
        {type: 'search', position: 'right'},
        {to: '/docs/sponsor/', label: 'Sponsor', position: 'right', className: 'navbar__sponsor'},
        {
          href: 'https://github.com/namastack/namastack-outbox',
          label: 'GitHub',
          position: 'right',
          className: 'navbar__github',
        },
        {
          type: 'docsVersionDropdown',
          position: 'right',
        },
      ],
    },
    footer: {
      style: 'dark',
      links: [
        {
          title: 'Namastack',
          items: [
            {
              label: 'Home',
              to: '/',
            },
            {
              label: 'Namastack Outbox',
              to: '/outbox/',
            },
          ],
        },
        {
          title: 'Documentation',
          items: [
            {
              label: 'Getting Started',
              to: '/docs/quickstart/',
            },
            {
              label: 'Reference',
              to: '/docs/reference/',
            },
            {
              label: 'Reliability Guarantees',
              to: '/docs/reference/guarantees/',
            },
          ],
        },
        {
          title: 'Community & Support',
          items: [
            {
              label: 'Contributing',
              to: '/docs/contributing/',
            },
            {
              label: 'GitHub Discussions',
              href: 'https://github.com/namastack/namastack-outbox/discussions',
            },
            {
              label: 'Discord',
              href: 'https://discord.gg/XNHP5Yhxy7',
            },
            {
              label: 'Sponsor Namastack',
              to: '/docs/sponsor/',
            },
          ],
        },
        {
          title: 'Project',
          items: [
            {label: 'GitHub', href: 'https://github.com/namastack/namastack-outbox'},
            {label: 'Releases', href: 'https://github.com/namastack/namastack-outbox/releases'},
            {label: 'Security', href: 'https://github.com/namastack/namastack-outbox/security'},
            {label: 'Legal Notice', to: '/docs/legal-notice/'},
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
