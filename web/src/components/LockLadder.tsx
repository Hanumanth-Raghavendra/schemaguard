import { LOCK_LADDER, lockLevel, traffic, type Traffic } from '../lock.ts';

const FILL: Record<Traffic, string> = {
  flows: 'bg-calm',
  'writes-wait': 'bg-medium',
  'all-wait': 'bg-critical',
};

interface Props {
  lock: string | null;
  effect: string | null;
}

/** The lock a statement takes, shown as a step on PostgreSQL's ladder of eight table lock modes. */
export function LockLadder({ lock, effect }: Props) {
  const level = lockLevel(lock);
  if (lock === null || level === 0) {
    return <p className="text-sm text-muted">{lock ?? 'No table lock'}</p>;
  }
  const fill = FILL[traffic(level)];
  return (
    <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
      <div
        className="flex items-end gap-0.5"
        role="img"
        aria-label={`${lock} lock, ${level} of ${LOCK_LADDER.length} on PostgreSQL's lock scale`}
      >
        {LOCK_LADDER.map((name, index) => (
          <span
            key={name}
            title={name}
            className={`w-1.5 rounded-sm ${index < level ? fill : 'bg-line'}`}
            style={{ height: `${6 + index * 2}px` }}
          />
        ))}
      </div>
      <p className="text-sm">
        <span className="font-medium">{lock}</span>
        {effect !== null && <span className="text-muted">: {effect}</span>}
      </p>
    </div>
  );
}
