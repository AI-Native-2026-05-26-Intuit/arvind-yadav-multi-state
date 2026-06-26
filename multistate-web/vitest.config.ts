import { defineConfig, mergeConfig } from 'vitest/config';
import viteConfig from './vite.config';

export default mergeConfig(
  viteConfig,
  defineConfig({
    test: {
      environment: 'jsdom',
      globals: true,
      setupFiles: ['./src/test/setupTests.ts'],
      include: ['src/**/*.test.{ts,tsx}'],
      coverage: {
        provider: 'v8',
        reporter: ['text', 'html', 'lcov'],
        include: ['src/**/*.{ts,tsx}'],
        exclude: [
          'src/**/*.test.{ts,tsx}',
          'src/test/**',
          'src/gql/generated/**',
          'src/mocks/browser.ts',
        ],
        thresholds: {
          // The W4 capstone gate. Branch coverage is the load-bearing metric
          // across the whole repo; the src/pages tree gets a tighter local
          // threshold because that's the surface the new tests target.
          branches: 70,
          'src/pages/**': {
            branches: 70,
            lines: 75,
            functions: 75,
            statements: 75,
          },
        },
      },
    },
  }),
);
