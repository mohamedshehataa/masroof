import { useCallback, useSyncExternalStore, type AnchorHTMLAttributes, type FC, type MouseEvent } from 'react';

// Minimal history-API router: five static routes do not justify a routing dependency (bundle budget).
const NAVIGATE_EVENT = 'masroof:navigate';

function subscribe(listener: () => void): () => void {
  window.addEventListener('popstate', listener);
  window.addEventListener(NAVIGATE_EVENT, listener);
  return () => {
    window.removeEventListener('popstate', listener);
    window.removeEventListener(NAVIGATE_EVENT, listener);
  };
}

const snapshot = () => window.location.pathname + window.location.search;

export function navigate(to: string, options: { replace?: boolean } = {}): void {
  if (options.replace) window.history.replaceState(null, '', to);
  else window.history.pushState(null, '', to);
  window.dispatchEvent(new Event(NAVIGATE_EVENT));
}

export interface Location {
  pathname: string;
  search: string;
}

export function useLocation(): Location {
  const full = useSyncExternalStore(subscribe, snapshot);
  const q = full.indexOf('?');
  return q === -1 ? { pathname: full, search: '' } : { pathname: full.slice(0, q), search: full.slice(q) };
}

export const Link: FC<AnchorHTMLAttributes<HTMLAnchorElement> & { href: string }> = ({
  href,
  onClick,
  children,
  ...rest
}) => {
  const handle = useCallback(
    (event: MouseEvent<HTMLAnchorElement>) => {
      onClick?.(event);
      const plain = event.button === 0 && !event.metaKey && !event.ctrlKey && !event.shiftKey && !event.altKey;
      if (event.defaultPrevented || !plain) return;
      event.preventDefault();
      navigate(href);
    },
    [href, onClick],
  );
  return (
    <a href={href} onClick={handle} {...rest}>
      {children}
    </a>
  );
};
