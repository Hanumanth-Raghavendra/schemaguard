import { refreshSession, type Session } from '../api/client.ts';

/**
 * A note in localStorage that this browser has logged in. It holds no secret (the session is the
 * httpOnly cookie); it only spares visitors who never logged in a refresh request on every page,
 * and lets other tabs hear about a login or logout through the storage event.
 */
export const SESSION_HINT = 'schemaguard.session';

export function hasSessionHint(): boolean {
  try {
    return localStorage.getItem(SESSION_HINT) === '1';
  } catch {
    return false;
  }
}

export function setSessionHint(present: boolean): void {
  try {
    if (present) localStorage.setItem(SESSION_HINT, '1');
    else localStorage.removeItem(SESSION_HINT);
  } catch {
    // Storage blocked (private mode, site data off): the session still works for this page.
  }
}

let inflight: Promise<Session> | null = null;

/**
 * Renews the session, at most one request at a time in this tab: a refresh token works once, so
 * two parallel refreshes (React's development double effects, two calls finding the access token
 * expired) would make the server see a token come back.
 */
export function renewOnce(): Promise<Session> {
  inflight ??= refreshSession().finally(() => {
    inflight = null;
  });
  return inflight;
}
