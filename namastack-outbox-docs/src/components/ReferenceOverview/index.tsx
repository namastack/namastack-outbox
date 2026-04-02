import type {ReactNode} from 'react';
import clsx from 'clsx';
import styles from './styles.module.css';
import {
  IconArrowBigRightLinesFilled,
  IconBrandSketchFilled,
  IconCalendarEventFilled,
  IconChartBar,
  IconDatabase,
  IconDeviceHeartMonitorFilled,
  IconDropletsFilled,
  IconFileSettingsFilled,
  IconManualGearboxFilled,
  IconMessage2Bolt,
  IconReplaceFilled,
  IconShieldCheckeredFilled,
  IconSignRightFilled,
  IconTableFilled,
} from '@tabler/icons-react';
import {useActiveVersion} from '@docusaurus/plugin-content-docs/client';

const referenceCategories = [
  {
    title: 'Configuration',
    icon: IconFileSettingsFilled,
    description: 'Complete reference of all configuration options.',
    link: 'configuration',
  },
  {
    title: 'Core Features',
    icon: IconShieldCheckeredFilled,
    description: 'Transactional outbox pattern, record ordering, and hash-based partitioning for horizontal scaling.',
    link: 'core',
  },
  {
    title: 'Persistence Modules',
    icon: IconDatabase,
    description: 'Choose between JPA and JDBC persistence modules.',
    link: 'persistence',
  },
  {
    title: 'Record Scheduling',
    icon: IconCalendarEventFilled,
    description: 'Schedule records via the Outbox Service API or use Spring\'s event system with @OutboxEvent.',
    link: 'scheduling',
  },
  {
    title: 'Handlers',
    icon: IconReplaceFilled,
    description: 'Type-safe and generic handlers for processing outbox records, including fallback handlers for graceful degradation.',
    link: 'handlers',
  },
  {
    title: 'Messaging Integrations',
    icon: IconMessage2Bolt,
    description: 'Production-ready Kafka and RabbitMQ handlers with flexible routing and configuration.',
    link: 'messaging',
    sinceVersion: '1.1.x',
  },
  {
    title: 'Polling Strategies',
    icon: IconDeviceHeartMonitorFilled,
    description: 'Supports both fixed and adaptive polling strategies for efficient and responsive outbox processing.',
    link: 'polling',
    sinceVersion: '1.1.x',
  },
  {
    title: 'Processing Chain',
    icon: IconManualGearboxFilled,
    description: 'Chain of Responsibility pattern for processing records through multiple stages.',
    link: 'processing',
  },
  {
    title: 'Retry Mechanisms',
    icon: IconSignRightFilled,
    description: 'Sophisticated retry strategies with exponential backoff, jitter, and exception filtering.',
    link: 'retry',
  },
  {
    title: 'Context Propagation',
    icon: IconDropletsFilled,
    description: 'Preserve trace IDs, tenant info, and other metadata across async boundaries.',
    link: 'context-propagation',
  },
  {
    title: 'Monitoring',
    icon: IconChartBar,
    description: 'Built-in metrics with Micrometer and Spring Boot Actuator integration.',
    link: 'monitoring',
    untilVersion: '1.1.x',
  },
  {
    title: 'Observability',
    icon: IconChartBar,
    description: 'Built-in metrics, distributed tracing, and programmatic monitoring.',
    link: 'observability',
    sinceVersion: '1.2.x',
  },
  {
    title: 'Virtual Threads Support',
    icon: IconTableFilled,
    description: 'Automatic virtual threads integration for better scalability.',
    link: 'virtual-threads',
  },
  {
    title: 'Database Support',
    icon: IconDatabase,
    description: 'Supported databases and schema management.',
    link: 'database',
  },
  {
    title: 'Serialization',
    icon: IconArrowBigRightLinesFilled,
    description: 'Flexible payload serialization with Jackson or custom serializers.',
    link: 'serialization',
  },
  {
    title: 'Reliability Guarantees',
    icon: IconBrandSketchFilled,
    description: 'What the library guarantees and what it does not.',
    link: 'guarantees',
  },
];

// --- Version utilities -------------------------------------------------
// Lightweight numeric comparer that understands `.x` wildcards in patterns.
function parseVersionParts(v: string): Array<number | null> {
  return v.split('.').map((part) => (part === 'x' ? null : Number(part)));
}

function normalizeParts(parts: Array<number | null>, wildcardMax = false, length = 3) {
  const out: number[] = [];
  for (let i = 0; i < length; i++) {
    const p = parts[i];
    if (p == null) {
      out.push(wildcardMax ? 9999 : 0);
    } else if (Number.isNaN(p)) {
      out.push(wildcardMax ? 9999 : 0);
    } else {
      out.push(p as number);
    }
  }
  return out;
}

function compareVersions(a: string, b: string, wildcardMaxForB = false): number {
  // Special handling for the `next` pseudo-version used by Docusaurus.
  // Treat `next` as greater than any numeric version.
  if (a === 'next' && b === 'next') return 0;
  if (a === 'next') return 1;
  if (b === 'next') return -1;

  // returns -1 if a < b, 0 if a == b, 1 if a > b
  const ap = normalizeParts(parseVersionParts(a), false);
  const bp = normalizeParts(parseVersionParts(b), wildcardMaxForB);
  for (let i = 0; i < Math.max(ap.length, bp.length); i++) {
    const ai = ap[i] ?? 0;
    const bi = bp[i] ?? 0;
    if (ai < bi) return -1;
    if (ai > bi) return 1;
  }
  return 0;
}

function matchesWildcard(pattern: string, version: string): boolean {
  // exact match
  if (pattern === version) return true;
  // special-case: explicit 'next' pattern
  if (pattern === 'next') return version === 'next';
  // wildcard like 1.0.x
  if (pattern.includes('x')) {
    const pp = pattern.split('.');
    const vp = version.split('.');
    for (let i = 0; i < pp.length; i++) {
      if (pp[i] === 'x') return true; // prefix matched so it's ok
      if (vp[i] === undefined) return false;
      if (pp[i] !== vp[i]) return false;
    }
    return true;
  }
  return false;
}

function isCategoryVisibleForVersion(cat: any, currentVersion: string) {
  // 1) excludeInVersion (backwards compatible) supports exact and .x wildcard entries
  if (Array.isArray(cat.excludeInVersion)) {
    for (const pattern of cat.excludeInVersion) {
      if (matchesWildcard(pattern, currentVersion)) return false;
    }
  }

  // 2) sinceVersion — show only if currentVersion >= sinceVersion
  if (cat.sinceVersion) {
    if (compareVersions(currentVersion, cat.sinceVersion) < 0) return false;
  }

  // 3) untilVersion — show only if currentVersion <= untilVersion
  if (cat.untilVersion) {
    // treat x in untilVersion as max (e.g., 1.1.x -> 1.1.9999)
    if (compareVersions(currentVersion, cat.untilVersion, true) > 0) return false;
  }

  return true;
}

function ReferenceCard({title, icon: Icon, description, link}: {
  title: string,
  icon: any,
  description: string,
  link: string
}) {
  return (
      <div className={clsx('col col--6', 'margin-bottom--lg')}>
        <div className={clsx(styles.featureCard, 'card')}>
          <div className="card__header"
               style={{display: 'flex', alignItems: 'center', gap: '0.5rem'}}>
            <Icon size={28} style={{marginRight: 8}}/>
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
  const activeVersion = useActiveVersion(undefined);
  // In some Docusaurus setups the active version name may be 'current' (non-numeric).
  // For development/unreleased docs we want to treat 'current' as 'next' so
  // `sinceVersion` comparisons behave intuitively and unreleased features appear.
  const rawName = activeVersion?.name;
  const currentVersion = rawName === 'current' || !rawName ? 'next' : rawName;

  return (
      <section>
        <div className="container">
          <div className="row">
            {referenceCategories
                .filter((cat) => isCategoryVisibleForVersion(cat, currentVersion))
                .map((props, idx) => (
                    <ReferenceCard key={idx} {...props} />
                ))}
          </div>
        </div>
      </section>
  );
}
