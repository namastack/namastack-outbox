import React, {useState, type ComponentType, type ReactNode} from 'react';
import Link from '@docusaurus/Link';
import Heading from '@theme/Heading';
import versions from '@site/versions.json';
import {
  IconAppsFilled,
  IconArrowsExchange,
  IconAutomaticGearboxFilled,
  IconBinocularsFilled,
  IconBrandGithubFilled,
  IconCalendarEventFilled,
  IconChartDots3Filled,
  IconDatabase,
  IconExclamationCircleFilled,
  IconHeartFilled,
  IconMessage2Bolt,
  IconShieldCheckeredFilled,
  IconThumbUpFilled,
} from '@tabler/icons-react';
import styles from './styles.module.css';

const GITHUB_URL = 'https://github.com/namastack/namastack-outbox';
const SPONSOR_URL = 'https://github.com/sponsors/namastack';
const latestVersion = versions[0];

type IconComponent = ComponentType<React.ComponentProps<'svg'>>;

type SectionHeaderProps = {
  eyebrow: string;
  title: string;
  description?: ReactNode;
  align?: 'left' | 'center';
};

export function SectionHeader({eyebrow, title, description, align = 'left'}: SectionHeaderProps) {
  return (
    <div className={align === 'center' ? styles.sectionHeaderCenter : styles.sectionHeader}>
      <span className={styles.eyebrow}>{eyebrow}</span>
      <Heading as="h2">{title}</Heading>
      {description ? <p>{description}</p> : null}
    </div>
  );
}

type InstallTarget = 'jpa' | 'jdbc' | 'mongodb';

const installArtifacts: Record<InstallTarget, string> = {
  jpa: 'namastack-outbox-starter-jpa',
  jdbc: 'namastack-outbox-starter-jdbc',
  mongodb: 'namastack-outbox-starter-mongodb',
};

export function InstallTabs() {
  const [target, setTarget] = useState<InstallTarget>('jpa');
  const [copied, setCopied] = useState(false);
  const snippet = `dependencies {\n    implementation(platform("io.namastack:namastack-outbox-bom:${latestVersion}"))\n    implementation("io.namastack:${installArtifacts[target]}")\n}`;

  async function copySnippet() {
    await navigator.clipboard.writeText(snippet);
    setCopied(true);
    window.setTimeout(() => setCopied(false), 1800);
  }

  return (
    <div className={styles.installPanel}>
      <div className={styles.codeHeader}>
        <span className={styles.fileLabel}>build.gradle.kts</span>
        <div className={styles.installControls}>
          <div className={styles.tabs} role="tablist" aria-label="Persistence starter">
            {(Object.keys(installArtifacts) as InstallTarget[]).map((item) => (
              <button
                aria-selected={target === item}
                className={target === item ? styles.tabActive : styles.tab}
                key={item}
                onClick={() => setTarget(item)}
                role="tab"
                type="button">
                {item === 'mongodb' ? 'MongoDB' : item.toUpperCase()}
              </button>
            ))}
          </div>
          <button
            aria-label={copied ? 'Dependency copied' : 'Copy dependency'}
            className={styles.copyButton}
            onClick={copySnippet}
            type="button">
            {copied ? 'Copied' : 'Copy'}
          </button>
        </div>
      </div>
      <pre className={styles.installCode} aria-live="polite">
        <code>
          <span className={styles.codeComment}>// Native Namastack Outbox setup</span>{'\n'}
          <span>dependencies {'{'}</span>{'\n'}
          <span>    implementation(platform(</span>
          <span className={styles.codeString}>&quot;io.namastack:namastack-outbox-bom:{latestVersion}&quot;</span>
          <span>))</span>{'\n'}
          <span>    implementation(</span>
          <span className={styles.codeString}>&quot;io.namastack:{installArtifacts[target]}&quot;</span>
          <span>)</span>{'\n'}
          <span>{'}'}</span>
        </code>
      </pre>
    </div>
  );
}

type HeroProps = {
  product?: boolean;
};

export function Hero({product = false}: HeroProps) {
  return (
    <section className={product ? styles.productHero : styles.hero}>
      <div className={styles.heroGrid} aria-hidden="true" />
      <div className={styles.container}>
        <div className={styles.heroInner}>
          <span className={styles.heroBadge}>
            {product ? 'Open-source transactional outbox' : 'Featured project · Namastack Outbox'}
          </span>
          <Heading as="h1">
            {product ? (
              <>Transactional event delivery, <em>built into Spring Boot.</em></>
            ) : (
              <>Reliable foundations for <em>event-driven systems.</em></>
            )}
          </Heading>
          <p className={styles.heroLead}>
            {product
              ? 'Persist outgoing work with your business data, then process it asynchronously with ordering, retries, horizontal scaling, and observability.'
              : 'Namastack provides open-source infrastructure for building dependable event-driven applications. Namastack Outbox is the first available project.'}
          </p>
          <div className={styles.actions}>
            <Link className={styles.primaryButton} to={product ? '/docs/quickstart/' : '/outbox/'}>
              {product ? 'Get started' : 'Explore Namastack Outbox'}
              <span aria-hidden="true">→</span>
            </Link>
            <Link className={styles.secondaryButton} to="/docs/">
              Documentation
            </Link>
            <Link className={styles.ghostButton} href={GITHUB_URL}>
              <IconBrandGithubFilled aria-hidden="true" />
              GitHub
            </Link>
          </div>
          <InstallTabs />
        </div>
      </div>
    </section>
  );
}

const tenets: Array<{title: string; description: string; label: string; icon: IconComponent}> = [
  {
    title: 'Reliability',
    description: 'Infrastructure that is designed around failure, recovery, and durable state.',
    label: 'Failure aware',
    icon: IconShieldCheckeredFilled,
  },
  {
    title: 'Consistency',
    description: 'Clear transactional boundaries keep state changes and outgoing work aligned.',
    label: 'Transactional',
    icon: IconArrowsExchange,
  },
  {
    title: 'Production readiness',
    description: 'Operational behavior, configuration, and observability are part of the design.',
    label: 'Operable',
    icon: IconChartDots3Filled,
  },
  {
    title: 'Simplicity',
    description: 'Focused APIs fit the Spring ecosystem without requiring another platform.',
    label: 'Developer focused',
    icon: IconThumbUpFilled,
  },
  {
    title: 'Open source',
    description: 'The available project is developed in public under the Apache License 2.0.',
    label: 'Apache 2.0',
    icon: IconBrandGithubFilled,
  },
];

export function BrandIntro() {
  return (
    <section className={styles.surfaceSection}>
      <div className={styles.container}>
        <div className={styles.introRow}>
          <SectionHeader eyebrow="Brand philosophy" title="What is Namastack?" />
          <p>
            Namastack is the umbrella brand for open-source infrastructure tools for modern
            distributed applications. Today, that work is represented by Namastack Outbox.
          </p>
        </div>
        <div className={styles.tenetGrid}>
          {tenets.map(({title, description, label, icon: Icon}) => (
            <article className={styles.tenetCard} key={title}>
              <Icon aria-hidden="true" className={styles.cardIcon} />
              <Heading as="h3">{title}</Heading>
              <p>{description}</p>
              <span>{label}</span>
            </article>
          ))}
        </div>
      </div>
    </section>
  );
}

const architectureSteps = [
  {
    title: 'Application transaction',
    description: 'Business data and the outbox record are persisted in one local transaction.',
    detail: '@Transactional',
  },
  {
    title: 'Commit',
    description: 'The committed outbox record becomes durable work ready for processing.',
    detail: 'Durable state',
  },
  {
    title: 'Partitioned processing',
    description: 'Workers poll committed records and coordinate across application instances.',
    detail: 'Partition aware',
  },
  {
    title: 'Handler execution',
    description: 'The matching handler runs while records with the same key stay ordered.',
    detail: 'Per-key ordering',
  },
  {
    title: 'Completion or retry',
    description: 'Success completes the record; failures follow the configured retry strategy.',
    detail: 'At least once',
  },
];

export function ArchitectureFlow({withCode = true}: {withCode?: boolean}) {
  return (
    <div className={styles.architecturePanel}>
      <div className={styles.panelHeading}>
        <span className={styles.eyebrow}>Architecture flow</span>
        <Heading as="h3">How it works</Heading>
      </div>
      <ol className={styles.flowGrid}>
        {architectureSteps.map((step, index) => (
          <li key={step.title}>
            <span className={styles.stepNumber}>Stage {String(index + 1).padStart(2, '0')}</span>
            <Heading as="h4">{step.title}</Heading>
            <p>{step.description}</p>
            <code>{step.detail}</code>
          </li>
        ))}
      </ol>
      {withCode ? (
        <div className={styles.codeExample}>
          <div className={styles.codeHeader}>
            <span className={styles.fileLabel}>OrderService.kt</span>
            <span className={styles.codeMeta}>Kotlin · Spring transaction</span>
          </div>
          <pre>
            <code>
              <span className={styles.codeComment}>// Business state and outgoing work commit together</span>{'\n'}
              <span className={styles.codeKeyword}>@Transactional</span>{'\n'}
              <span><span className={styles.codeKeyword}>fun</span> createOrder(command: CreateOrderCommand) {'{'}</span>{'\n'}
              <span>    <span className={styles.codeKeyword}>val</span> order = orderRepository.save(Order.create(command))</span>{'\n'}
              <span>    outbox.schedule(</span>{'\n'}
              <span>        payload = OrderCreatedEvent(order.id, order.customerId),</span>{'\n'}
              <span>        key = <span className={styles.codeString}>&quot;order-${'{'}order.id{'}'}&quot;</span></span>{'\n'}
              <span>    )</span>{'\n'}
              <span>{'}'}</span>
            </code>
          </pre>
        </div>
      ) : null}
    </div>
  );
}

const capabilities: Array<{title: string; description: string; icon: IconComponent}> = [
  {
    title: 'Reliable delivery',
    description: 'At-least-once delivery with configurable retries and fallback handling. Consumers should be idempotent.',
    icon: IconShieldCheckeredFilled,
  },
  {
    title: 'Strict ordering',
    description: 'Records that share a key retain their processing order across polling cycles.',
    icon: IconCalendarEventFilled,
  },
  {
    title: 'Horizontal scaling',
    description: 'Partition-aware processing coordinates work safely across multiple application instances.',
    icon: IconChartDots3Filled,
  },
  {
    title: 'Flexible persistence',
    description: 'Choose JPA, JDBC, or MongoDB to fit the persistence model already used by the application.',
    icon: IconDatabase,
  },
  {
    title: 'Messaging integrations',
    description: 'Publish through Kafka, RabbitMQ, and Amazon SNS integrations, or implement a custom handler.',
    icon: IconMessage2Bolt,
  },
  {
    title: 'Observability',
    description: 'Micrometer metrics, distributed tracing, and context propagation cover the asynchronous boundary.',
    icon: IconBinocularsFilled,
  },
];

export function CapabilityGrid() {
  return (
    <div className={styles.capabilityGrid}>
      {capabilities.map(({title, description, icon: Icon}) => (
        <article key={title}>
          <div className={styles.capabilityTitle}>
            <Icon aria-hidden="true" />
            <Heading as="h4">{title}</Heading>
          </div>
          <p>{description}</p>
        </article>
      ))}
    </div>
  );
}

export function FeaturedOutbox() {
  return (
    <section className={styles.section} id="how-it-works">
      <div className={styles.container}>
        <div className={styles.productIntro}>
          <span className={styles.productBadge}>Available open-source project</span>
          <Heading as="h2">Namastack Outbox</Heading>
          <p>
            A transactional outbox library for Spring Boot. It persists business data and outgoing
            work in the same local transaction, then processes committed records asynchronously.
            Delivery is at least once, so duplicate delivery is possible and consumers should be
            designed to be idempotent.
          </p>
          <Link to="/outbox/">Explore the product <span aria-hidden="true">→</span></Link>
        </div>
        <ArchitectureFlow />
        <div className={styles.capabilitiesHeading}>
          <SectionHeader
            eyebrow="Core capabilities"
            title="Engineered for production resilience"
            description="The documented building blocks for moving committed work from storage to its destination."
          />
        </div>
        <CapabilityGrid />
      </div>
    </section>
  );
}

export function IntegrationSection() {
  return (
    <section className={styles.surfaceSection}>
      <div className={styles.container}>
        <div className={styles.integrationPanel}>
          <div>
            <span className={styles.heroBadge}><IconAppsFilled aria-hidden="true" /> Spring Modulith integration</span>
            <Heading as="h2">Use Namastack as the outbox behind Spring Modulith events.</Heading>
            <p>
              Namastack Outbox is an independent library. Spring Modulith applications can use the
              official starter to externalize events through Namastack&apos;s persistence, processing,
              retry, ordering, and observability model.
            </p>
            <Link className={styles.secondaryButton} to="/docs/reference/spring-modulith/">
              Read the integration guide
            </Link>
          </div>
          <div className={styles.integrationCode}>
            <span>Spring Modulith</span>
            <code>spring-modulith-starter-namastack</code>
            <div aria-hidden="true">↓</div>
            <span>Namastack Outbox</span>
            <small>JPA · JDBC · MongoDB</small>
          </div>
        </div>
      </div>
    </section>
  );
}

const trustSignals = [
  ['Apache 2.0', 'Open-source license'],
  ['Spring Boot', 'Native application integration'],
  ['Java & Kotlin', 'JVM language support'],
  ['JPA · JDBC · MongoDB', 'Persistence choices'],
  ['Micrometer', 'Metrics and observations'],
  ['Public development', 'Issues and discussions on GitHub'],
];

export function TrustSection() {
  return (
    <section className={styles.section}>
      <div className={styles.container}>
        <SectionHeader
          align="center"
          eyebrow="Built on standard foundations"
          title="Open infrastructure"
          description="Technology choices and project signals you can inspect directly."
        />
        <div className={styles.trustGrid}>
          {trustSignals.map(([title, label]) => (
            <div key={title}>
              <strong>{title}</strong>
              <span>{label}</span>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}

export function CTASection({product = false}: {product?: boolean}) {
  return (
    <section className={styles.ctaSection}>
      <div className={styles.ctaInner}>
        <IconAutomaticGearboxFilled aria-hidden="true" />
        <Heading as="h2">
          {product ? 'Start with a working outbox.' : 'Build reliable event-driven systems with confidence.'}
        </Heading>
        <p>
          {product
            ? 'Install a persistence starter, schedule your first record, and follow the operational model in the documentation.'
            : 'Use Namastack Outbox to close the dual-write gap in your Spring Boot application.'}
        </p>
        <div className={styles.actions}>
          <Link className={styles.primaryButton} to="/docs/quickstart/">Read the quickstart <span aria-hidden="true">→</span></Link>
          <Link className={styles.secondaryButton} href={GITHUB_URL}><IconBrandGithubFilled aria-hidden="true" /> Explore on GitHub</Link>
        </div>
      </div>
    </section>
  );
}

export function SponsorSection() {
  return (
    <section className={styles.sponsorSection}>
      <div className={styles.sponsorCard}>
        <IconHeartFilled aria-hidden="true" />
        <Heading as="h2">Support the work</Heading>
        <p>
          Namastack Outbox is independently developed and maintained as open-source software.
          Sponsorship supports maintenance, documentation, integrations, and long-term stability.
        </p>
        <div className={styles.actions}>
          <Link className={styles.primaryButton} href={SPONSOR_URL}>Become a sponsor</Link>
          <Link className={styles.ghostButton} href={GITHUB_URL}>View on GitHub</Link>
        </div>
      </div>
    </section>
  );
}

export function ProductOverview() {
  return (
    <section className={styles.section}>
      <div className={styles.container}>
        <div className={styles.productOverviewGrid}>
          <SectionHeader
            eyebrow="Transactional outbox"
            title="Keep database state and outgoing work consistent."
            description="A database update and a broker publish cannot share one local transaction. Namastack stores the outgoing record beside the business change and processes it after commit."
          />
          <div className={styles.overviewList}>
            <div><span>01</span><p><strong>Schedule atomically.</strong> Save the outbox record in the business transaction.</p></div>
            <div><span>02</span><p><strong>Process asynchronously.</strong> Partition-aware workers claim committed work.</p></div>
            <div><span>03</span><p><strong>Recover explicitly.</strong> Retries and fallback handlers deal with delivery failures.</p></div>
          </div>
        </div>
      </div>
    </section>
  );
}

export function ProductDetails() {
  return (
    <section className={styles.section}>
      <div className={styles.container}>
        <SectionHeader
          eyebrow="Product capabilities"
          title="One focused library, from persistence to operations"
          description="Choose the modules your application needs and keep the processing model inside Spring Boot."
        />
        <CapabilityGrid />
        <div className={styles.docsCallout}>
          <IconExclamationCircleFilled aria-hidden="true" />
          <div>
            <Heading as="h3">Understand the delivery contract</Heading>
            <p>At-least-once delivery can produce duplicates. The guarantees guide explains ordering, retries, transactions, and consumer responsibilities.</p>
          </div>
          <Link to="/docs/reference/guarantees/">Read guarantees →</Link>
        </div>
      </div>
    </section>
  );
}

export function HomeContent() {
  return (
    <>
      <Hero />
      <main>
        <BrandIntro />
        <FeaturedOutbox />
        <IntegrationSection />
        <TrustSection />
        <CTASection />
        <SponsorSection />
      </main>
    </>
  );
}

export function OutboxContent() {
  return (
    <>
      <Hero product />
      <main>
        <ProductOverview />
        <section className={styles.surfaceSection}>
          <div className={styles.container}><ArchitectureFlow /></div>
        </section>
        <ProductDetails />
        <IntegrationSection />
        <CTASection product />
        <SponsorSection />
      </main>
    </>
  );
}
