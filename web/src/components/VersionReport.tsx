import { useRef, useState } from 'react';
import {
  diff,
  plan,
  report,
  saveReport,
  type DiffResponse,
  type PlanResponse,
  type Project,
  type ReportResponse,
  type Version,
} from '../api/client.ts';
import { problemMessage } from '../auth/returnTo.ts';
import { downloadJson, downloadMarkdown, fileName, versionMarkdown } from '../export.ts';
import { ResultActions } from './ResultActions.tsx';

interface Analysis {
  report: ReportResponse;
  diff: DiffResponse;
  plan: PlanResponse;
}

/**
 * The report of the change a version made, to download or share. It is analysed when first asked
 * for, with today's rules, and the same analysis serves both downloads. Sharing saves it like any
 * analysis, as a link anyone can open.
 */
export function VersionReport({ project, version, before }: { project: Project; version: Version; before: string }) {
  const [error, setError] = useState<string | null>(null);
  const analysis = useRef<Promise<Analysis> | null>(null);
  const after = version.sql;
  const name = `${fileName(project.name)}-v${String(version.number)}-report`;

  function analyse(): Promise<Analysis> {
    analysis.current ??= Promise.all([
      report(before, after),
      diff(before, after),
      plan({ before, after, route: 'SAFE', renames: [] }),
    ]).then(
      ([reportResult, diffResult, planResult]) => ({ report: reportResult, diff: diffResult, plan: planResult }),
      (failure: unknown) => {
        // A failed analysis is not kept: the next click tries again.
        analysis.current = null;
        throw failure;
      },
    );
    return analysis.current;
  }

  async function save(as: 'markdown' | 'json') {
    setError(null);
    try {
      const result = await analyse();
      if (as === 'markdown') {
        const context = {
          project: project.name,
          number: version.number,
          label: version.label,
          savedAt: version.createdAt,
        };
        downloadMarkdown(`${name}.md`, versionMarkdown(context, result));
      } else {
        downloadJson(`${name}.json`, {
          kind: 'version',
          project: { id: project.id, name: project.name },
          version: {
            number: version.number,
            label: version.label,
            createdAt: version.createdAt,
            savedRisk: version.risk,
          },
          before,
          after,
          ...result,
        });
      }
    } catch (failure) {
      setError(problemMessage(failure));
    }
  }

  return (
    <section aria-labelledby="report-title" className="space-y-3">
      <h3 id="report-title" className="font-semibold">
        Report
      </h3>
      <p className="text-sm text-muted">
        The change from version {version.number - 1}, analysed with today's rules: its risks, changes and a safe plan. A
        link shows both schemas.
      </p>
      <ResultActions
        save={() => saveReport(before, after)}
        onMarkdown={() => {
          void save('markdown');
        }}
        onJson={() => {
          void save('json');
        }}
      />
      {error && (
        <p role="alert" className="text-sm text-critical">
          {error}
        </p>
      )}
    </section>
  );
}
