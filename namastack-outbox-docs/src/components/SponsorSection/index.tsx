import React from 'react';
import Link from '@docusaurus/Link';
import Heading from '@theme/Heading';
import { IconHeartFilled } from '@tabler/icons-react';
import styles from './styles.module.css';

const SponsorSection: React.FC = () => {
  return (
    <section className={`padding-vert--xl ${styles.wrapper}`}>
      <div className="container">
        <div className={styles.inner}>
          <div className={styles.icon}>
            <IconHeartFilled size={48} color="#e05d78" />
          </div>
          <Heading as="h2">Support the Project</Heading>
          <p className={styles.subtitle}>
            Namastack Outbox started as a personal passion project — built because reliable event
            publishing is much harder than it looks. A lot of time goes into maintaining,
            improving, and documenting this library. If it saves you time or gives you confidence
            in your architecture, consider sponsoring to keep it going.
          </p>
          <div className={styles.actions}>
            <Link
              className="button button--primary button--lg"
              href="https://github.com/sponsors/namastack">
              Become a Sponsor
            </Link>
            <Link
              className="button button--secondary button--lg"
              to="/sponsor">
              Learn more
            </Link>
          </div>
        </div>
      </div>
    </section>
  );
};

export default SponsorSection;
