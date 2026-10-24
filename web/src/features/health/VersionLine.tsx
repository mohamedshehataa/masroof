import { Fragment, type FC } from 'react';
import { useTranslation } from 'react-i18next';

export interface VersionLineProps {
  version: string;
  gitSha: string;
}

const SHA_LENGTH = 7;
const VERSION_TOKEN = '@@version@@';
const SHA_TOKEN = '@@sha@@';

/** Version and SHA are isolated LTR (bdi) so they never reorder inside RTL copy; values stay text nodes. */
export const VersionLine: FC<VersionLineProps> = ({ version, gitSha }) => {
  const { t } = useTranslation();
  const copy = t('health.version', { version: VERSION_TOKEN, sha: SHA_TOKEN });
  const parts = copy.split(/(@@version@@|@@sha@@)/);
  return (
    <p className="version-line">
      {parts.map((part, i) => {
        if (part === VERSION_TOKEN)
          return (
            <bdi key={i} dir="ltr">
              {version}
            </bdi>
          );
        if (part === SHA_TOKEN)
          return (
            <bdi key={i} dir="ltr">
              {gitSha.slice(0, SHA_LENGTH)}
            </bdi>
          );
        return <Fragment key={i}>{part}</Fragment>;
      })}
    </p>
  );
};
