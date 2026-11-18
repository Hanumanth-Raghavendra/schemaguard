import { LEVEL_TONE } from '../levels.ts';
import { sentenceCase } from '../lock.ts';

/** A risk level (NONE to CRITICAL) as a small outlined tag in the level's colour. */
export function Level({ level }: { level: string }) {
  return (
    <span
      className={`inline-block rounded border px-1.5 text-xs font-semibold ${LEVEL_TONE[level] ?? 'text-muted border-line'}`}
    >
      {sentenceCase(level)}
    </span>
  );
}
