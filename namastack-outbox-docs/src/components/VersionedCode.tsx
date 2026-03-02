import React from 'react';
import CodeBlock from '@theme/CodeBlock';
import {useDocsVersion} from '@docusaurus/plugin-content-docs/client';

type Props = {
  language: string;
  template: string;
};

export default function VersionedCode({language, template}: Props) {
  const version = useDocsVersion();
  const vars = {
    version: version.version,
    versionLabel: version.label,
  };

console.log(version.version)
  const code = template.replace(/{{(\w+)}}/g, (_, key) => {
    return key in vars ? String(vars[key as keyof typeof vars]) : `{{${key}}}`;
  });

  return (
    <CodeBlock language={language}>
      {code}
    </CodeBlock>
  );
}
