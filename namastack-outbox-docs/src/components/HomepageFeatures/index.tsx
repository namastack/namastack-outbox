import type {ReactNode} from 'react';
import clsx from 'clsx';
import Heading from '@theme/Heading';
import styles from './styles.module.css';
import { useColorMode } from '@docusaurus/theme-common'
import { IconShieldCheckeredFilled, IconChartDots3Filled, IconAutomaticGearboxFilled, IconExclamationCircleFilled, IconBinocularsFilled, IconThumbUpFilled } from '@tabler/icons-react';

type FeatureItem = {
  title: string;
  Svg: React.ComponentType<React.ComponentProps<'svg'>>;
  description: ReactNode;
};

const FeatureList: FeatureItem[] = [
  {
    title: 'Guaranteed Reliability',
    Svg: IconShieldCheckeredFilled,
    description: (
      <>
        Never lose a message: All records are saved together with your business data, ensuring
        that no important information is lost - even in the event of failures.
      </>
    ),
  },
  {
    title: 'Effortless Scalability',
    Svg: IconChartDots3Filled,
    description: (
      <>
        Grow with your business: The system automatically distributes work across multiple
        application instances, so you can handle more load without manual intervention.
      </>
    ),
  },
  {
    title: 'Flexible Integration',
    Svg: IconAutomaticGearboxFilled,
    description: (
      <>
        Easily connect to your business processes: Use simple, type-safe handlers to process any
        kind of event, command, or notification, tailored to your needs.
      </>
    ),
  },
  {
      title: 'Automatic Error Handling',
      Svg: IconExclamationCircleFilled,
      description: (
        <>
          Reduce operational risk: Built-in retry and fallback mechanisms ensure that temporary
          issues are handled automatically, and critical failures are managed gracefully.
        </>
      ),
    },
    {
      title: 'Full Observability',
      Svg: IconBinocularsFilled,
      description: (
        <>
          Gain insights and traceability: Context propagation and built-in metrics provide
          end-to-end visibility, making it easy to monitor, audit, and troubleshoot your event
          flows.
        </>
      ),
    },
    {
      title: 'Seamless Operations',
      Svg: IconThumbUpFilled,
      description: (
        <>
          Works with your existing infrastructure: Supports all major databases and integrates
          smoothly with Spring Boot, so you can adopt it without disrupting your current systems.
        </>
      ),
    },
];

function Feature({title, Svg, description, fill}: FeatureItem) {
  return (
    <div className={clsx('col col--4')}>
      <div className="text--center">
        <Svg className={styles.featureSvg} role="img" />
      </div>
      <div className="text--center padding-horiz--md">
        <Heading as="h3">{title}</Heading>
        <p>{description}</p>
      </div>
    </div>
  );
}

export default function HomepageFeatures(): ReactNode {
  return (
    <section className={styles.features}>
      <div className="container">
        <div className="row">
          {FeatureList.map((props, idx) => (
            <Feature key={idx} {...props} />
          ))}
        </div>
      </div>
    </section>
  );
}
