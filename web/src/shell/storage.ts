// Tiny localStorage-backed settings store with change notification, shared by language, digits, theme and session.
const listeners = new Set<() => void>();

export function readStored(key: string): string | null {
  try {
    return window.localStorage.getItem(key);
  } catch {
    return null;
  }
}

export function writeStored(key: string, value: string | null): void {
  try {
    if (value === null) window.localStorage.removeItem(key);
    else window.localStorage.setItem(key, value);
  } catch {
    // Storage may be unavailable (private mode); the in-memory UI state still updates through the listeners.
  }
  listeners.forEach((l) => {
    l();
  });
}

export function subscribeStored(listener: () => void): () => void {
  listeners.add(listener);
  window.addEventListener('storage', listener);
  return () => {
    listeners.delete(listener);
    window.removeEventListener('storage', listener);
  };
}
