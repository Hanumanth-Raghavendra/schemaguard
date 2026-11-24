import { Link } from 'react-router';
import type { Project, VersionRisk, VersionSummary } from '../api/client.ts';
import { sentenceCase } from '../lock.ts';

/** More bars than this get too thin to read or click; the chart shows the latest ones. */
const SHOWN = 60;
/** Above this many bars, the score over each bar would crowd its neighbours. */
const SCORES_UP_TO = 24;
/** Where each level starts (the engine's RiskLevel.ofScore). */
const LEVEL_LINES = [
  { at: 25, level: 'MEDIUM' },
  { at: 50, level: 'HIGH' },
  { at: 75, level: 'CRITICAL' },
];

type Scored = VersionSummary & { risk: VersionRisk };

function colour(level: string): string {
  return `var(--color-${level.toLowerCase()})`;
}

function describe(version: VersionSummary): string {
  const name = `Version ${version.number}${version.label === null ? '' : `, ${version.label}`}`;
  if (version.risk === null) return `${name}: the first version, nothing to compare`;
  return `${name}: risk ${version.risk.score} of 100, ${version.risk.level.toLowerCase()}`;
}

function summary(scored: Scored[]): string {
  const [latest] = scored;
  if (latest === undefined) return '';
  const riskiest = scored.reduce((top, version) => (version.risk.score > top.risk.score ? version : top));
  const said = `The latest change, version ${latest.number}, scored ${latest.risk.score} (${latest.risk.level.toLowerCase()})`;
  if (scored.length === 1) return `${said}.`;
  if (riskiest === latest) return `${said}, the riskiest so far.`;
  return `${said}. The riskiest was version ${riskiest.number}, at ${riskiest.risk.score} (${riskiest.risk.level.toLowerCase()}).`;
}

/**
 * The risk of each change in a project: one bar per version, its height the score out of 100 and
 * its colour the level, with the lines where each level starts. Each bar opens its version.
 */
export function RiskChart({ project }: { project: Project }) {
  // The project lists versions newest first.
  const scored = project.versions.filter((version): version is Scored => version.risk !== null);
  if (scored.length === 0) return null;
  const oldestFirst = project.versions.toReversed();
  const shown = oldestFirst.slice(-SHOWN);
  const withScores = shown.length <= SCORES_UP_TO;
  const labelEvery = Math.ceil(shown.length / 12);

  return (
    <section aria-labelledby="trend-title" className="space-y-3">
      <h3 id="trend-title" className="text-lg font-semibold">
        Risk of each change
      </h3>
      <p className="text-sm text-muted">{summary(scored)}</p>
      <div className="rounded-md border border-line bg-surface px-4 pt-7 pb-3">
        <div className="relative h-44">
          {LEVEL_LINES.map((line) => (
            <div
              key={line.level}
              aria-hidden="true"
              className="absolute inset-x-0 border-t border-dashed border-line"
              style={{ bottom: `${line.at}%` }}
            >
              <span className="absolute right-0 -translate-y-1/2 bg-surface pl-1.5 text-xs text-muted">
                {sentenceCase(line.level)}
              </span>
            </div>
          ))}
          <div aria-hidden="true" className="absolute inset-x-0 bottom-0 border-t border-muted" />
          <ol aria-label="Versions, oldest first" className="relative flex h-full items-end gap-1 pr-16">
            {shown.map((version) => (
              <li key={version.number} className="relative h-full max-w-10 flex-1">
                <Link
                  to={`/projects/${project.id}/versions/${version.number}`}
                  aria-label={describe(version)}
                  title={describe(version)}
                  className="group absolute inset-0 block rounded-sm focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-elephant"
                >
                  {version.risk === null ? (
                    <span className="absolute bottom-0 left-1/2 h-2.5 w-2.5 -translate-x-1/2 translate-y-1/2 rounded-full border-2 border-muted bg-surface" />
                  ) : (
                    <>
                      <span
                        className="absolute inset-x-0 bottom-0 rounded-t-sm opacity-85 group-hover:opacity-100"
                        style={{
                          height: `${String(Math.max(version.risk.score, 1))}%`,
                          backgroundColor: colour(version.risk.level),
                        }}
                      />
                      {withScores && (
                        <span
                          className="absolute inset-x-0 text-center text-[11px] font-semibold tabular-nums"
                          style={{
                            bottom: `calc(${String(version.risk.score)}% + 2px)`,
                            color: colour(version.risk.level),
                          }}
                        >
                          {version.risk.score}
                        </span>
                      )}
                    </>
                  )}
                </Link>
              </li>
            ))}
          </ol>
        </div>
        <div aria-hidden="true" className="flex gap-1 pt-1.5 pr-16">
          {shown.map((version, index) => (
            <span key={version.number} className="max-w-10 flex-1 text-center text-[11px] text-muted tabular-nums">
              {index % labelEvery === 0 || index === shown.length - 1 ? version.number : ''}
            </span>
          ))}
        </div>
        {oldestFirst.length > SHOWN && (
          <p className="mt-2 text-xs text-muted">
            The latest {SHOWN} of {oldestFirst.length} versions.
          </p>
        )}
      </div>
    </section>
  );
}
