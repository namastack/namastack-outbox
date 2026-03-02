import type {ReactNode} from 'react';
import clsx from 'clsx';
import styles from './styles.module.css';
import { IconShieldCheckeredFilled } from '@tabler/icons-react';

// Mapping of reference categories to their metadata
const referenceCategories = [
  {
    title: 'Core Features',
    icon: IconShieldCheckeredFilled,
    description: 'Transactional outbox pattern, record ordering, and hash-based partitioning for horizontal scaling.',
    link: 'core',
  },
  {
    title: 'Record Scheduling',
    icon: IconShieldCheckeredFilled,
    description: 'Schedule records via the Outbox Service API or use Spring\'s event system with @OutboxEvent.',
    link: 'scheduling',
  },
  {
    title: 'Processing Chain',
    icon: IconShieldCheckeredFilled,
    description: 'Chain of Responsibility pattern for processing records through multiple stages.',
    link: 'processing',
  },
  {
    title: 'Handlers',
    icon: IconShieldCheckeredFilled,
    description: 'Type-safe and generic handlers for processing outbox records, including fallback handlers for graceful degradation.',
    link: 'handlers',
  },
  {
    title: 'Context Propagation',
    icon: IconShieldCheckeredFilled,
    description: 'Preserve trace IDs, tenant info, and other metadata across async boundaries.',
    link: 'context-propagation',
  },
  {
    title: 'Serialization',
    icon: IconShieldCheckeredFilled,
    description: 'Flexible payload serialization with Jackson or custom serializers.',
    link: 'serialization',
  },
  {
    title: 'Retry Mechanisms',
    icon: IconShieldCheckeredFilled,
    description: 'Sophisticated retry strategies with exponential backoff, jitter, and exception filtering.',
    link: 'retry',
  },
  {
    title: 'Persistence',
    icon: IconShieldCheckeredFilled,
    description: 'Choose between JPA and JDBC persistence modules.',
    link: 'persistence',
  },
  {
    title: 'Monitoring',
    icon: IconShieldCheckeredFilled,
    description: 'Built-in metrics with Micrometer and Spring Boot Actuator integration.',
    link: 'monitoring',
  },
  {
    title: 'Configuration',
    icon: IconShieldCheckeredFilled,
    description: 'Complete reference of all configuration options.',
    link: 'configuration',
  },
  {
    title: 'Virtual Threads Support',
    icon: IconShieldCheckeredFilled,
    description: 'Automatic virtual threads integration for better scalability.',
    link: 'virtual-threads',
  },
  {
    title: 'Database Support',
    icon: IconShieldCheckeredFilled,
    description: 'Supported databases and schema management.',
    link: 'database',
  },
  {
    title: 'Reliability Guarantees',
    icon: IconShieldCheckeredFilled,
    description: 'What the library guarantees and what it does not.',
    link: 'guarantees',
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
  return (
    <section>
      <div className="container">
        <div className="row">
          {referenceCategories.map((props, idx) => (
            <ReferenceCard key={idx} {...props} />
          ))}
        </div>
      </div>
    </section>
  );
}
