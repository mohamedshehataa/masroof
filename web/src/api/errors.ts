import { client } from './generated/client.gen';

/** What the generated client throws, normalized: the HTTP status (0 = no response) and `Retry-After` seconds. */
export interface ApiError {
  status: number;
  retryAfter: number | null;
}

const RETRY_AFTER = 'Retry-After';

// The generated client throws only the parsed body; this interceptor adds the status and Retry-After header.
client.interceptors.error.use((error, response) => {
  const header = response?.headers.get(RETRY_AFTER) ?? null;
  const seconds = header === null ? Number.NaN : Number(header);
  const base = typeof error === 'object' && error !== null ? error : {};
  return { ...base, status: response?.status ?? 0, retryAfter: Number.isFinite(seconds) ? seconds : null };
});

export function apiErrorOf(e: unknown): ApiError {
  if (typeof e === 'object' && e !== null && 'status' in e && typeof e.status === 'number') {
    const retryAfter = 'retryAfter' in e && typeof e.retryAfter === 'number' ? e.retryAfter : null;
    return { status: e.status, retryAfter };
  }
  return { status: 0, retryAfter: null };
}
