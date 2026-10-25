import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { renderAt, useShellEnv } from './shell.helpers';

const isDark = () => document.documentElement.classList.contains('dark');

describe('REQ-UI-003 theme: More -> Theme', () => {
  useShellEnv();

  it('REQ-UI-003 offers System/Light/Dark, defaulting to System', async () => {
    renderAt('/more');
    expect(await screen.findByRole('radio', { name: /system/i })).toBeChecked();
    expect(screen.getByRole('radio', { name: /light/i })).not.toBeChecked();
    expect(screen.getByRole('radio', { name: /dark/i })).not.toBeChecked();
  });

  it('REQ-UI-003 choosing Dark applies the dark palette (html.dark) and Light removes it', async () => {
    const user = userEvent.setup();
    renderAt('/more');
    await user.click(await screen.findByRole('radio', { name: /dark/i }));
    expect(isDark()).toBe(true);
    await user.click(screen.getByRole('radio', { name: /light/i }));
    expect(isDark()).toBe(false);
  });

  it('REQ-UI-003 the theme choice persists across a reload', async () => {
    const user = userEvent.setup();
    const first = renderAt('/more');
    await user.click(await screen.findByRole('radio', { name: /dark/i }));
    first.unmount();
    document.documentElement.removeAttribute('class');

    renderAt('/more');
    expect(await screen.findByRole('radio', { name: /dark/i })).toBeChecked();
    expect(isDark()).toBe(true);
  });
});

describe('REQ-UI-003 theme follows prefers-color-scheme when System', () => {
  useShellEnv({ prefersDark: true });

  it('REQ-UI-003 dark OS preference applies the dark palette with no stored choice', async () => {
    renderAt('/more');
    await screen.findAllByRole('navigation');
    expect(isDark()).toBe(true);
  });

  it('REQ-UI-003 an explicit Light choice beats a dark OS preference', async () => {
    const user = userEvent.setup();
    renderAt('/more');
    await user.click(await screen.findByRole('radio', { name: /light/i }));
    expect(isDark()).toBe(false);
  });
});
