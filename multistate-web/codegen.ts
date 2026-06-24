import type { CodegenConfig } from '@graphql-codegen/cli';

const config: CodegenConfig = {
  schema: 'http://localhost:8080/graphql',
  documents: ['./src/queries/**/*.graphql'],
  generates: {
    './src/gql/generated/': {
      preset: 'client',
    },
  },
};

export default config;
