import type {ReactNode} from 'react';
import clsx from 'clsx';
import styles from './styles.module.css';
import {
  IconFileSettingsFilled,
  IconShieldCheckeredFilled,
  IconDatabase,
  IconCalendarEventFilled,
  IconReplaceFilled,
  IconDeviceHeartMonitorFilled,
  IconManualGearboxFilled,
  IconSignRightFilled,
  IconDropletsFilled,
  IconChartBar,
  IconTableFilled,
  IconBrandSketchFilled,
  IconArrowBigRightLinesFilled,
  IconMessage2Bolt,
} from '@tabler/icons-react';
import { useActiveVersion } from '@docusaurus/plugin-content-docs/client';

const referenceCategories = [
  {
    title: 'Configuration',
    icon: IconFileSettingsFilled,
    description: 'Complete reference of all configuration options.',
    link: 'configuration',
    excludeInVersion: [],
  },
  {
    title: 'Core Features',
    icon: IconShieldCheckeredFilled,
    description: 'Transactional outbox pattern, record ordering, and hash-based partitioning for horizontal scaling.',
    link: 'core',
    excludeInVersion: [],
  },
  {
    title: 'Persistence Modules',
    icon: IconDatabase,
    description: 'Choose between JPA and JDBC persistence modules.',
    link: 'persistence',
    excludeInVersion: [],
  },
  {
    title: 'Record Scheduling',
    icon: IconCalendarEventFilled,
    description: 'Schedule records via the Outbox Service API or use Spring\'s event system with @OutboxEvent.',
    link: 'scheduling',
    excludeInVersion: [],
  },
  {
    title: 'Handlers',
    icon: IconReplaceFilled,
    description: 'Type-safe and generic handlers for processing outbox records, including fallback handlers for graceful degradation.',
    link: 'handlers',
    excludeInVersion: [],
  },
  {
    title: 'Messaging Integrations',
    icon: IconMessage2Bolt,
    description: 'Production-ready Kafka and RabbitMQ handlers with flexible routing and configuration.',
    link: 'messaging',
    excludeInVersion: ['1.0.0'],
  },
  {
    title: 'Polling Strategies',
    icon: IconDeviceHeartMonitorFilled,
    description: 'Supports both fixed and adaptive polling strategies for efficient and responsive outbox processing.',
    link: 'polling',
    excludeInVersion: ["1.0.0"],
  },
  {
    title: 'Processing Chain',
    icon: IconManualGearboxFilled,
    description: 'Chain of Responsibility pattern for processing records through multiple stages.',
    link: 'processing',
    excludeInVersion: [],
  },
  {
    title: 'Retry Mechanisms',
    icon: IconSignRightFilled,
    description: 'Sophisticated retry strategies with exponential backoff, jitter, and exception filtering.',
    link: 'retry',
    excludeInVersion: [],
  },
  {
    title: 'Context Propagation',
    icon: IconDropletsFilled,
    description: 'Preserve trace IDs, tenant info, and other metadata across async boundaries.',
    link: 'context-propagation',
    excludeInVersion: [],
  },
  {
    title: 'Monitoring',
    icon: IconChartBar,
    description: 'Built-in metrics with Micrometer and Spring Boot Actuator integration.',
    link: 'monitoring',
    excludeInVersion: [],
  },
  {
    title: 'Virtual Threads Support',
    icon: IconTableFilled,
    description: 'Automatic virtual threads integration for better scalability.',
    link: 'virtual-threads',
    excludeInVersion: [],
  },
  {
    title: 'Database Support',
    icon: IconDatabase,
    description: 'Supported databases and schema management.',
    link: 'database',
    excludeInVersion: [],
  },
  {
    title: 'Serialization',
    icon: IconArrowBigRightLinesFilled,
    description: 'Flexible payload serialization with Jackson or custom serializers.',
    link: 'serialization',
    excludeInVersion: [],
  },
  {
    title: 'Reliability Guarantees',
    icon: IconBrandSketchFilled,
    description: 'What the library guarantees and what it does not.',
    link: 'guarantees',
    excludeInVersion: [],
  },
];

function ReferenceCard({title, icon: Icon, description, link}: {title: string, icon: any, description: string, link: string}) {
  return (
    <div className={clsx('col col--6', 'margin-bottom--lg')}>
      <div className={clsx(styles.featureCard, 'card')}>
        <div className="card__header" style={{display: 'flex', alignItems: 'center', gap: '0.5rem'}}>
          <Icon size={28} style={{marginRight: 8}} />
          <h3 style={{margin: 0}}>{title}</h3>
        </div>
        <div className="card__body">
          <p>{description}</p>
        </div>
        <div className="card__footer">
          <a className="button button--secondary button--block" href={link}>
            Read more
          </a>
        </div>
      </div>
    </div>
  );
}

export default function ReferenceOverview(): ReactNode {
  const activeVersion = useActiveVersion();
  const currentVersion = activeVersion?.name || 'current';
  return (
    <section>
      <div className="container">
        <div className="row">
          {referenceCategories
            .filter(cat => !cat.excludeInVersion.includes(currentVersion))
            .map((props, idx) => (
              <ReferenceCard key={idx} {...props} />
            ))}
        </div>
      </div>
    </section>
  );
}
