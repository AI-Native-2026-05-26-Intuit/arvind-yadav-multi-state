import type { ReactElement, FormEvent } from 'react';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';

export function LoginPage(): ReactElement {
  const navigate = useNavigate();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');

  const onSubmit = (e: FormEvent): void => {
    e.preventDefault();
    // THREAT MODEL: see src/apollo/client.ts — fake JWT in localStorage
    // is acceptable until W6 wires HttpOnly cookies. The synthetic
    // credentials below are never sent anywhere; the form only gates
    // the protected layout for the W4 D5 e2e flow.
    localStorage.setItem('uc:jwt', 'dev.fake.jwt');
    void navigate('/tenants');
  };

  return (
    <section>
      <h1>Login</h1>
      <form onSubmit={onSubmit}>
        <label>
          Email
          <input
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            required
            autoComplete="username"
          />
        </label>
        <label>
          Password
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
            autoComplete="current-password"
          />
        </label>
        <button type="submit">Sign in</button>
      </form>
    </section>
  );
}
