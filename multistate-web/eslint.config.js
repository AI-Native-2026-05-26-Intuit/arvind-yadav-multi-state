// eslint.config.js — ESLint 9 flat config.
//
// W4 D5 Task 4 hardens the lint surface: react-hooks + jsx-a11y recommended
// rule sets, plus a banned-syntax rule that rejects `as any` (the casted
// escape hatch) at parse time rather than relying on no-explicit-any to
// catch every variant.
import js from '@eslint/js';
import tseslint from 'typescript-eslint';
import reactHooks from 'eslint-plugin-react-hooks';
import jsxA11y from 'eslint-plugin-jsx-a11y';

export default tseslint.config(
  {
    ignores: [
      'dist',
      'node_modules',
      'coverage',
      'playwright-report',
      'test-results',
      'src/gql/generated/**',
      'vitest.config.ts',
      'codegen.ts',
      'playwright.config.ts',
      'e2e/**',
    ],
  },
  js.configs.recommended,
  ...tseslint.configs.recommendedTypeChecked.map((cfg) => ({
    ...cfg,
    files: ['src/**/*.{ts,tsx}'],
  })),
  {
    files: ['src/**/*.{ts,tsx}'],
    languageOptions: {
      parserOptions: {
        project: ['./tsconfig.json'],
        tsconfigRootDir: import.meta.dirname,
      },
    },
    plugins: {
      'react-hooks': reactHooks,
      'jsx-a11y':    jsxA11y,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      ...jsxA11y.configs.recommended.rules,
      '@typescript-eslint/no-explicit-any':         'error',
      '@typescript-eslint/no-floating-promises':    'error',
      '@typescript-eslint/consistent-type-imports': 'error',
      'no-restricted-syntax': [
        'error',
        {
          // Old-style TS type assertion: `<any>foo`.
          selector: "TSTypeAssertion[typeAnnotation.typeName.name='any']",
          message:  'as any is banned; widen the type properly.',
        },
        {
          // Modern TS type assertion: `foo as any`.
          selector: "TSAsExpression[typeAnnotation.typeName.name='any']",
          message:  'as any is banned; widen the type properly.',
        },
      ],
    },
  },
);
