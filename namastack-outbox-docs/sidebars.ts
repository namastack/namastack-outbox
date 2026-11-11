import type {SidebarsConfig} from '@docusaurus/plugin-content-docs';

const sidebars: SidebarsConfig = {
  referenceSidebar: [
    {
      type: 'category',
      label: 'Reference',
      collapsible: false,
      collapsed: false,
      link: {type: 'doc', id: 'reference/index'},
      items: [
        {
          type: 'category',
          label: 'Core Concepts',
          collapsed: true,
          items: [
            'reference/core',
            'reference/guarantees',
            'reference/scheduling',
            'reference/handlers',
            'reference/processing',
            'reference/processing-groups',
          ],
        },
        {
          type: 'category',
          label: 'Persistence',
          collapsed: true,
          items: [
            'reference/persistence',
            'reference/database',
            'reference/retention',
          ],
        },
        {
          type: 'category',
          label: 'Integrations',
          collapsed: true,
          items: [
            'reference/messaging',
            'reference/modulith',
          ],
        },
        {
          type: 'category',
          label: 'Processing & Resilience',
          collapsed: true,
          items: [
            'reference/polling',
            'reference/retry',
            'reference/virtual-threads',
            'reference/rolling-deployments',
          ],
        },
        {
          type: 'category',
          label: 'Observability',
          collapsed: true,
          items: [
            'reference/observability',
            'reference/context-propagation',
          ],
        },
        {
          type: 'category',
          label: 'Advanced',
          collapsed: true,
          items: [
            'reference/performance-tuning',
            'reference/serialization',
          ],
        },
        'reference/configuration',
      ],
    },
    {
      type: 'category',
      label: 'Migrations',
      collapsed: false,
      link: {
        type: 'generated-index',
        title: 'Migrations',
        description: 'Upgrade an existing Namastack Outbox application while preserving its records and delivery state.',
        slug: '/migrations',
      },
      items: [
        {
          type: 'category',
          label: 'Upgrade to 2.0',
          collapsed: false,
          link: {type: 'doc', id: 'migrations/2-0-0'},
          items: ['migrations/2-0-0-indexes'],
        },
      ],
    },
  ],
};

export default sidebars;
