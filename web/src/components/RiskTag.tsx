import type { VersionRisk } from '../api/client.ts';
import { levelText } from '../levels.ts';
import { plural } from '../words.ts';
import { Level } from './Level.tsx';

/** A saved version's risk in one line: the level, the score and what changed. */
export function RiskTag({ risk }: { risk: VersionRisk | null }) {
  if (risk === null) return <span className="text-sm text-muted">First version</span>;
  return (
    <span className="inline-flex flex-wrap items-baseline gap-x-2 gap-y-1 text-sm">
      <Level level={risk.level} />
      <span className={`font-semibold tabular-nums ${levelText(risk.level)}`}>{risk.score}</span>
      <span className="text-muted">
        {plural(risk.changes, 'change')}, {plural(risk.findings, 'risk')}
      </span>
    </span>
  );
}
