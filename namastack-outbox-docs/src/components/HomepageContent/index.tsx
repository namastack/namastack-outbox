import type {ReactNode} from 'react';
import Link from '@docusaurus/Link';
import Heading from '@theme/Heading';
import {IconArrowsExchange, IconDatabase, IconLeaf} from '@tabler/icons-react';
import styles from './styles.module.css';

export default function HomepageContent(): ReactNode {
  return (
    <section className={styles.content}>
      <div className={styles.container}>
        <div className={styles.intro}>
          <span className={styles.eyebrow}>From commit to delivery</span>
          <Heading as="h2">Reliable events, without the dual-write gap</Heading>
          <p>
            Keep business data and outgoing events consistent, then deliver them on your
            application&apos;s terms.
          </p>
        </div>

        <div className={styles.cards}>
          <article className={styles.card}>
            <div className={styles.icon}>
              <IconDatabase aria-hidden="true" />
            </div>
            <Heading as="h3">What is Namastack Outbox?</Heading>
            <p>
              Namastack Outbox is an open-source library that makes reliable event publishing part
              of a Spring Boot application. It stores outgoing events alongside business data and
              processes them asynchronously after commit, providing at-least-once delivery without
              another infrastructure component. Use it for events, commands, notifications, or any
              work that must survive application and downstream failures.
            </p>
          </article>

          <article className={styles.card}>
            <div className={styles.icon}>
              <IconArrowsExchange aria-hidden="true" />
            </div>
            <Heading as="h3">Why a transactional outbox?</Heading>
            <p>
              Database updates and broker publishes are separate operations; either can succeed
              while the other fails. Namastack closes that gap by saving the outbox record in the
              same local transaction as the business change. Background workers deliver committed
              records and retry failures. Delivery can repeat after a crash, so consumers should be
              idempotent. See the <Link to="/reference/guarantees/">delivery guarantees</Link>.
            </p>
          </article>

          <article className={styles.card}>
            <div className={styles.icon}>
              <IconLeaf aria-hidden="true" />
            </div>
            <Heading as="h3">Built for Spring Boot</Heading>
            <p>
              Use Java or Kotlin with JPA, JDBC, or MongoDB starters. Publish through ready-made
              Kafka, RabbitMQ, and Amazon SNS integrations, or write a custom handler. Per-key
              ordering, partition-aware horizontal scaling, configurable retries, fallback handling,
              Micrometer metrics, and trace propagation cover the operational path from persistence
              to delivery. Explore <Link to="/reference/persistence/">persistence</Link> and{' '}
              <Link to="/reference/messaging/">messaging</Link> options.
            </p>
          </article>
        </div>
      </div>
    </section>
  );
}
