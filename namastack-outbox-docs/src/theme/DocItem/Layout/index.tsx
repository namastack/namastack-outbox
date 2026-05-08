import React from 'react';
import DocItemLayout from '@theme-original/DocItem/Layout';
import type DocItemLayoutType from '@theme/DocItem/Layout';
import type {WrapperProps} from '@docusaurus/types';
import Head from '@docusaurus/Head';
import {useDoc} from '@docusaurus/plugin-content-docs/client';

type Props = WrapperProps<typeof DocItemLayoutType>;

export default function DocItemLayoutWrapper(props: Props): React.ReactNode {
  const {metadata, frontMatter} = useDoc();

  const description =
    (frontMatter.description as string | undefined) || metadata.description;

  const structuredData = {
    '@context': 'https://schema.org',
    '@type': 'TechArticle',
    headline: metadata.title,
    ...(description ? {description} : {}),
    url: `https://www.namastack.io${metadata.permalink}`,
    inLanguage: 'en',
    isPartOf: {
      '@type': 'TechArticle',
      name: 'Namastack Outbox Documentation',
      url: 'https://www.namastack.io/outbox/',
    },
    author: {
      '@type': 'Organization',
      name: 'Namastack',
      url: 'https://www.namastack.io',
    },
  };

  return (
    <>
      <Head>
        <script type="application/ld+json">
          {JSON.stringify(structuredData)}
        </script>
      </Head>
      <DocItemLayout {...props} />
    </>
  );
}
