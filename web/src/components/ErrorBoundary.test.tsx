import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState, type FC } from 'react';
import { I18nextProvider } from 'react-i18next';
import { describe, expect, it, vi } from 'vitest';
import { makeI18n } from '../test/i18n';
import { ErrorBoundary } from './ErrorBoundary';

let shouldThrow = true;
const Bomb: FC = () => {
  if (shouldThrow) throw new Error('boom');
  return <p>fine</p>;
};

const Harness: FC = () => {
  const [n] = useState(0);
  return (
    <ErrorBoundary>
      <span hidden>{n}</span>
      <Bomb />
    </ErrorBoundary>
  );
};

describe('REQ-UI-004 global error boundary', () => {
  it('REQ-UI-004 shows Retry/Reload and recovers on Retry', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const i18n = await makeI18n('en');
    render(
      <I18nextProvider i18n={i18n}>
        <Harness />
      </I18nextProvider>,
    );
    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Reload' })).toBeInTheDocument();
    shouldThrow = false;
    await userEvent.setup().click(screen.getByRole('button', { name: 'Retry' }));
    expect(screen.getByText('fine')).toBeInTheDocument();
    vi.restoreAllMocks();
  });
});
