import { render, screen } from '@testing-library/react';
import { lazy, Suspense } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { LoadBoundary } from './LoadBoundary.tsx';

describe('LoadBoundary', () => {
  it('shows the fallback when a lazy part fails to download, and keeps the rest of the page', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const Broken = lazy(() => Promise.reject(new Error('Failed to fetch dynamically imported module')));
    render(
      <main>
        <p>The rest of the page</p>
        <LoadBoundary fallback={<p>Plain editor</p>}>
          <Suspense fallback={<p>Loading</p>}>
            <Broken />
          </Suspense>
        </LoadBoundary>
      </main>,
    );
    expect(await screen.findByText('Plain editor')).toBeInTheDocument();
    expect(screen.getByText('The rest of the page')).toBeInTheDocument();
  });
});
