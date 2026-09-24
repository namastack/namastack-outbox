import React, {type ReactNode} from 'react';
import Head from '@docusaurus/Head';
import Layout from '@theme/Layout';
import {HomeContent} from '@site/src/components/MarketingPage';

const structuredData = {
  '@context': 'https://schema.org',
  '@type': 'Organization',
  name: 'Namastack',
  url: 'https://www.namastack.io/',
  logo: 'https://www.namastack.io/img/namastack_logo.svg',
  sameAs: ['https://github.com/namastack'],
  description: 'Open-source infrastructure for reliable event-driven systems.',
};

export default function Home(): ReactNode {
  return (
    <Layout
      title="Reliable foundations for event-driven systems"
      description="Namastack builds open-source infrastructure for reliable event-driven systems. Explore Namastack Outbox for transactional event delivery in Spring Boot.">
      <Head>
        <link rel="canonical" href="https://www.namastack.io/" />
        <meta property="og:type" content="website" />
        <meta property="og:title" content="Namastack — Reliable foundations for event-driven systems" />
        <meta property="og:description" content="Open-source infrastructure for reliable event-driven systems." />
        <meta property="og:url" content="https://www.namastack.io/" />
        <meta name="twitter:card" content="summary_large_image" />
        <script type="application/ld+json">{JSON.stringify(structuredData)}</script>
      </Head>
      <HomeContent />
    </Layout>
  );
}
