import type { CreateClientConfig } from './generated/client.gen';

/**
 * Runtime config for the generated fetch client (REQ-OPS-053).
 * - Same-origin requests: the contract paths (`/health`, `/api/...`) resolve against the page origin.
 * - The generated client hands `fetch` a `Request`; we forward URL + init instead so the global `fetch`
 *   (resolved per call, so test and service-worker stubs apply) receives plain arguments.
 */
export const createClientConfig: CreateClientConfig = (config) => ({
  ...config,
  baseUrl: globalThis.location.origin,
  fetch: async (input, init) => {
    const request = input instanceof Request ? input : new Request(input, init);
    return globalThis.fetch(request.url, {
      method: request.method,
      headers: request.headers,
      body: request.body === null ? null : await request.arrayBuffer(),
      signal: request.signal,
      credentials: request.credentials,
    });
  },
});
