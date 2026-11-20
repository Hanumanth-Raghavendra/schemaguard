import { useEffect, useState, type ReactNode } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import {
  ApiError,
  deleteAnalysis,
  openAnalysis,
  type DiffResponse,
  type LintResponse,
  type ReportResponse,
  type StoredAnalysis,
} from './api/client.ts';
import { ChangeList } from './components/ChangeList.tsx';
import { FindingList } from './components/FindingList.tsx';
import { LintView } from './components/LintView.tsx';
import { ResultActions } from './components/ResultActions.tsx';
import { RiskSummary } from './components/RiskSummary.tsx';
import { formatDate } from './dates.ts';
import { compareMarkdown, downloadJson, downloadMarkdown, lintMarkdown } from './export.ts';
import type { Preset } from './presets.ts';
import { forgetToken, tokenFor } from './tokens.ts';

const TITLES: Record<string, string> = {
  report: 'Shared risk report',
  lint: 'Shared migration check',
  diff: 'Shared schema diff',
  plan: 'Shared migration plan',
};

const OPEN_ERRORS: Record<number, string> = {
  404: 'This link has expired or was deleted.',
  503: 'Shared links are switched off on this server.',
};

type State =
  | { status: 'loading' }
  | { status: 'failed'; message: string }
  | { status: 'loaded'; analysis: StoredAnalysis }
  | { status: 'deleted' };

function text(record: Record<string, unknown>, key: string): string {
  const value = record[key];
  return typeof value === 'string' ? value : '';
}

/** The inputs that were saved, by kind: schemas for diff, report and plan; schema and migration for lint. */
function inputs(analysis: StoredAnalysis): { label: string; sql: string }[] {
  const request = analysis.request as Record<string, unknown>;
  return analysis.kind === 'lint'
    ? [
        { label: 'Current schema', sql: text(request, 'schema') },
        { label: 'Migration', sql: text(request, 'migration') },
      ]
    : [
        { label: 'Current schema', sql: text(request, 'before') },
        { label: 'New schema', sql: text(request, 'after') },
      ];
}

function preset(analysis: StoredAnalysis): Preset {
  const request = analysis.request as Record<string, unknown>;
  return analysis.kind === 'lint'
    ? { mode: 'lint', schema: text(request, 'schema'), migration: text(request, 'migration') }
    : { mode: 'compare', before: text(request, 'before'), after: text(request, 'after') };
}

function has(result: object, ...keys: string[]): boolean {
  return keys.every((key) => key in result);
}

/** A saved analysis opened from its link: the saved result, the inputs, and what can be done with them. */
export function SharedAnalysis() {
  const { id = '' } = useParams();
  const navigate = useNavigate();
  const [state, setState] = useState<State>({ status: 'loading' });
  const [token, setToken] = useState<string | null>(() => tokenFor(id));
  const [confirming, setConfirming] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    openAnalysis(id, controller.signal)
      .then((analysis) => {
        setState({ status: 'loaded', analysis });
      })
      .catch((cause: unknown) => {
        if (controller.signal.aborted) return;
        const status = cause instanceof ApiError ? cause.status : 0;
        setState({
          status: 'failed',
          message: OPEN_ERRORS[status] ?? (cause instanceof Error ? cause.message : 'The link could not be opened.'),
        });
      });
    return () => {
      controller.abort();
    };
  }, [id]);

  async function remove(deleteToken: string) {
    setDeleteError(null);
    try {
      await deleteAnalysis(id, deleteToken);
      forgetToken(id);
      setState({ status: 'deleted' });
    } catch (cause) {
      const status = cause instanceof ApiError ? cause.status : 0;
      if (status === 404) {
        forgetToken(id);
        setState({ status: 'deleted' });
      } else if (status === 403) {
        forgetToken(id);
        setToken(null);
        setDeleteError('The delete key kept in this browser does not match this link, so it was not deleted.');
      } else {
        setDeleteError(cause instanceof Error ? cause.message : 'The link was not deleted. Try again.');
      }
    } finally {
      setConfirming(false);
    }
  }

  if (state.status === 'loading') return <p role="status">Opening the shared analysis…</p>;
  if (state.status === 'deleted' || state.status === 'failed') {
    return (
      <div className="space-y-3">
        <p role={state.status === 'failed' ? 'alert' : 'status'} className="font-semibold">
          {state.status === 'deleted' ? 'Deleted. The link no longer works.' : state.message}
        </p>
        <Link to="/" className="text-elephant hover:underline">
          Start a new analysis
        </Link>
      </div>
    );
  }

  const { analysis } = state;
  const result = analysis.result as object;
  const button = 'rounded-md border border-line bg-surface px-3 py-1.5 text-sm font-medium hover:border-elephant';

  let body: ReactNode;
  let markdown: (() => string) | null = null;
  if (analysis.kind === 'report' && has(result, 'score', 'findings')) {
    const report = result as ReportResponse;
    body = (
      <>
        <RiskSummary score={report.score} subject="this change" />
        <FindingList findings={report.findings} empty="No risks found." />
      </>
    );
    markdown = () => compareMarkdown({ report });
  } else if (analysis.kind === 'lint' && has(result, 'score', 'statements')) {
    const lint = result as LintResponse;
    body = (
      <>
        <RiskSummary score={lint.score} subject="this migration" />
        <LintView lint={lint} />
      </>
    );
    markdown = () => lintMarkdown(lint);
  } else if (analysis.kind === 'diff' && has(result, 'changes')) {
    body = <ChangeList changes={(result as DiffResponse).changes} />;
  } else {
    body = <p className="text-sm text-muted">Open it in the editor to see the full analysis.</p>;
  }

  return (
    <article className="space-y-6">
      <header className="space-y-1">
        <h2 className="text-xl font-bold">{TITLES[analysis.kind] ?? 'Shared analysis'}</h2>
        <p className="text-sm text-muted">
          Saved {formatDate(analysis.createdAt)} by SchemaGuard {analysis.engineVersion}. The link works until{' '}
          {formatDate(analysis.expiresAt)}.
        </p>
      </header>

      <div className="flex flex-wrap items-start gap-2">
        <button
          type="button"
          onClick={() => {
            void navigate('/', { state: preset(analysis) });
          }}
          className="rounded-md bg-elephant px-3 py-1.5 text-sm font-semibold text-paper hover:opacity-90"
        >
          Open in the editor
        </button>
        <ResultActions
          onMarkdown={() => {
            downloadMarkdown(`schemaguard-${analysis.id}.md`, markdown ? markdown() : '');
          }}
          onJson={() => {
            downloadJson(`schemaguard-${analysis.id}.json`, analysis);
          }}
        />
        {token !== null && !confirming && (
          <button
            type="button"
            onClick={() => {
              setConfirming(true);
            }}
            className={`${button} text-critical`}
          >
            Delete link
          </button>
        )}
      </div>
      {token !== null && confirming && (
        <div role="group" aria-label="Confirm delete" className="flex flex-wrap items-center gap-3 text-sm">
          <span>Delete this link for everyone? This cannot be undone.</span>
          <button
            type="button"
            onClick={() => {
              void remove(token);
            }}
            className="rounded-md bg-critical px-3 py-1.5 font-semibold text-paper"
          >
            Delete
          </button>
          <button
            type="button"
            onClick={() => {
              setConfirming(false);
            }}
            className={button}
          >
            Cancel
          </button>
        </div>
      )}
      {deleteError !== null && (
        <p role="alert" className="text-sm text-critical">
          {deleteError}
        </p>
      )}

      <div className="space-y-6 rounded-lg border border-line bg-surface p-5 sm:p-6">{body}</div>

      <details className="text-sm">
        <summary className="cursor-pointer font-semibold">Inputs</summary>
        <div className="mt-3 grid gap-4 md:grid-cols-2">
          {inputs(analysis).map((input) => (
            <section key={input.label} className="min-w-0 space-y-1.5">
              <h3 className="font-semibold">{input.label}</h3>
              <pre className="max-h-96 overflow-auto rounded-md border border-line bg-surface p-3 font-mono text-[13px]">
                {input.sql === '' ? '(empty)' : input.sql}
              </pre>
            </section>
          ))}
        </div>
      </details>
    </article>
  );
}
