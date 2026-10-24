import { cleanup, render, screen } from '@testing-library/react';
import { I18nextProvider } from 'react-i18next';
import { afterEach, describe, expect, it } from 'vitest';
import { makeI18n } from '../../test/i18n';
import { byFullText } from '../../test/matchers';
import { VersionLine } from './VersionLine';

afterEach(cleanup);

async function renderLine(lng: 'en' | 'ar', version: string, gitSha: string) {
  const i18n = await makeI18n(lng);
  return render(
    <I18nextProvider i18n={i18n}>
      <VersionLine version={version} gitSha={gitSha} />
    </I18nextProvider>,
  );
}

describe('REQ-OPS-021 version line', () => {
  it('REQ-OPS-021 shows English copy with SHA cut to 7 chars, version and SHA in bdi dir=ltr', async () => {
    const { container } = await renderLine('en', '0.1.0', '1a2b3c4d5e6f');
    expect(screen.getByText(byFullText('Backend 0.1.0 (1a2b3c4)'))).toBeInTheDocument();
    const bdis = Array.from(container.querySelectorAll('bdi[dir="ltr"]')).map((b) => b.textContent);
    expect(bdis).toEqual(['0.1.0', '1a2b3c4']);
  });

  it('REQ-OPS-021 shows only Arabic copy in ar, never both languages', async () => {
    const { container } = await renderLine('ar', '0.1.0', '1a2b3c4d5e6f');
    expect(container.textContent).toBe('الخادم 0.1.0 (1a2b3c4)');
    expect(container.textContent).not.toContain('Backend');
  });

  it('REQ-OPS-021 renders an injected version string as literal text and creates no img element', async () => {
    const evil = '<img src=x onerror=alert(1)>';
    const { container } = await renderLine('en', evil, '1a2b3c4d5e6f');
    expect(container.querySelector('img')).toBeNull();
    expect(container.textContent).toContain(evil);
  });
});
