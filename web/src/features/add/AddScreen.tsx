import { useRef, useState, type FC } from 'react';
import { useTranslation } from 'react-i18next';
import type { ExpenseCreate } from '../../api/generated';
import { useAccounts, useCategories, useCreateExpense } from '../../api/queries';
import { usePrefs } from '../../app/prefs';
import { SkeletonRows, useOnline } from '../../components/common';
import { newUuid } from '../../lib/uuid';
import { addDays, cairoToday, occurredAtFor } from '../../lib/dates';
import { EMPTY_KEYPAD, keypadDisplay, keypadToMinor, pressKey, type KeypadKey } from '../../lib/money';
import { useRouter } from '../../router';

const KEYS: KeypadKey[] = ['1', '2', '3', '4', '5', '6', '7', '8', '9', '.', '0', 'back'];
const HTTP_VALIDATION = new Set([400, 422]);

function failureKey(error: unknown, online: boolean): 'errorOffline' | 'errorValidation' | 'errorSave' {
  if (!online) return 'errorOffline';
  const status = typeof error === 'object' && error !== null && 'status' in error ? error.status : null;
  return typeof status === 'number' && HTTP_VALIDATION.has(status) ? 'errorValidation' : 'errorSave';
}

export const AddScreen: FC = () => {
  const { t } = useTranslation();
  const { lang, digits } = usePrefs();
  const { navigate } = useRouter();
  const online = useOnline();
  const categories = useCategories();
  const accounts = useAccounts();
  const create = useCreateExpense();
  const [keypad, setKeypad] = useState(EMPTY_KEYPAD);
  const [categoryId, setCategoryId] = useState('');
  const [accountId, setAccountId] = useState('');
  const [day, setDay] = useState(() => cairoToday());
  const [note, setNote] = useState('');
  const [amountError, setAmountError] = useState(false);
  const [dateError, setDateError] = useState(false);
  // One Idempotency-Key per entry: retries of the same entry reuse it (a lost response cannot create a duplicate);
  // editing any field starts a new entry with a new key. The time of day is excluded from the signature.
  const attempt = useRef<{ signature: string; key: string } | null>(null);

  const keyLabel = (k: KeypadKey) => (k === 'back' ? t('add.backspace') : k === '.' ? t('add.decimal') : k);

  const onSave = () => {
    const amountMinor = keypadToMinor(keypad);
    if (amountMinor <= 0) {
      setAmountError(true);
      return;
    }
    if (day > addDays(cairoToday(), 1)) {
      setDateError(true);
      return;
    }
    setAmountError(false);
    setDateError(false);
    const body: ExpenseCreate = {
      type: 'EXPENSE',
      amount: { amountMinor, currency: 'EGP' },
      occurredAt: occurredAtFor(day),
      ...(categoryId === '' ? {} : { categoryId }),
      ...(accountId === '' ? { paymentMethod: 'CASH' as const } : { paymentAccountId: accountId }),
      ...(note.trim() === '' ? {} : { notes: note.trim() }),
    };
    const signature = JSON.stringify([amountMinor, categoryId, accountId, day, note.trim()]);
    if (attempt.current?.signature !== signature) attempt.current = { signature, key: newUuid() };
    create.mutate(
      { body, idempotencyKey: attempt.current.key },
      {
        onSuccess: () => {
          navigate('/transactions');
        },
      },
    );
  };

  return (
    <form
      className="add"
      noValidate
      onSubmit={(e) => {
        e.preventDefault();
        onSave();
      }}
    >
      <div className="section-head">
        <h1>{t('add.title')}</h1>
        <button
          type="button"
          className="btn btn-plain"
          onClick={() => {
            navigate('/');
          }}
        >
          {t('add.close')}
        </button>
      </div>

      <p className="amount-display" aria-live="polite" aria-label={t('add.amount')}>
        <bdi className="amount">{keypadDisplay(keypad, digits)}</bdi>
      </p>
      {amountError ? <p role="alert">{t('add.errorAmount')}</p> : null}
      <div className="keypad" dir="ltr" role="group" aria-label={t('add.keypad')}>
        {KEYS.map((k) => (
          <button
            key={k}
            type="button"
            className="key"
            aria-label={keyLabel(k)}
            onClick={() => {
              setKeypad((s) => pressKey(s, k));
              setAmountError(false);
            }}
          >
            {k === 'back'
              ? '⌫'
              : k === '.'
                ? digits === 'arabic'
                  ? '٫'
                  : '.'
                : digits === 'arabic'
                  ? keypadDisplay({ integer: k, fraction: null }, digits)
                  : k}
          </button>
        ))}
      </div>

      <fieldset className="categories">
        <legend>{t('add.category')}</legend>
        {categories.isError ? (
          <p role="alert">
            {t('add.categoriesError')}{' '}
            <button
              type="button"
              className="btn"
              onClick={() => {
                void categories.refetch();
              }}
            >
              {t('common.retry')}
            </button>
          </p>
        ) : categories.isPending && categories.fetchStatus !== 'paused' ? (
          <SkeletonRows count={2} />
        ) : categories.data?.length === 0 ? (
          <p className="muted">{t('add.noCategories')}</p>
        ) : (
          <div className="grid">
            {categories.data
              ?.filter((c) => !c.archived)
              .map((c) => (
                <button
                  key={c.id}
                  type="button"
                  className="chip"
                  aria-pressed={categoryId === c.id}
                  onClick={() => {
                    setCategoryId(categoryId === c.id ? '' : c.id);
                  }}
                >
                  {lang === 'ar' ? c.nameAr : c.nameEn}
                </button>
              ))}
          </div>
        )}
      </fieldset>

      <label className="field">
        <span>{t('add.account')}</span>
        <select
          value={accountId}
          onChange={(e) => {
            setAccountId(e.target.value);
          }}
        >
          <option value="">{t('add.noAccount')}</option>
          {accounts.data?.map((a) => (
            <option key={a.id} value={a.id}>
              {a.nickname ?? a.institution ?? a.type}
              {a.last4 ? ` ${a.last4}` : ''}
            </option>
          ))}
        </select>
      </label>
      {accounts.isError ? (
        <p role="alert">
          {t('add.accountsError')}{' '}
          <button
            type="button"
            className="btn"
            onClick={() => {
              void accounts.refetch();
            }}
          >
            {t('common.retry')}
          </button>
        </p>
      ) : null}
      <label className="field">
        <span>{t('add.date')}</span>
        <input
          type="date"
          value={day}
          max={addDays(cairoToday(), 1)}
          aria-invalid={dateError}
          required
          onChange={(e) => {
            if (e.target.value !== '') setDay(e.target.value);
          }}
        />
      </label>
      {dateError ? <p role="alert">{t('add.errorDate')}</p> : null}
      <label className="field">
        <span>{t('add.note')}</span>
        <input
          type="text"
          maxLength={500}
          value={note}
          placeholder={t('add.notePlaceholder')}
          onChange={(e) => {
            setNote(e.target.value);
          }}
        />
      </label>

      {!online ? (
        <p role="alert">{t('add.errorOffline')}</p>
      ) : create.isError ? (
        <p role="alert">{t(`add.${failureKey(create.error, online)}`)}</p>
      ) : null}
      <button type="submit" className="btn btn-block" disabled={create.isPending || !online}>
        {create.isPending ? t('add.saving') : t('add.save')}
      </button>
    </form>
  );
};
