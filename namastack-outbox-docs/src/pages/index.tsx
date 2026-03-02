import type {ReactNode} from 'react';
import clsx from 'clsx';
import Link from '@docusaurus/Link';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';
import Layout from '@theme/Layout';
import HomepageFeatures from '@site/src/components/HomepageFeatures';
import HowItWorks from "@site/src/components/HowItWorks";
import Heading from '@theme/Heading';
import Logo from '@site/static/img/namastack_logo.svg';
import { IconJetpackFilled, IconBrandGithubFilled } from '@tabler/icons-react';
import styles from './index.module.css';
import VantaBackground from "../components/VantaBackground";

function HomepageHeader() {
  const {siteConfig} = useDocusaurusContext();

  return (
    <VantaBackground
      minHeight="10vh"
      options={{
        color: 0xa89060,
        backgroundColor: 0x000000,
        mouseControls: true,
        touchControls: true,
      }}
      style={{ display: 'flex', alignItems: 'center', justifyContent: 'center' }}
    >
      <header className={clsx('hero hero--primary', styles.heroBanner)}>
        <div className="container" style={{ position: "relative" }}>
          <Logo className="hero__logo" />
          <Heading as="h1" className="hero__title">
            {siteConfig.title}
          </Heading>
          <p className="hero__subtitle">{siteConfig.tagline}</p>
          <div className="row">
            <div className="col col--3 col--offset-3">
              <div className={styles.buttons} className="text--center margin-top--md">
                <Link
                  className="button button--secondary button--lg"
                  to="/quickstart">
                  Getting started
                  <IconJetpackFilled />
                </Link>
              </div>
            </div>
            <div className="col col--3 col--offset-0">
              <div className={styles.buttons} className="text--center margin-top--md">
                <Link
                  className="button button--secondary button--lg"
                  to="https://github.com/namastack/namastack-outbox">
                  View on GitHub
                  <IconBrandGithubFilled />
                </Link>
              </div>
            </div>
          </div>
        </div>
      </header>
    </VantaBackground>
  );
}

export default function Home(): ReactNode {
  const {siteConfig} = useDocusaurusContext();
  return (
    <Layout
      title={`${siteConfig.title}`}
      description="Official documentation for Namastack Outbox - a reliable transactional messaging solution implementing the Outbox Pattern for distributed systems. Learn installation, configuration, integrations, and best practices for building consistent, event-driven architectures.">
      <HomepageHeader />
      <main>
        <HomepageFeatures />
        <HowItWorks />
      </main>
    </Layout>
  );
}
