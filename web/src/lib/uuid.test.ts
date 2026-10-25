import { afterEach, describe, expect, it, vi } from 'vitest';
import { newUuid } from './uuid';

const V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('REQ-UI-023 Idempotency-Key generation', () => {
  it('REQ-UI-023 returns a v4 UUID', () => {
    expect(newUuid()).toMatch(V4);
  });

  it('REQ-UI-023 still works in an insecure context where crypto.randomUUID is missing (http LAN origin)', () => {
    vi.stubGlobal('crypto', { getRandomValues: crypto.getRandomValues.bind(crypto) });
    const a = newUuid();
    expect(a).toMatch(V4);
    expect(newUuid()).not.toBe(a);
  });
});
