import type {ReactNode} from 'react';
import clsx from 'clsx';
import Link from '@docusaurus/Link';
import Heading from '@theme/Heading';
import styles from './styles.module.css';
import {IconAppsFilled} from '@tabler/icons-react';

export default function ModulithPromotion(): ReactNode {
  return (
      <section className={styles.modulithSection}>
        <div className="container">
          <div className={clsx('card', styles.modulithCard)}>
            <div className="card__body">
              <div className="row">
                <div className="col col--2 text--center"
                     style={{display: 'flex', alignItems: 'center', justifyContent: 'center'}}>
                  <IconAppsFilled className={styles.modulithIcon}/>
                </div>
                <div className="col col--7">
                  <Heading as="h2" className={styles.modulithTitle}>
                    Spring Modulith Integration
                  </Heading>
                  <p className={styles.modulithDescription}>
                    Seamlessly integrate Namastack Outbox with Spring Modulith for outbox-backed
                    event externalization.
                    Get transactional guarantees, automatic retry handling, and production-ready
                    reliability for your modular monolithic applications.
                  </p>
                </div>
                <div className="col col--3"
                     style={{display: 'flex', alignItems: 'center', justifyContent: 'center'}}>
                  <Link
                      className="button button--primary button--lg"
                      to="/reference/spring-modulith">
                    Learn More
                    <IconAppsFilled style={{marginLeft: '0.5rem'}}/>
                  </Link>
                </div>
              </div>
            </div>
          </div>
        </div>
      </section>
  );
}

