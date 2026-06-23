import '@testing-library/jest-dom';
import { afterEach } from 'vitest';
import { useTenantFilterStore } from '../stores/useTenantFilterStore';

// Reset the Zustand filter store between tests so threshold/search/state
// chips set in one test cannot bleed into the next. The persist middleware
// also writes to localStorage on each set(), so clear that too.
afterEach(() => {
  useTenantFilterStore.getState().reset();
  if (typeof localStorage !== 'undefined') {
    localStorage.clear();
  }
});
