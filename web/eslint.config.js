import js from '@eslint/js';
import i18next from 'eslint-plugin-i18next';
import jsxA11y from 'eslint-plugin-jsx-a11y';
import reactHooks from 'eslint-plugin-react-hooks';
import globals from 'globals';
import tseslint from 'typescript-eslint';

// REQ-OPS-053: money arithmetic only in src/lib/money.ts; /api/* only through the generated client.
const ARITH = String.raw`/^(\+|-|\*|\/|%|\*\*)$/`;
const ARITH_ASSIGN = String.raw`/^(\+|-|\*|\/|%|\*\*)=$/`;
const API_URL = String.raw`/^\/api(\/|$)/`;
const contractSelectors = [
  {
    selector: `BinaryExpression[operator=${ARITH}] > MemberExpression[property.name='amountMinor']`,
    message: 'Arithmetic on Money.amountMinor is only allowed in src/lib/money.ts (money rule).',
  },
  {
    selector: `AssignmentExpression[operator=${ARITH_ASSIGN}] > MemberExpression[property.name='amountMinor']`,
    message: 'Arithmetic on Money.amountMinor is only allowed in src/lib/money.ts (money rule).',
  },
  {
    selector: `AssignmentExpression[operator=${ARITH_ASSIGN}][right.property.name='amountMinor']`,
    message: 'Arithmetic on Money.amountMinor is only allowed in src/lib/money.ts (money rule).',
  },
  {
    selector: "UpdateExpression > MemberExpression[property.name='amountMinor']",
    message: 'Arithmetic on Money.amountMinor is only allowed in src/lib/money.ts (money rule).',
  },
  {
    selector: `CallExpression[callee.name='fetch'][arguments.0.value=${API_URL}]`,
    message: 'Do not fetch /api/* directly; use the generated client in src/api/generated.',
  },
  {
    selector: `CallExpression[callee.name='fetch'][arguments.0.type='TemplateLiteral'][arguments.0.quasis.0.value.raw=${API_URL}]`,
    message: 'Do not fetch /api/* directly; use the generated client in src/api/generated.',
  },
];

// Global fetch is reserved for the client runtime config in src/api (it forwards to the stubbable global).
const globalFetchSelector = {
  selector: "MemberExpression[property.name='fetch'][object.name=/^(window|globalThis|self)$/]",
  message: 'Do not use the global fetch outside src/api; use the generated client in src/api/generated.',
};
const allSelectors = [...contractSelectors, globalFetchSelector];

export default tseslint.config(
  {
    ignores: [
      'dist',
      'coverage',
      'src/api/generated',
      'playwright-report',
      'test-results',
      'test/fixtures/**',
      'e2e/__screenshots__',
    ],
  },
  js.configs.recommended,
  ...tseslint.configs.strictTypeChecked,
  jsxA11y.flatConfigs.recommended,
  reactHooks.configs.flat.recommended,
  {
    files: ['**/*.{ts,tsx}'],
    languageOptions: {
      globals: { ...globals.browser },
      parserOptions: {
        projectService: { allowDefaultProject: ['test/fixtures/lint/*.tsx'] },
        tsconfigRootDir: import.meta.dirname,
      },
    },
  },
  {
    files: ['**/*.{ts,tsx}'],
    ignores: ['src/lib/money.ts'],
    rules: { 'no-restricted-syntax': ['error', ...allSelectors] },
  },
  {
    files: ['src/api/**/*.ts'],
    ignores: ['src/lib/money.ts'],
    rules: { 'no-restricted-syntax': ['error', ...contractSelectors] },
  },
  {
    files: ['**/*.tsx'],
    ignores: ['src/**/*.test.tsx'],
    ...i18next.configs['flat/recommended'],
    rules: {
      'i18next/no-literal-string': ['error', { mode: 'jsx-text-only' }],
      // User-facing attributes must come from i18n too (the plugin's jsx-text-only mode ignores attributes).
      'no-restricted-syntax': [
        'error',
        {
          selector:
            'JSXAttribute[name.name=/^(aria-label|aria-description|aria-placeholder|title|alt|placeholder)$/] > Literal',
          message: 'User-facing attribute strings must come from t().',
        },
        ...allSelectors,
      ],
    },
  },
  { files: ['**/*.{js,mjs}'], ...tseslint.configs.disableTypeChecked },
  { files: ['*.config.{js,ts}', 'scripts/*.mjs'], languageOptions: { globals: globals.node } },
);
