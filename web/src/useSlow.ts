import { useEffect, useState } from 'react';

/** True once `pending` has stayed true for `afterMs`. */
export function useSlow(pending: boolean, afterMs = 3000): boolean {
  const [elapsed, setElapsed] = useState(false);
  useEffect(() => {
    if (!pending) return;
    const timer = setTimeout(() => {
      setElapsed(true);
    }, afterMs);
    return () => {
      clearTimeout(timer);
      setElapsed(false);
    };
  }, [pending, afterMs]);
  return pending && elapsed;
}
