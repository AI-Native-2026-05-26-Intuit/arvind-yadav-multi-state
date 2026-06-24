import {
  ApolloClient,
  InMemoryCache,
  HttpLink,
  from,
} from '@apollo/client';
import { setContext } from '@apollo/client/link/context';

// THREAT MODEL: storing the JWT in localStorage exposes it to any XSS
// that runs on the page. We accept that today because the W6 cookie
// story (HttpOnly, SameSite=Strict, server-set) isn't built yet —
// see §9 Sticking Points.
const httpLink = new HttpLink({ uri: 'http://localhost:8080/graphql' });

const authLink = setContext((_op, prevContext) => {
  const headers = (prevContext as { headers?: Record<string, string> }).headers ?? {};
  const token = localStorage.getItem('uc:jwt');
  return {
    headers: {
      ...headers,
      ...(token ? { authorization: `Bearer ${token}` } : {}),
    },
  };
});

export const apolloClient = new ApolloClient({
  link: from([authLink, httpLink]),
  cache: new InMemoryCache({
    typePolicies: {
      Tenant: { keyFields: ['id'] },
    },
  }),
});
