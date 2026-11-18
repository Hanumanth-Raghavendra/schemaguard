import type { Score } from '../api/client.ts';
import { sentenceCase } from '../lock.ts';
import { levelText } from '../levels.ts';
import { splitReason } from '../reasons.ts';

/** The overall risk: the score the findings add up to, its level, and the findings that weigh most. */
export function RiskSummary({ score, subject }: { score: Score; subject: string }) {
  return (
    <section aria-label="Overall risk" className="flex flex-col gap-4 sm:flex-row sm:items-start sm:gap-8">
      <p className={`shrink-0 leading-none ${levelText(score.level)}`}>
        <span className="text-5xl font-bold tabular-nums">{score.value}</span>
        <span className="ml-1 text-lg text-muted">/ 100</span>
        <span className="mt-1 block text-lg font-semibold">{sentenceCase(score.level)} risk</span>
      </p>
      {score.reasons.length > 0 ? (
        <ul className="list-disc space-y-1 pl-5 text-sm">
          {score.reasons.map((reason) => {
            const { points, rule, title } = splitReason(reason);
            return (
              <li key={reason}>
                {title}
                {rule !== null && (
                  <span className="text-muted">
                    {' '}
                    ({rule}, {points} points)
                  </span>
                )}
              </li>
            );
          })}
        </ul>
      ) : (
        <p className="text-sm text-muted">Nothing in {subject} takes a lock that blocks traffic or loses data.</p>
      )}
    </section>
  );
}
