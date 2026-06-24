import { useEffect, useState } from 'react';
import type { ReactElement } from 'react';
import { TenantDetailPage } from './pages/TenantDetailPage';
import { ErrorBoundary } from './components/ErrorBoundary';

const TENANT_HASH = '#/tenants/stub-id-1';

export default function App(): ReactElement {
  const [hash, setHash] = useState<string>(window.location.hash);

  useEffect(() => {
    const onChange = (): void => setHash(window.location.hash);
    window.addEventListener('hashchange', onChange);
    return () => window.removeEventListener('hashchange', onChange);
  }, []);

  if (hash === TENANT_HASH) {
    return (
      <ErrorBoundary
        fallback={(err, reset) => (
          <div role="alert" className="error-card">
            <h2>Something went wrong</h2>
            <pre>{err.message}</pre>
            <button type="button" onClick={reset}>Try again</button>
          </div>
        )}
      >
        <TenantDetailPage />
      </ErrorBoundary>
    );
  }

  return (
    <p>
      Go to <a href={TENANT_HASH}>{TENANT_HASH}</a>
    </p>
  );
}
