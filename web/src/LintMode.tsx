import { useEffect, useRef, useState } from 'react';
import { ApiError, lint, saveLint, type LintResponse } from './api/client.ts';
import { LintView } from './components/LintView.tsx';
import { ErrorNotice, SkippedNotice, WakingNotice } from './components/Notices.tsx';
import { ResultActions } from './components/ResultActions.tsx';
import { RiskSummary } from './components/RiskSummary.tsx';
import { SqlEditor } from './components/SqlEditor.tsx';
import { EXAMPLE_BEFORE, EXAMPLE_MIGRATION } from './examples.ts';
import { downloadJson, downloadMarkdown, lintMarkdown } from './export.ts';
import { useSlow } from './useSlow.ts';

/** A hand-written migration checked statement by statement against the schema it will run on. */
export function LintMode({ preset }: { preset: { schema: string; migration: string } | null }) {
  const [schema, setSchema] = useState(preset?.schema ?? '');
  const [migration, setMigration] = useState(preset?.migration ?? '');
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [result, setResult] = useState<{ schema: string; migration: string; lint: LintResponse } | null>(null);
  const slow = useSlow(running);

  async function check(schema: string, migration: string) {
    setRunning(true);
    setError(null);
    try {
      setResult({ schema, migration, lint: await lint(schema, migration) });
    } catch (cause) {
      setResult(null);
      setError(cause instanceof ApiError ? cause : new ApiError(0, { title: 'The check failed', detail: String(cause) }));
    } finally {
      setRunning(false);
    }
  }

  // Inputs handed over by another page (a shared link, the plan check) run at once, one time.
  const started = useRef(false);
  useEffect(() => {
    if (preset === null || started.current) return;
    started.current = true;
    void check(preset.schema, preset.migration);
  });

  const diagnosticsFor = (input: string) =>
    [...(error?.problem.diagnostics ?? []), ...(result ? [...result.lint.warnings, ...result.lint.diagnostics] : [])].filter(
      (diagnostic) => diagnostic.input === input,
    );

  return (
    <div className="space-y-8">
      <form
        className="space-y-4"
        onSubmit={(event) => {
          event.preventDefault();
          void check(schema, migration);
        }}
      >
        <div className="grid gap-4 md:grid-cols-2">
          <SqlEditor
            label="Current schema"
            value={schema}
            onChange={setSchema}
            diagnostics={diagnosticsFor('schema')}
            placeholder="Paste the schema the migration will run on"
          />
          <SqlEditor
            label="Migration"
            value={migration}
            onChange={setMigration}
            diagnostics={diagnosticsFor('migration')}
            placeholder="Paste the migration script"
          />
        </div>
        <div className="flex flex-wrap items-center gap-3">
          <button
            type="submit"
            disabled={running || migration.trim() === ''}
            className="rounded-md bg-elephant px-4 py-2 font-semibold text-paper hover:opacity-90 disabled:opacity-50"
          >
            {running ? 'Checking…' : 'Check migration'}
          </button>
          <button
            type="button"
            onClick={() => {
              setSchema(EXAMPLE_BEFORE);
              setMigration(EXAMPLE_MIGRATION);
            }}
            className="rounded-md px-3 py-2 text-sm font-medium text-elephant hover:underline"
          >
            Load an example
          </button>
          {slow && <WakingNotice />}
        </div>
      </form>

      {error && <ErrorNotice error={error} />}

      {result && (
        <div className="space-y-6 rounded-lg border border-line bg-surface p-5 sm:p-6">
          <RiskSummary score={result.lint.score} subject="this migration" />
          <ResultActions
            save={() => saveLint(result.schema, result.migration)}
            onMarkdown={() => {
              downloadMarkdown('schemaguard-migration-check.md', lintMarkdown(result.lint));
            }}
            onJson={() => {
              downloadJson('schemaguard-migration-check.json', { kind: 'lint', ...result });
            }}
          />
          <SkippedNotice warnings={result.lint.warnings} />
          <LintView lint={result.lint} />
        </div>
      )}
    </div>
  );
}
