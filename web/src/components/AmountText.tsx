import type { FC } from 'react';
import { useTranslation } from 'react-i18next';
import { formatMoney, type Money } from '../lib/money';
import { useDigits } from '../shell/settings';

/** Renders Money per the active language and digit setting; bidi-isolated with tabular numerals. */
export const AmountText: FC<{ money: Money }> = ({ money }) => {
  const { i18n } = useTranslation();
  const [digits] = useDigits();
  const locale = i18n.language === 'ar' ? 'ar' : 'en';
  return (
    <bdi className="amount" dir={locale === 'ar' ? 'rtl' : 'ltr'}>
      {formatMoney(money, { locale, digits })}
    </bdi>
  );
};
