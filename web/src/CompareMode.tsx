import { lazy, Suspense, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router';
import {
  ApiError,
  diff,
  model,
  plan,
  report,
  saveReport,
  type DiffResponse,
  type ModelResponse,
  type PlanResponse,
  type RenameDto,
  type ReportResponse,
  type Route,
} from './api/client.ts';
import { ChangeList } from './components/ChangeList.tsx';
import { FindingList } from './components/FindingList.tsx';
import { Gallery } from './components/Gallery.tsx';
import { ErrorNotice, SkippedNotice, WakingNotice } from './components/Notices.tsx';
import { PlanView } from './components/PlanView.tsx';
import { ResultActions } from './components/ResultActions.tsx';
import { RiskSummary } from './components/RiskSummary.tsx';
import { SqlEditor } from './components/SqlEditor.tsx';
import { Tabs } from './components/Tabs.tsx';
import { compareMarkdown, downloadJson, downloadMarkdown } from './export.ts';
import type { Preset } from './presets.ts';
import { renameKey } from './renames.ts';
import { useSlow } from './useSlow.ts';

// The diagram and its layout library load when the Diagram tab first opens.
const ErDiagram = lazy(() => import('./components/ErDiagram.tsx'));

interface Results {
  before: string;
  after: string;
  diff: DiffResponse;
  report: ReportResponse;
  plan: PlanResponse;
  model: ModelResponse;
}

function toError(cause: unknown): ApiError {
  if (cause instanceof ApiError) return cause;
  return new ApiError(0, { title: 'The analysis failed', detail: String(cause) });
}

/** Two schemas in, three answers out: what changed, what it risks, and how to apply it. */
export function CompareMode({ preset }: { preset: { before: string; after: string } | null }) {
  const navigate = useNavigate();
  const [before, setBefore] = useState(preset?.before ?? '');
  const [after, setAfter] = useState(preset?.after ?? '');
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [results, setResults] = useState<Results | null>(null);
  const [tab, setTab] = useState('risks');
  const [route, setRoute] = useState<Route>('SAFE');
  const [renames, setRenames] = useState<RenameDto[]>([]);
  const [planning, setPlanning] = useState(false);
  const slow = useSlow(running);
  const planRequest = useRef<AbortController | null>(null);

  async function analyse(before: string, after: string) {
    setRunning(true);
    setError(null);
    setRenames([]);
    try {
      const [diffResult, reportResult, planResult, modelResult] = await Promise.all([
        diff(before, after),
        report(before, after),
        plan({ before, after, route, renames: [] }),
        model(before, after),
      ]);
      setResults({ before, after, diff: diffResult, report: reportResult, plan: planResult, model: modelResult });
    } catch (cause) {
      setResults(null);
      setError(toError(cause));
    } finally {
      setRunning(false);
    }
  }

  async function replan(nextRoute: Route, nextRenames: RenameDto[]) {
    if (!results) return;
    planRequest.current?.abort();
    const controller = new AbortController();
    planRequest.current = controller;
    setPlanning(true);
    try {
      const planResult = await plan(
        { before: results.before, after: results.after, route: nextRoute, renames: nextRenames },
        controller.signal,
      );
      setResults((current) => (current ? { ...current, plan: planResult } : current));
      setError(null);
    } catch (cause) {
      if (controller.signal.aborted) return;
      setError(toError(cause));
    } finally {
      if (planRequest.current === controller) setPlanning(false);
    }
  }

  useEffect(() => () => planRequest.current?.abort(), []);

  // Inputs handed over by another page (a shared link, the plan check) run at once, one time.
  const started = useRef(false);
  useEffect(() => {
    if (preset === null || started.current) return;
    started.current = true;
    void analyse(preset.before, preset.after);
  });

  const diagnosticsFor = (input: string) => [
    ...(error?.problem.diagnostics ?? []),
    ...(results ? [...results.diff.warnings] : []),
  ].filter((diagnostic) => diagnostic.input === input);

  function checkPlan() {
    if (!results) return;
    const next: Preset = { mode: 'lint', schema: results.before, migration: results.plan.sql };
    void navigate('/', { state: next });
  }

  const candidates = results?.diff.changes.filter((change) => change.rename !== null) ?? [];
  const confirmed = new Set(renames.map(renameKey));

  return (
    <div className="space-y-8">
      <form
        className="space-y-4"
        onSubmit={(event) => {
          event.preventDefault();
          void analyse(before, after);
        }}
      >
        <Gallery
          disabled={running}
          onPick={(example) => {
            setBefore(example.before);
            setAfter(example.after);
            void analyse(example.before, example.after);
          }}
        />
        <div className="grid gap-4 md:grid-cols-2">
          <SqlEditor
            label="Current schema"
            value={before}
            onChange={setBefore}
            diagnostics={diagnosticsFor('before')}
            placeholder="Paste the schema as it is now, for example pg_dump --schema-only output"
          />
          <SqlEditor
            label="New schema"
            value={after}
            onChange={setAfter}
            diagnostics={diagnosticsFor('after')}
            placeholder="Paste the schema as it should be after the release"
          />
        </div>
        <div className="flex flex-wrap items-center gap-3">
          <button
            type="submit"
            disabled={running || after.trim() === ''}
            className="rounded-md bg-elephant px-4 py-2 font-semibold text-paper hover:opacity-90 disabled:opacity-50"
          >
            {running ? 'Analysing…' : 'Analyse changes'}
          </button>
          {slow && <WakingNotice />}
        </div>
      </form>

      {error && <ErrorNotice error={error} />}

      {results && (
        <div className="space-y-6 rounded-lg border border-line bg-surface p-5 sm:p-6">
          <RiskSummary score={results.report.score} subject="this change" />
          <ResultActions
            save={() => saveReport(results.before, results.after)}
            onMarkdown={() => {
              downloadMarkdown('schemaguard-report.md', compareMarkdown(results));
            }}
            onJson={() => {
              downloadJson('schemaguard-report.json', { kind: 'compare', ...results });
            }}
          />
          <SkippedNotice warnings={results.diff.warnings} />
          <Tabs
            label="Results"
            selected={tab}
            onSelect={setTab}
            tabs={[
              {
                id: 'risks',
                label: `Risks (${results.report.findings.length})`,
                content: (
                  <div className="space-y-4">
                    {renames.length > 0 && (
                      <p className="border-l-2 border-elephant pl-2 text-sm">
                        These risks judge the schemas as written, so a rename you confirmed still counts as a drop and
                        an add. The plan keeps the data; to see the risks of the plan itself,{' '}
                        <button type="button" onClick={checkPlan} className="text-elephant underline">
                          check the plan as a migration
                        </button>
                        .
                      </p>
                    )}
                    <FindingList findings={results.report.findings} empty="No risks found." />
                  </div>
                ),
              },
              {
                id: 'changes',
                label: `Changes (${results.diff.significant})`,
                content: <ChangeList changes={results.diff.changes} />,
              },
              {
                id: 'diagram',
                label: 'Diagram',
                content: (
                  <Suspense fallback={<p className="text-sm text-muted">Drawing the diagram…</p>}>
                    <ErDiagram model={results.model} />
                  </Suspense>
                ),
              },
              {
                id: 'plan',
                label: 'Plan',
                content: (
                  <PlanView
                    plan={results.plan}
                    route={route}
                    onRouteChange={(next) => {
                      setRoute(next);
                      void replan(next, renames);
                    }}
                    candidates={candidates}
                    confirmed={confirmed}
                    onToggleRename={(rename) => {
                      const key = renameKey(rename);
                      const next = confirmed.has(key)
                        ? renames.filter((each) => renameKey(each) !== key)
                        : [...renames, rename];
                      setRenames(next);
                      void replan(route, next);
                    }}
                    planning={planning}
                    onCheckPlan={checkPlan}
                  />
                ),
              },
            ]}
          />
        </div>
      )}
    </div>
  );
}
