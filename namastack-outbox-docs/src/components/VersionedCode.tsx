import React from 'react';
import CodeBlock from '@theme/CodeBlock';
import {useDocsVersion} from '@docusaurus/plugin-content-docs/client';

type Props = {
  language: string;
  template: string;
};

export default function VersionedCode({language, template}: Props) {
  let version: {version: string; label: string};

  try {
    version = useDocsVersion();
  } catch {
    version = {
      version: 'current',
      label: 'current',
    };
  }

  const vars = {
    version: version.version,
    versionLabel: version.label,
  };

  const code = template.replace(/{{(\w+)}}/g, (_, key) => {
    return key in vars ? String(vars[key as keyof typeof vars]) : `{{${key}}}`;
  });

  return (
    <CodeBlock language={language}>
      {code}
    </CodeBlock>
  );
}
