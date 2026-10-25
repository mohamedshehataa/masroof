import type { CreateClientConfig } from './generated/client.gen';
import { getAccessToken } from './session';

const HEALTH_PATH = '/health';

/**
 * REQ-OPS-021: a 200 `/health` body that reports DOWN is a failure for the version page (the contract enum allows
 * DOWN, but the backend answers 503 in that case). Rejecting here keeps the rule in hand-written, tested code.
 */
export async function rejectDownHealth(response: Response, requestUrl: string): Promise<Response> {
  if (!response.ok || new URL(requestUrl).pathname !== HEALTH_PATH) return response;
  const body: unknown = await response
    .clone()
    .json()
    .catch(() => null);
  if (typeof body === 'object' && body !== null && 'status' in body && body.status === 'DOWN') {
    throw new Error('health: backend reports DOWN');
  }
  return response;
}

/**
 * Runtime config for the generated fetch client (REQ-OPS-053).
 * - Same-origin requests: the contract paths (`/health`, `/api/...`) resolve against the page origin.
 * - The generated client hands `fetch` a `Request`; we forward URL + init instead so the global `fetch`
 *   (resolved per call, so test and service-worker stubs apply) receives plain arguments.
 */
export const createClientConfig: CreateClientConfig = (config) => ({
  ...config,
  baseUrl: globalThis.location.origin,
  auth: () => getAccessToken(),
  fetch: async (input, init) => {
    const request = input instanceof Request ? input : new Request(input, init);
    const response = await globalThis.fetch(request.url, {
      method: request.method,
      headers: request.headers,
      body: request.body === null ? null : await request.arrayBuffer(),
      signal: request.signal,
      credentials: request.credentials,
    });
    return rejectDownHealth(response, request.url);
  },
});
