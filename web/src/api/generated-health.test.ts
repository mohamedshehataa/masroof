import { afterEach, describe, expect, it, vi } from 'vitest';

// Resolved at runtime so a missing generated client fails the assertions instead of test collection.
const GENERATED_CLIENT = './generated/index';
type HealthResult = { version: string; gitSha: string };
async function loadGetHealth(): Promise<() => Promise<HealthResult>> {
  const mod = (await import(/* @vite-ignore */ GENERATED_CLIENT)) as { getHealth?: () => Promise<HealthResult> };
  expect(typeof mod.getHealth, 'generated client must export getHealth').toBe('function');
  return mod.getHealth as () => Promise<HealthResult>;
}

// Mirrors STORY-141's REQ-OPS-021 cases against the generated client's getHealth (REQ-OPS-053 second criterion).
const good = {
  status: 'UP',
  version: '0.1.0',
  gitSha: '1a2b3c4d5e6f',
  buildTime: '2026-10-24T10:00:00Z',
  time: '2026-10-24T12:00:00Z',
  checks: { db: 'UP' },
};

function respond(status: number, body: unknown, contentType = 'application/json') {
  vi.stubGlobal(
    'fetch',
    vi.fn(() =>
      Promise.resolve(
        new Response(typeof body === 'string' ? body : JSON.stringify(body), {
          status,
          headers: { 'content-type': contentType },
        }),
      ),
    ),
  );
}

afterEach(() => vi.unstubAllGlobals());

describe('REQ-OPS-053 generated getHealth keeps the REQ-OPS-021 behaviour', () => {
  it('REQ-OPS-053 resolves a valid Health body from GET /health', async () => {
    respond(200, good);
    const getHealth = await loadGetHealth();
    const h = await getHealth();
    expect(h.version).toBe('0.1.0');
    expect(h.gitSha).toBe('1a2b3c4d5e6f');
    const fetchMock = vi.mocked(fetch);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const first = fetchMock.mock.calls[0]?.[0];
    const url = first instanceof Request ? first.url : String(first);
    expect(url).toContain('/health');
  });

  const rejects: [string, number, unknown, string?][] = [
    ['503 problem+json', 503, { type: 'about:blank', title: 'Down', status: 503 }, 'application/problem+json'],
    ['502 html', 502, '<html>Bad gateway</html>', 'text/html'],
    ['200 status DOWN', 200, { ...good, status: 'DOWN' }],
    ['200 missing version', 200, { ...good, version: undefined }],
    ['200 gitSha XYZ (schema-invalid)', 200, { ...good, gitSha: 'XYZ' }],
    ['200 gitSha too short (schema-invalid)', 200, { ...good, gitSha: 'abc12' }],
    ['200 non-object body', 200, '[]'],
  ];
  it.each(rejects)('REQ-OPS-053 rejects %s', async (_name, status, body, ct) => {
    respond(status, body, ct);
    const getHealth = await loadGetHealth();
    await expect(getHealth()).rejects.toThrow();
  });

  it('REQ-OPS-053 rejects on network failure', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.reject(new TypeError('Failed to fetch'))),
    );
    const getHealth = await loadGetHealth();
    await expect(getHealth()).rejects.toThrow('Failed to fetch');
  });
});
