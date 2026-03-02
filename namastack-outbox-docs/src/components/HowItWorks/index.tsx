import React from "react";
import { useColorMode } from '@docusaurus/theme-common'
import styles from "./styles.module.css";

const HowItWorks: React.FC = () => {
  const { colorMode } = useColorMode();

  const imageSrc =
    colorMode === "dark"
      ? "img/landing/diagram_dark.svg"
      : "img/landing/diagram_light.svg";

  return (
    <section className={`padding-vert--xl ${styles.wrapper}`}>
      <div className="container">
        <h2 className="margin-bottom--lg">How It Works</h2>
        <div className="row">
          <div className="col col--6">
            <p>
              <strong>Namastack Outbox for Spring Boot</strong> brings bulletproof
              reliability to your event-driven systems - combining transactional
              integrity with seamless message delivery.
            </p>

            <p>
              When your application writes data, both the <strong>entity table</strong> and
              the <strong>outbox table</strong> are updated within a single{" "}
              <strong>ACID transaction</strong>. This guarantees that your domain state
              and outgoing events remain consistent - even if the system crashes
              mid-operation.
            </p>

            <p>
              A background <strong>outbox scheduler</strong> polls the database for new
              records and hands them off to your custom{" "}
              <strong>outbox processor</strong> - a lightweight interface you implement
              to publish messages to your broker (e.g. Kafka, RabbitMQ, SNS).
            </p>

            <p>
              Once messages are successfully delivered, they’re marked as processed.
            </p>

            <h3 className="margin-top--lg">This architecture ensures:</h3>

            <ul>
              <li>
                <strong>Zero message loss</strong>, even under failure
              </li>
              <li>
                <strong>Strict per-aggregate ordering</strong> for deterministic processing
              </li>
              <li>
                <strong>Horizontal scalability</strong> with hash-based partitioning
              </li>
              <li>
                <strong>At-least-once delivery</strong> with safe retry policies and observability
              </li>
            </ul>

            <p className="margin-top--lg">
              With <strong>Namastack Outbox for Spring Boot</strong>, you get the
              reliability of database transactions - and the resilience of
              message-driven design.
            </p>

            <p>
              <strong>Build confidently. Scale safely. Never lose an event again.</strong>
            </p>
          </div>

          <div className="col col--6 text--center">
            <img
              src={imageSrc}
              alt="Outbox architecture diagram"
              className={styles.diagramImage}
            />
          </div>

        </div>
      </div>
    </section>
  );
};

export default HowItWorks;
