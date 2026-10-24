export interface Health {
  status: 'UP';
  version: string;
  gitSha: string;
  buildTime: string;
  time: string;
  checks?: Record<string, 'UP' | 'DOWN'>;
}

const GIT_SHA = /^[0-9a-f]{7,40}$/;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** Runtime validation of the Health shape; STORY-007 replaces this with the generated client. */
function parseHealth(body: unknown): Health {
  if (!isRecord(body)) throw new Error('health: body is not an object');
  const { status, version, gitSha, buildTime, time, checks } = body;
  if (status !== 'UP') throw new Error('health: backend reports DOWN');
  if (typeof version !== 'string' || version === '') throw new Error('health: invalid version');
  if (typeof gitSha !== 'string' || !GIT_SHA.test(gitSha)) throw new Error('health: invalid gitSha');
  if (typeof buildTime !== 'string' || typeof time !== 'string') throw new Error('health: invalid timestamps');
  if (checks === undefined) return { status, version, gitSha, buildTime, time };
  if (!isRecord(checks) || !Object.values(checks).every((v) => v === 'UP' || v === 'DOWN')) {
    throw new Error('health: invalid checks');
  }
  return { status, version, gitSha, buildTime, time, checks: checks as Record<string, 'UP' | 'DOWN'> }; // narrowed by the every() above
}

export async function getHealth(): Promise<Health> {
  const res = await fetch('/health', { headers: { accept: 'application/json' } });
  if (!res.ok) throw new Error(`health: HTTP ${String(res.status)}`);
  const body: unknown = await res.json();
  return parseHealth(body);
}
