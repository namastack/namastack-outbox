import React, {type ReactNode} from 'react';
import Head from '@docusaurus/Head';
import Layout from '@theme/Layout';
import {OutboxContent} from '@site/src/components/MarketingPage';

const structuredData = {
  '@context': 'https://schema.org',
  '@type': 'SoftwareApplication',
  name: 'Namastack Outbox',
  description:
    'Open-source transactional outbox library for Spring Boot with at-least-once delivery, ordering, retries, horizontal scaling, and observability.',
  applicationCategory: 'DeveloperApplication',
  operatingSystem: 'JVM',
  programmingLanguage: ['Java', 'Kotlin'],
  url: 'https://www.namastack.io/outbox/',
  license: 'https://github.com/namastack/namastack-outbox/blob/main/LICENSE',
  author: {
    '@type': 'Organization',
    name: 'Namastack',
    url: 'https://www.namastack.io/',
  },
};

export default function OutboxPage(): ReactNode {
  return (
    <Layout
      title="Namastack Outbox"
      description="A transactional outbox library for Spring Boot with reliable at-least-once delivery, per-key ordering, retries, scaling, and observability.">
      <Head>
        <link rel="canonical" href="https://www.namastack.io/outbox/" />
        <meta property="og:type" content="website" />
        <meta property="og:title" content="Namastack Outbox — Transactional event delivery for Spring Boot" />
        <meta property="og:description" content="Reliable at-least-once delivery, strict per-key ordering, retries, horizontal scaling, and observability." />
        <meta property="og:url" content="https://www.namastack.io/outbox/" />
        <meta name="twitter:card" content="summary_large_image" />
        <script type="application/ld+json">{JSON.stringify(structuredData)}</script>
      </Head>
      <OutboxContent />
    </Layout>
  );
}
