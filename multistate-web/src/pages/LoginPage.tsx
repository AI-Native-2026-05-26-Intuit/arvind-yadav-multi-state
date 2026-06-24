import type { ReactElement } from 'react';
import { useNavigate } from 'react-router-dom';

export function LoginPage(): ReactElement {
  const navigate = useNavigate();

  const onLogin = (): void => {
    // THREAT MODEL: see src/apollo/client.ts — fake JWT in localStorage
    // is acceptable until W6 wires HttpOnly cookies.
    localStorage.setItem('uc:jwt', 'dev.fake.jwt');
    navigate('/tenants');
  };

  return (
    <section>
      <h1>Login</h1>
      <button type="button" onClick={onLogin}>
        Sign in
      </button>
    </section>
  );
}
