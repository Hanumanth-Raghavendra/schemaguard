import type { LintResponse } from '../api/client.ts';
import { describeWork } from '../lock.ts';
import { owner } from '../positions.ts';
import { FindingList } from './FindingList.tsx';
import { LockLadder } from './LockLadder.tsx';

const STATUS: Record<string, { text: string; tone: string }> = {
  NOT_SCHEMA: { text: 'Not a schema change', tone: 'text-muted' },
  NOT_ANALYSED: { text: 'Not analysed', tone: 'text-medium' },
  WOULD_FAIL: { text: 'Would fail', tone: 'text-critical' },
};

/** Each statement of the migration, in order, with the locks it takes and the risks found in it. */
export function LintView({ lint }: { lint: LintResponse }) {
  const problems = lint.diagnostics.filter((diagnostic) => diagnostic.input === 'migration');
  return (
    <ol className="space-y-5">
      {lint.statements.map((statement, index) => {
        const status = STATUS[statement.status] ?? { text: statement.status, tone: 'text-muted' };
        const failures = problems.filter((diagnostic) => owner(lint.statements, diagnostic) === index);
        return (
          <li key={index} className="grid grid-cols-[3.5rem_1fr] gap-x-3">
            <span className="pt-2 text-right text-sm tabular-nums text-muted">line {statement.line}</span>
            <div className="min-w-0 space-y-2">
              <pre className="overflow-x-auto whitespace-pre-wrap break-words rounded-md bg-elephant-soft px-3 py-2 font-mono text-sm">{statement.sql}</pre>
              {statement.status !== 'ANALYSED' && <p className={`text-sm font-medium ${status.tone}`}>{status.text}</p>}
              {failures.map((failure, n) => (
                <p key={n} className="text-sm text-critical">
                  {failure.message}
                </p>
              ))}
              {statement.operations.map((operation, n) => (
                <div key={n} className="flex flex-wrap items-center gap-x-4 gap-y-1">
                  <LockLadder lock={operation.lock} effect={operation.lockEffect} />
                  <span className="text-sm text-muted">{describeWork(operation.work)}</span>
                </div>
              ))}
              {statement.findings.length > 0 && <FindingList findings={statement.findings} empty="" inStatement />}
            </div>
          </li>
        );
      })}
    </ol>
  );
}
