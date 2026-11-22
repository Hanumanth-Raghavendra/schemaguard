/** Where to go after logging in: the page that asked for it, if the router state names one of ours. */
export function returnTo(state: unknown, fallback = '/account'): string {
  if (state !== null && typeof state === 'object' && 'from' in state) {
    const from = state.from;
    if (typeof from === 'string' && from.startsWith('/') && !from.startsWith('//')) return from;
  }
  return fallback;
}

/** A message for a failed account request, as the API words it; the API's details are written for people. */
export function problemMessage(error: unknown): string {
  if (error instanceof Error && error.message !== '') return error.message;
  return 'Something went wrong. Try again.';
}
