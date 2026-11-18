import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach, vi } from 'vitest';

// Monaco needs a real browser; components under test get the plain editor it falls back to.
vi.mock('../components/MonacoSqlEditor.tsx', async () => {
  const plain = await import('../components/PlainSqlEditor.tsx');
  return { default: plain.PlainSqlEditor };
});

afterEach(() => {
  cleanup();
});
