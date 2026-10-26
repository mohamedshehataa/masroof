import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, useSyncExternalStore, type FC } from 'react';
import { useTranslation } from 'react-i18next';
import { apiErrorOf } from '../../api/errors';
import { createInvite, listInvites, listMembers, revokeInvite } from '../../api/generated';
import type { Invite } from '../../api/generated';
import { getMember, subscribeSession } from '../../api/session';
import { usePrefs } from '../../app/prefs';
import { SkeletonRows } from '../../components/common';

const HTTP_CONFLICT = 409;
const HTTP_BAD_REQUEST = 400;
const INVITE_DAYS = 7;

const membersKey = ['members'] as const;
const invitesKey = ['invites'] as const;

/** The invite link; the code sits in the URL fragment so it never reaches a server log. */
export const inviteLink = (code: string, origin: string): string => `${origin}/invite#${code}`;

const InvitePanel: FC<{ invite: Invite; onDone: () => void }> = ({ invite, onDone }) => {
  const { t } = useTranslation();
  const link = inviteLink(invite.code, window.location.origin);
  const [copied, setCopied] = useState<'link' | 'code' | 'failed' | null>(null);
  const canShare = typeof navigator.share === 'function';

  const copy = async (what: 'link' | 'code') => {
    try {
      await navigator.clipboard.writeText(what === 'link' ? link : invite.code);
      setCopied(what);
    } catch {
      setCopied('failed');
    }
  };

  return (
    <section className="stack stretch" aria-labelledby="invite-created">
      <h3 id="invite-created">{t('members.created', { name: invite.displayName })}</h3>
      <p className="muted">{t('members.shownOnce', { days: INVITE_DAYS })}</p>
      <label className="field">
        <span>{t('members.link')}</span>
        <input
          readOnly
          value={link}
          dir="ltr"
          onFocus={(e) => {
            e.currentTarget.select();
          }}
        />
      </label>
      <label className="field">
        <span>{t('members.code')}</span>
        <input
          readOnly
          value={invite.code}
          dir="ltr"
          onFocus={(e) => {
            e.currentTarget.select();
          }}
        />
      </label>
      <div className="stack">
        <button
          type="button"
          className="btn"
          onClick={() => {
            void copy('link');
          }}
        >
          {t('members.copyLink')}
        </button>
        <button
          type="button"
          className="btn btn-plain"
          onClick={() => {
            void copy('code');
          }}
        >
          {t('members.copyCode')}
        </button>
        {canShare ? (
          <button
            type="button"
            className="btn btn-plain"
            onClick={() => {
              void navigator.share({ title: t('members.shareTitle'), url: link }).catch(() => undefined);
            }}
          >
            {t('members.share')}
          </button>
        ) : null}
      </div>
      <p role="status">{copied === null ? '' : copied === 'failed' ? t('members.copyFailed') : t('members.copied')}</p>
      <button type="button" className="btn" onClick={onDone}>
        {t('members.done')}
      </button>
    </section>
  );
};

const InviteForm: FC<{ onCreated: (invite: Invite) => void }> = ({ onCreated }) => {
  const { t } = useTranslation();
  const client = useQueryClient();
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [error, setError] = useState<'conflict' | 'invalid' | 'generic' | null>(null);
  const create = useMutation({
    networkMode: 'always',
    mutationFn: () =>
      createInvite({ body: { displayName: name.trim(), ...(email.trim() === '' ? {} : { email: email.trim() }) } }),
    onSuccess: (invite) => {
      setName('');
      setEmail('');
      setError(null);
      void client.invalidateQueries({ queryKey: invitesKey });
      onCreated(invite);
    },
    onError: (e) => {
      const { status } = apiErrorOf(e);
      setError(status === HTTP_CONFLICT ? 'conflict' : status === HTTP_BAD_REQUEST ? 'invalid' : 'generic');
    },
  });
  return (
    <form
      className="stack stretch"
      onSubmit={(e) => {
        e.preventDefault();
        create.mutate();
      }}
    >
      <label className="field">
        <span>{t('members.name')}</span>
        <input
          required
          maxLength={80}
          value={name}
          onChange={(e) => {
            setName(e.target.value);
          }}
        />
      </label>
      <label className="field">
        <span>{t('members.emailHint')}</span>
        <input
          type="email"
          autoComplete="off"
          dir="ltr"
          value={email}
          onChange={(e) => {
            setEmail(e.target.value);
          }}
        />
      </label>
      {error === null ? null : <p role="alert">{t(`members.error.${error}`)}</p>}
      <button type="submit" className="btn btn-block" disabled={create.isPending || name.trim() === ''}>
        {t('members.invite')}
      </button>
    </form>
  );
};

const PendingInvites: FC = () => {
  const { t } = useTranslation();
  const { lang } = usePrefs();
  const client = useQueryClient();
  const invites = useQuery({ queryKey: invitesKey, queryFn: () => listInvites() });
  const revoke = useMutation({
    networkMode: 'always',
    mutationFn: (id: string) => revokeInvite({ path: { inviteId: id } }),
    onSettled: () => client.invalidateQueries({ queryKey: invitesKey }),
  });
  const fmt = new Intl.DateTimeFormat(lang, { dateStyle: 'medium', timeZone: 'Africa/Cairo' });
  if (invites.isPending) return <SkeletonRows count={1} />;
  if (invites.isError)
    return (
      <p role="alert">
        {t('members.error.load')}{' '}
        <button
          type="button"
          className="btn btn-plain"
          onClick={() => {
            void invites.refetch();
          }}
        >
          {t('common.retry')}
        </button>
      </p>
    );
  if (invites.data.length === 0) return <p className="muted">{t('members.noPending')}</p>;
  return (
    <ul className="stack stretch" aria-label={t('members.pending')}>
      {invites.data.map((i) => (
        <li key={i.id} className="stack">
          <span>
            {i.displayName}{' '}
            <span className="muted">{t('members.expires', { date: fmt.format(new Date(i.expiresAt)) })}</span>
          </span>
          <button
            type="button"
            className="btn btn-plain"
            disabled={revoke.isPending}
            aria-label={t('members.revokeFor', { name: i.displayName })}
            onClick={() => {
              revoke.mutate(i.id);
            }}
          >
            {t('members.revoke')}
          </button>
        </li>
      ))}
    </ul>
  );
};

export const MembersSection: FC = () => {
  const { t } = useTranslation();
  const me = useSyncExternalStore(subscribeSession, getMember);
  const [created, setCreated] = useState<Invite | null>(null);
  const members = useQuery({ queryKey: membersKey, queryFn: () => listMembers() });
  const isAdmin = me?.role === 'ADMIN';
  return (
    <section className="stack stretch" aria-labelledby="members-title">
      <h2 id="members-title">{t('members.title')}</h2>
      {members.isPending ? (
        <SkeletonRows count={2} />
      ) : members.isError ? (
        <p role="alert">
          {t('members.error.load')}{' '}
          <button
            type="button"
            className="btn btn-plain"
            onClick={() => {
              void members.refetch();
            }}
          >
            {t('common.retry')}
          </button>
        </p>
      ) : (
        <ul className="stack stretch" aria-label={t('members.title')}>
          {members.data.map((m) => (
            <li key={m.id}>
              {m.displayName} <span className="muted">{t(`members.role.${m.role}`)}</span>
            </li>
          ))}
        </ul>
      )}
      {isAdmin ? (
        <>
          <h3>{t('members.pending')}</h3>
          <PendingInvites />
          {created === null ? (
            <InviteForm onCreated={setCreated} />
          ) : (
            <InvitePanel
              invite={created}
              onDone={() => {
                setCreated(null);
              }}
            />
          )}
        </>
      ) : null}
    </section>
  );
};
