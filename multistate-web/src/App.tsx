import { useEffect, useState } from 'react';
import type { ReactElement } from 'react';
import { TenantDetailPage } from './pages/TenantDetailPage';

const TENANT_HASH = '#/tenants/stub-id-1';

export default function App(): ReactElement {
  const [hash, setHash] = useState<string>(window.location.hash);

  useEffect(() => {
    const onChange = () => setHash(window.location.hash);
    window.addEventListener('hashchange', onChange);
    return () => window.removeEventListener('hashchange', onChange);
  }, []);

  if (hash === TENANT_HASH) return <TenantDetailPage />;

  return (
    <p>
      Go to <a href={TENANT_HASH}>{TENANT_HASH}</a>
    </p>
  );
}
