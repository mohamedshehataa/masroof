import type { CreateClientConfig } from './generated/client.gen';
import { clearSession, getAccessToken, refreshOnce } from './session';

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
    const body = request.body === null ? null : await request.arrayBuffer();
    const send = (headers: Headers) =>
      globalThis.fetch(request.url, {
        method: request.method,
        headers,
        body,
        signal: request.signal,
        credentials: request.credentials,
      });
    const response = await retryOnce401(request, await send(request.headers), send);
    return rejectDownHealth(response, request.url);
  },
});

/**
 * REQ-UI-011: on 401 refresh once (shared by parallel 401s) and retry the request once with the new token; a
 * second 401, or a refresh the backend rejects, ends the session so the app shows the login page.
 */
export async function retryOnce401(
  request: Request,
  response: Response,
  send: (headers: Headers) => Promise<Response>,
): Promise<Response> {
  const used = request.headers.get('Authorization');
  if (response.status !== 401 || used === null || new URL(request.url).pathname.startsWith('/auth/')) return response;
  if (used === `Bearer ${getAccessToken() ?? ''}` && (await refreshOnce()) !== 'ok') return response;
  const next = getAccessToken();
  if (next === undefined) return response;
  const headers = new Headers(request.headers);
  headers.set('Authorization', `Bearer ${next}`);
  const retried = await send(headers);
  if (retried.status === 401) clearSession();
  return retried;
}
