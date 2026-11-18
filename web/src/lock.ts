/** PostgreSQL's table lock modes, weakest first (PostgreSQL 16 manual, "Table-Level Locks"). */
export const LOCK_LADDER = [
  'ACCESS SHARE',
  'ROW SHARE',
  'ROW EXCLUSIVE',
  'SHARE UPDATE EXCLUSIVE',
  'SHARE',
  'SHARE ROW EXCLUSIVE',
  'EXCLUSIVE',
  'ACCESS EXCLUSIVE',
] as const;

/** What ordinary traffic does while the lock is held. */
export type Traffic = 'flows' | 'writes-wait' | 'all-wait';

/** 1 for ACCESS SHARE up to 8 for ACCESS EXCLUSIVE; 0 for no lock or a name PostgreSQL does not use. */
export function lockLevel(lock: string | null): number {
  if (lock === null) return 0;
  return LOCK_LADDER.indexOf(lock as (typeof LOCK_LADDER)[number]) + 1;
}

/**
 * Up to SHARE UPDATE EXCLUSIVE, SELECT, INSERT, UPDATE and DELETE carry on. SHARE to EXCLUSIVE conflict with
 * ROW EXCLUSIVE, so writes wait; plain reads still run. ACCESS EXCLUSIVE conflicts with everything.
 */
export function traffic(level: number): Traffic {
  if (level >= 8) return 'all-wait';
  if (level >= 5) return 'writes-wait';
  return 'flows';
}

const WORK: Record<string, string> = {
  NONE: 'No table scan',
  SCAN: 'Scans the table',
  REWRITE: 'Rewrites the table',
};

export function describeWork(work: string): string {
  return WORK[work] ?? work;
}

export function sentenceCase(value: string): string {
  const lower = value.toLowerCase().replaceAll('_', ' ');
  return lower.charAt(0).toUpperCase() + lower.slice(1);
}
