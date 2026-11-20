/**
 * Delete tokens of the analyses this browser shared, so the sharer can delete them later. Storage can be
 * missing or full (private windows, blocked site data); then the link still works and only Delete is lost.
 */
const KEY = 'schemaguard.deleteTokens';

interface Entry {
  token: string;
  expiresAt: string;
}

function isEntry(value: unknown): value is Entry {
  if (typeof value !== 'object' || value === null) return false;
  const entry = value as Record<string, unknown>;
  return typeof entry.token === 'string' && typeof entry.expiresAt === 'string';
}

function live(entries: Record<string, unknown>, now: number): Record<string, Entry> {
  const kept: Record<string, Entry> = {};
  for (const [id, entry] of Object.entries(entries)) {
    if (isEntry(entry) && Date.parse(entry.expiresAt) > now) kept[id] = entry;
  }
  return kept;
}

function read(): Record<string, Entry> {
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(KEY) ?? '{}');
    return typeof parsed === 'object' && parsed !== null ? live(parsed as Record<string, unknown>, Date.now()) : {};
  } catch {
    return {};
  }
}

function write(entries: Record<string, Entry>): void {
  try {
    localStorage.setItem(KEY, JSON.stringify(entries));
  } catch {
    // Nothing to do: see the comment at the top.
  }
}

export function rememberToken(id: string, token: string, expiresAt: string): void {
  write({ ...read(), [id]: { token, expiresAt } });
}

export function tokenFor(id: string): string | null {
  return read()[id]?.token ?? null;
}

export function forgetToken(id: string): void {
  const entries = read();
  // eslint-disable-next-line @typescript-eslint/no-dynamic-delete -- the ids are the keys
  delete entries[id];
  write(entries);
}
