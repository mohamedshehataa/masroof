import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { renderAt, useShellEnv } from './shell.helpers';

const html = () => document.documentElement;
const storedValues = () => Object.keys(localStorage).map((k) => localStorage.getItem(k));

describe('REQ-UI-002 language default from the browser (no stored preference)', () => {
  describe('ar-EG browser', () => {
    useShellEnv({ browserLanguage: 'ar-EG' });
    it('REQ-UI-002 renders Arabic with lang=ar dir=rtl', async () => {
      renderAt('/more');
      await screen.findByRole('navigation');
      expect(html()).toHaveAttribute('lang', 'ar');
      expect(html()).toHaveAttribute('dir', 'rtl');
      expect(screen.queryByRole('link', { name: 'Home' })).toBeNull();
    });
  });

  describe('fr-FR browser', () => {
    useShellEnv({ browserLanguage: 'fr-FR' });
    it('REQ-UI-002 falls back to English LTR', async () => {
      renderAt('/more');
      expect((await screen.findAllByRole('link', { name: 'Home' })).length).toBeGreaterThan(0);
      expect(html()).toHaveAttribute('lang', 'en');
      expect(html()).toHaveAttribute('dir', 'ltr');
    });
  });
});

describe('REQ-UI-002 More -> Language switch', () => {
  useShellEnv();

  it('REQ-UI-002 switching to Arabic changes every visible string, flips dir, no reload, and persists', async () => {
    const user = userEvent.setup();
    renderAt('/more');
    await user.click(await screen.findByText('العربية'));

    expect(html()).toHaveAttribute('lang', 'ar');
    expect(html()).toHaveAttribute('dir', 'rtl');
    expect(window.location.pathname).toBe('/more');
    for (const en of ['Home', 'Transactions', 'Insights']) {
      expect(screen.queryByRole('link', { name: en })).toBeNull();
    }
    // no Latin-only visible copy left in the navigation landmark(s)
    for (const nav of screen.getAllByRole('navigation')) expect(nav.textContent).not.toMatch(/[A-Za-z]/);
    expect(storedValues()).toContain('ar');
  });

  it('REQ-UI-002 the stored choice wins over the browser language on the next load', async () => {
    const user = userEvent.setup();
    const first = renderAt('/more');
    await user.click(await screen.findByText('العربية'));
    first.unmount();

    renderAt('/more'); // navigator.language is still en-US
    await screen.findAllByRole('navigation');
    expect(html()).toHaveAttribute('lang', 'ar');
    expect(html()).toHaveAttribute('dir', 'rtl');
  });

  it('REQ-UI-002 switching back to English restores LTR and English labels', async () => {
    const user = userEvent.setup();
    renderAt('/more');
    await user.click(await screen.findByText('العربية'));
    await user.click(await screen.findByText('English'));
    expect(html()).toHaveAttribute('lang', 'en');
    expect(html()).toHaveAttribute('dir', 'ltr');
    expect((await screen.findAllByRole('link', { name: 'Home' })).length).toBeGreaterThan(0);
  });

  it('REQ-UI-002 the skeleton toggle on the home page is replaced: no language button outside More', async () => {
    renderAt('/');
    await screen.findAllByRole('navigation');
    expect(screen.queryByText('العربية')).toBeNull();
  });
});

describe('REQ-UI-005 digits setting in More -> Language/digits', () => {
  useShellEnv();

  it('REQ-UI-005 offers Western and Arabic-Indic digits, Western by default, and the choice persists', async () => {
    const user = userEvent.setup();
    const first = renderAt('/more');
    const western = await screen.findByRole('radio', { name: /western/i });
    const indic = screen.getByRole('radio', { name: /arabic-indic/i });
    expect(western).toBeChecked();
    expect(indic).not.toBeChecked();

    await user.click(indic);
    expect(screen.getByRole('radio', { name: /arabic-indic/i })).toBeChecked();
    first.unmount();

    renderAt('/more');
    expect(await screen.findByRole('radio', { name: /arabic-indic/i })).toBeChecked();
    expect(screen.getByRole('radio', { name: /western/i })).not.toBeChecked();
  });
});
