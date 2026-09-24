import React, {type ReactNode} from 'react';
import Head from '@docusaurus/Head';
import Link from '@docusaurus/Link';
import Layout from '@theme/Layout';
import Heading from '@theme/Heading';
import {
  IconBook2,
  IconBrandGithubFilled,
  IconRocket,
  IconShieldCheckeredFilled,
  IconSettings,
} from '@tabler/icons-react';
import styles from './styles.module.css';

const sections = [
  {
    title: 'Getting started',
    description: 'Install a persistence starter, schedule a record, register a handler, and run the application.',
    to: '/docs/quickstart/',
    icon: IconRocket,
  },
  {
    title: 'Reference',
    description: 'Explore persistence, scheduling, processing, messaging, observability, configuration, and operations.',
    to: '/docs/reference/',
    icon: IconBook2,
  },
  {
    title: 'Configuration',
    description: 'Review the complete Spring Boot configuration model and its defaults.',
    to: '/docs/reference/configuration/',
    icon: IconSettings,
  },
  {
    title: 'Reliability guarantees',
    description: 'Understand transactions, at-least-once delivery, possible duplicates, ordering, and consumer responsibilities.',
    to: '/docs/reference/guarantees/',
    icon: IconShieldCheckeredFilled,
  },
];

export default function DocumentationHome(): ReactNode {
  return (
    <Layout
      title="Namastack Outbox documentation"
      description="Technical documentation for installing, configuring, integrating, and operating Namastack Outbox.">
      <Head>
        <link rel="canonical" href="https://www.namastack.io/docs/" />
        <meta property="og:title" content="Namastack Outbox documentation" />
        <meta property="og:description" content="Install, configure, integrate, and operate Namastack Outbox." />
        <meta property="og:url" content="https://www.namastack.io/docs/" />
      </Head>
      <main className={styles.page}>
        <header className={styles.hero}>
          <div className={styles.container}>
            <span className={styles.eyebrow}>Documentation · Namastack Outbox</span>
            <Heading as="h1">Build and operate a reliable transactional outbox.</Heading>
            <p>
              Technical guidance for adding Namastack Outbox to a Spring Boot application,
              choosing persistence and messaging modules, and running it in production.
            </p>
            <div className={styles.actions}>
              <Link className="button button--primary" to="/docs/quickstart/">Start the quickstart</Link>
              <Link className="button button--secondary" to="/docs/reference/">Browse reference</Link>
              <Link className={styles.githubLink} href="https://github.com/namastack/namastack-outbox">
                <IconBrandGithubFilled aria-hidden="true" /> GitHub
              </Link>
            </div>
          </div>
        </header>
        <section className={styles.content}>
          <div className={styles.container}>
            <div className={styles.grid}>
              {sections.map(({title, description, to, icon: Icon}) => (
                <Link className={styles.card} key={title} to={to}>
                  <Icon aria-hidden="true" />
                  <Heading as="h2">{title}</Heading>
                  <p>{description}</p>
                  <span>Read documentation →</span>
                </Link>
              ))}
            </div>
            <aside className={styles.notice}>
              <strong>Delivery model</strong>
              <span>
                Namastack Outbox provides at-least-once delivery. Handlers and downstream
                consumers should be idempotent because a record may be delivered more than once.
              </span>
              <Link to="/docs/reference/guarantees/">Review the guarantees</Link>
            </aside>
          </div>
        </section>
      </main>
    </Layout>
  );
}
