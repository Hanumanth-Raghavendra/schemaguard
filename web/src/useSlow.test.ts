import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useSlow } from './useSlow.ts';

describe('useSlow', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('turns true only after the request has been pending for a while, and resets', () => {
    const { result, rerender } = renderHook(({ pending }) => useSlow(pending, 3000), {
      initialProps: { pending: true },
    });
    expect(result.current).toBe(false);
    act(() => {
      vi.advanceTimersByTime(2999);
    });
    expect(result.current).toBe(false);
    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(result.current).toBe(true);

    rerender({ pending: false });
    expect(result.current).toBe(false);
    rerender({ pending: true });
    expect(result.current).toBe(false);
  });
});
