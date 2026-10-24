// REQ-OPS-053: generate src/api/generated from ../contracts/openapi.yaml (hey-api, fetch client + valibot validators).
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createClient } from '@hey-api/openapi-ts';
import { parse } from 'yaml';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const input = join(root, '..', 'contracts', 'openapi.yaml');

// Top-level nullable type arrays the generator expresses correctly (`string | null`). Any other top-level schema
// using a type array is not expressible with confidence: fail by name instead of emitting a loose type.
const SUPPORTED_NULLABLE_SCHEMAS = new Set(['NextCursor']);

const spec = parse(readFileSync(input, 'utf8'));
const unsupported = Object.entries(spec.components?.schemas ?? {})
  .filter(([name, s]) => Array.isArray(s?.type) && !SUPPORTED_NULLABLE_SCHEMAS.has(name))
  .map(([name]) => name);
if (unsupported.length > 0) {
  console.error(`generate: unsupported schema type (type array) in: ${unsupported.join(', ')}`);
  process.exit(1);
}

await createClient({
  input,
  output: { path: join(root, 'src', 'api', 'generated'), module: { extension: '' }, clean: true },
  plugins: [
    '@hey-api/typescript',
    {
      name: '@hey-api/client-fetch',
      throwOnError: true,
      baseUrl: false,
      runtimeConfigPath: join(root, 'src', 'api', 'clientConfig'),
    },
    'valibot',
    { name: '@hey-api/sdk', responseStyle: 'data', validator: { response: 'valibot' } },
  ],
});

// The vendored fetch runtime contains four loose `any` annotations. Tighten them deterministically so the output
// is any-free; every rewrite must match exactly once, so a generator upgrade that changes the runtime fails loudly.
const outDir = join(root, 'src', 'api', 'generated');
function rewrite(file, edits) {
  const path = join(outDir, file);
  let text = readFileSync(path, 'utf8');
  for (const [from, to] of edits) {
    if (!text.includes(from)) throw new Error(`generate: runtime patch no longer applies in ${file}: ${from}`);
    text = text.replace(from, to);
  }
  writeFileSync(path, text);
}
rewrite('client/client.gen.ts', [
  ['body?: any;', 'body?: BodyInit | null;'],
  ['body: getValidRequestBody(opts),', 'body: getValidRequestBody(opts) as BodyInit | null | undefined,'],
  ['let emptyData: any;', 'let emptyData: unknown;'],
  ['let data: any;', 'let data: unknown;'],
  [
    "const request: Client['request'] = async (options) => {",
    "const requestImpl = async (options: Parameters<Client['request']>[0]) => {",
  ],
  [
    '  const makeMethodFn =',
    "  const request = requestImpl as unknown as Client['request'];\n\n  const makeMethodFn =",
  ],
]);
// REQ-OPS-021 kept: a 200 body reporting DOWN is a failure for the version page (the contract enum allows DOWN
// but the backend answers 503 in that case), so getHealth() rejects instead of resolving a DOWN payload.
rewrite('sdk.gen.ts', [
  [
    'responseValidator: async (data) => await v.parseAsync(vGetHealthResponse, data),',
    `responseValidator: async (data) => {
        const health = await v.parseAsync(vGetHealthResponse, data);
        if (health.status !== 'UP') throw new Error('health: backend reports DOWN');
    },`,
  ],
]);
rewrite('core/serverSentEvents.gen.ts', [['yield data as any;', 'yield data as never;']]);
