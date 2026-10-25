import { createContext, useCallback, useContext, useEffect, useMemo, useState, type FC, type ReactNode } from 'react';

// Tiny history-API router: a handful of static routes do not justify a router dependency (docs/DECISIONS.md).
interface RouterValue {
  path: string;
  navigate: (to: string, opts?: { replace?: boolean }) => void;
}

const RouterContext = createContext<RouterValue>({ path: '/', navigate: () => undefined });

export const RouterProvider: FC<{ children: ReactNode }> = ({ children }) => {
  const [path, setPath] = useState(() => window.location.pathname);
  useEffect(() => {
    const onPop = () => {
      setPath(window.location.pathname);
    };
    window.addEventListener('popstate', onPop);
    return () => {
      window.removeEventListener('popstate', onPop);
    };
  }, []);
  const navigate = useCallback((to: string, opts?: { replace?: boolean }) => {
    if (opts?.replace === true) window.history.replaceState(null, '', to);
    else window.history.pushState(null, '', to);
    setPath(new URL(to, window.location.origin).pathname);
  }, []);
  const value = useMemo(() => ({ path, navigate }), [path, navigate]);
  return <RouterContext value={value}>{children}</RouterContext>;
};

export const useRouter = (): RouterValue => useContext(RouterContext);

export const Link: FC<{ to: string; className?: string; current?: boolean; children: ReactNode }> = ({
  to,
  className,
  current,
  children,
}) => {
  const { navigate } = useRouter();
  return (
    <a
      href={to}
      className={className}
      aria-current={current === true ? 'page' : undefined}
      onClick={(e) => {
        if (e.metaKey || e.ctrlKey || e.shiftKey || e.button !== 0) return;
        e.preventDefault();
        navigate(to);
      }}
    >
      {children}
    </a>
  );
};
