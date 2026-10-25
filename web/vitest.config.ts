import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    setupFiles: ['./vitest.setup.ts'],
    // Typed ESLint runs (src/lint.test.ts) take >5 s under coverage with parallel workers.
    testTimeout: 30_000,
    include: ['src/**/*.test.{ts,tsx}'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'json-summary'],
      include: ['src/**'],
      exclude: ['src/**/*.test.*', 'src/main.tsx', 'src/api/generated/**'],
    },
  },
});
