import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import * as api from '../api/client.ts';
import { ApiError, type Account, type Session } from '../api/client.ts';
import { AuthContext, type Auth, type AuthStatus } from './AuthContext.ts';
import { hasSessionHint, renewOnce, SESSION_HINT, setSessionHint } from './session.ts';

interface AccessToken {
  value: string;
  expiresAt: number;
}

// Renew a little before the server would refuse the token.
const EARLY_MS = 30_000;

/** Holds the session for the whole app: the account, and the access token in memory only. */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<AuthStatus>(() => (hasSessionHint() ? 'checking' : 'anonymous'));
  const [account, setAccount] = useState<Account | null>(null);
  const token = useRef<AccessToken | null>(null);

  const apply = useCallback((session: Session) => {
    token.current = { value: session.accessToken, expiresAt: Date.now() + session.expiresIn * 1000 };
    setAccount(session.account);
    setStatus('signed-in');
    setSessionHint(true);
  }, []);

  const forget = useCallback((clearHint: boolean) => {
    token.current = null;
    setAccount(null);
    setStatus('anonymous');
    if (clearHint) setSessionHint(false);
  }, []);

  /** Renews the session; a 401 means it is over, anything else (offline, server down) leaves the hint for next time. */
  const renew = useCallback(async (): Promise<AccessToken> => {
    try {
      const session = await renewOnce();
      apply(session);
      return { value: session.accessToken, expiresAt: Date.now() + session.expiresIn * 1000 };
    } catch (error) {
      forget(error instanceof ApiError && error.status === 401);
      throw error;
    }
  }, [apply, forget]);

  // A browser that logged in before renews its session when the app starts.
  useEffect(() => {
    if (!hasSessionHint()) return;
    renewOnce().then(apply, (error: unknown) => {
      forget(error instanceof ApiError && error.status === 401);
    });
  }, [apply, forget]);

  // Another tab logged in or out.
  useEffect(() => {
    const onStorage = (event: StorageEvent) => {
      if (event.key !== SESSION_HINT && event.key !== null) return;
      if (hasSessionHint()) {
        if (token.current === null) renew().catch(() => undefined);
      } else {
        forget(false);
      }
    };
    window.addEventListener('storage', onStorage);
    return () => {
      window.removeEventListener('storage', onStorage);
    };
  }, [renew, forget]);

  const withToken = useCallback(
    async <T,>(call: (accessToken: string) => Promise<T>): Promise<T> => {
      let current = token.current;
      if (current === null || current.expiresAt - EARLY_MS < Date.now()) current = await renew();
      try {
        return await call(current.value);
      } catch (error) {
        if (!(error instanceof ApiError && error.status === 401)) throw error;
      }
      const fresh = await renew();
      try {
        return await call(fresh.value);
      } catch (error) {
        // Renewed and still refused: the account is gone.
        if (error instanceof ApiError && error.status === 401) forget(true);
        throw error;
      }
    },
    [renew, forget],
  );

  const auth = useMemo<Auth>(
    () => ({
      status,
      account,
      signUp: async (email, password) => {
        apply(await api.signUp(email, password));
      },
      logIn: async (email, password) => {
        apply(await api.logIn(email, password));
      },
      logOut: async () => {
        try {
          await api.logOut();
        } catch {
          // Logged out here all the same; the server's session expires on its own.
        }
        forget(true);
      },
      deleteAccount: async (password) => {
        await withToken((accessToken) => api.deleteAccount(accessToken, password));
        forget(true);
      },
      withToken,
    }),
    [status, account, apply, forget, withToken],
  );

  return <AuthContext value={auth}>{children}</AuthContext>;
}
