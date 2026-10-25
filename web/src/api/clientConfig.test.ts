import { describe, expect, it } from 'vitest';
import { rejectDownHealth } from './clientConfig';

function res(body: string, status = 200): Response {
  return new Response(body, { status, headers: { 'content-type': 'application/json' } });
}
const HEALTH = 'http://localhost/health';

describe('REQ-OPS-053 clientConfig health guard (REQ-OPS-021 DOWN rule)', () => {
  it('REQ-OPS-053 rejects a 200 /health body reporting DOWN', async () => {
    await expect(rejectDownHealth(res('{"status":"DOWN"}'), HEALTH)).rejects.toThrow('DOWN');
  });

  it('REQ-OPS-053 passes an UP /health body through unread', async () => {
    const r = res('{"status":"UP"}');
    expect(await rejectDownHealth(r, HEALTH)).toBe(r);
    expect(await r.json()).toEqual({ status: 'UP' });
  });

  it('REQ-OPS-053 ignores other paths, non-ok responses and non-JSON bodies', async () => {
    const other = res('{"status":"DOWN"}');
    expect(await rejectDownHealth(other, 'http://localhost/api/x')).toBe(other);
    const bad = res('{"status":"DOWN"}', 503);
    expect(await rejectDownHealth(bad, HEALTH)).toBe(bad);
    const html = res('<html>');
    expect(await rejectDownHealth(html, HEALTH)).toBe(html);
  });
});
