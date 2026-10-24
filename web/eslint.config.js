import js from '@eslint/js';
import i18next from 'eslint-plugin-i18next';
import jsxA11y from 'eslint-plugin-jsx-a11y';
import reactHooks from 'eslint-plugin-react-hooks';
import globals from 'globals';
import tseslint from 'typescript-eslint';

export default tseslint.config(
  { ignores: ['dist', 'coverage', 'playwright-report', 'test-results', 'test/fixtures/**', 'e2e/__screenshots__'] },
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
      ],
    },
  },
  { files: ['**/*.js'], ...tseslint.configs.disableTypeChecked },
  { files: ['*.config.{js,ts}'], languageOptions: { globals: globals.node } },
);
