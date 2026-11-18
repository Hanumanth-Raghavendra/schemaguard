import type { Finding } from '../api/client.ts';
import { describeWork } from '../lock.ts';
import { Level } from './Level.tsx';
import { LockLadder } from './LockLadder.tsx';

function tableName(finding: Finding): string | null {
  return finding.table === null ? null : `${finding.table.schema}.${finding.table.name}`;
}

interface Props {
  findings: Finding[];
  empty: string;
  /** Inside a statement of a checked migration, which already shows its SQL and its locks. */
  inStatement?: boolean;
}

/** Risks, highest score first, each with its lock and the safer way to do it. */
export function FindingList({ findings, empty, inStatement = false }: Props) {
  if (findings.length === 0) return <p className="text-sm text-muted">{empty}</p>;
  const ordered = findings.toSorted((a, b) => b.score - a.score);
  return (
    <ul className="divide-y divide-line">
      {ordered.map((finding, index) => (
        <li key={`${finding.ruleId}-${index}`} className="space-y-2 py-4 first:pt-0 last:pb-0">
          <div className="flex flex-wrap items-baseline gap-x-2 gap-y-1">
            <Level level={finding.severity} />
            <h3 className="font-semibold">{finding.title}</h3>
            <span className="text-sm text-muted">{finding.ruleId}</span>
          </div>
          <p className="text-sm">{finding.message}</p>
          {!inStatement && (finding.change !== null || tableName(finding) !== null) && (
            <p className="font-mono text-xs text-muted">{finding.change ?? tableName(finding)}</p>
          )}
          {!inStatement && finding.operation !== null && (
            <div className="flex flex-wrap items-center gap-x-4 gap-y-1">
              <LockLadder lock={finding.operation.lock} effect={finding.operation.lockEffect} />
              <span className="text-sm text-muted">{describeWork(finding.operation.work)}</span>
            </div>
          )}
          <details className="text-sm">
            <summary className="cursor-pointer text-elephant">Why it matters and what to do instead</summary>
            <p className="mt-2">{finding.why}</p>
            <p className="mt-2">{finding.fix}</p>
          </details>
        </li>
      ))}
    </ul>
  );
}
