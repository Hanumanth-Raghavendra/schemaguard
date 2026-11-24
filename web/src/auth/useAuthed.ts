import { useCallback, useEffect, useState } from 'react';
import { useAuth } from './AuthContext.ts';

/** Loads with the access token; give a stable function (useCallback), a new one loads again. */
export type Loader<T> = (accessToken: string, signal: AbortSignal) => Promise<T>;

export interface Loaded<T> {
  data: T | null;
  error: unknown;
  /** Loads again, keeping what is shown until the new answer arrives. */
  reload: () => void;
  /** Shows an answer the page already has, such as the result of a rename. */
  replace: (data: T) => void;
}

interface State<T> {
  load: Loader<T>;
  data: T | null;
  error: unknown;
}

/** Data that needs the logged-in account, loaded when the session is ready and whenever the loader changes. */
export function useAuthed<T>(load: Loader<T>): Loaded<T> {
  const { status, withToken } = useAuth();
  const [round, setRound] = useState(0);
  const [state, setState] = useState<State<T>>({ load, data: null, error: null });
  // Another loader (another project id) starts empty instead of showing the previous one's data.
  if (state.load !== load) setState({ load, data: null, error: null });

  useEffect(() => {
    if (status !== 'signed-in') return;
    const controller = new AbortController();
    withToken((token) => load(token, controller.signal)).then(
      (data) => {
        setState({ load, data, error: null });
      },
      (error: unknown) => {
        if (!controller.signal.aborted) setState({ load, data: null, error });
      },
    );
    return () => {
      controller.abort();
    };
  }, [load, round, status, withToken]);

  const reload = useCallback(() => {
    setRound((value) => value + 1);
  }, []);
  const replace = useCallback(
    (data: T) => {
      setState({ load, data, error: null });
    },
    [load],
  );
  const shown = state.load === load ? state : { data: null, error: null };
  return { data: shown.data, error: shown.error, reload, replace };
}
