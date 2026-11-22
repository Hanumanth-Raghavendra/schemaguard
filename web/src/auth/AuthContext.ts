import { createContext, useContext } from 'react';
import type { Account } from '../api/client.ts';

/** Checking: a session may exist and is being renewed. Then signed in, or anonymous. */
export type AuthStatus = 'checking' | 'anonymous' | 'signed-in';

export interface Auth {
  status: AuthStatus;
  account: Account | null;
  signUp: (email: string, password: string) => Promise<void>;
  logIn: (email: string, password: string) => Promise<void>;
  /** Ends the session on the server (if it can be reached) and here in any case. */
  logOut: () => Promise<void>;
  deleteAccount: (password: string) => Promise<void>;
  /**
   * Runs a call that needs the access token. A missing or nearly expired token is renewed first; a
   * call answered 401 renews the session once and tries again; a session that cannot be renewed
   * logs out here and the error is passed on.
   */
  withToken: <T>(call: (accessToken: string) => Promise<T>) => Promise<T>;
}

export const AuthContext = createContext<Auth | null>(null);

export function useAuth(): Auth {
  const auth = useContext(AuthContext);
  if (auth === null) throw new Error('useAuth must be used inside <AuthProvider>');
  return auth;
}
