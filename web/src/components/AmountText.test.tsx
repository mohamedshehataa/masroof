import { render, screen } from '@testing-library/react';
import { I18nextProvider } from 'react-i18next';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { DIGITS_KEY } from '../shell/settings';
import { makeI18n } from '../test/i18n';
import { AmountText } from './AmountText';

const money = { amountMinor: 125000, currency: 'EGP' };

beforeEach(() => {
  localStorage.clear();
});
afterEach(() => {
  localStorage.clear();
});

describe('REQ-UI-005 AmountText', () => {
  it('REQ-UI-005 renders English amounts with tabular numerals class', async () => {
    const i18n = await makeI18n('en');
    render(
      <I18nextProvider i18n={i18n}>
        <AmountText money={money} />
      </I18nextProvider>,
    );
    const el = screen.getByText(/1,250/);
    expect(el.textContent).toBe('1,250 EGP');
    expect(el).toHaveClass('amount');
  });

  it('REQ-UI-005 follows the stored digits setting in Arabic', async () => {
    localStorage.setItem(DIGITS_KEY, 'arabic-indic');
    const i18n = await makeI18n('ar');
    render(
      <I18nextProvider i18n={i18n}>
        <AmountText money={money} />
      </I18nextProvider>,
    );
    expect(screen.getByText(/١٬٢٥٠/).textContent).toBe('١٬٢٥٠ ج.م');
  });
});
