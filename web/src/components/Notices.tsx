import type { ApiError, Diagnostic } from '../api/client.ts';

const INPUT_NAMES: Record<string, string> = {
  before: 'Current schema',
  after: 'New schema',
  schema: 'Current schema',
  migration: 'Migration',
  new: 'New version',
  previous: 'Latest version',
};

function where(diagnostic: Diagnostic): string {
  return `${INPUT_NAMES[diagnostic.input] ?? diagnostic.input}, line ${diagnostic.line}, column ${diagnostic.column}`;
}

/** An error from the API, with each SQL problem and where it is. */
export function ErrorNotice({ error }: { error: ApiError }) {
  const diagnostics = error.problem.diagnostics ?? [];
  return (
    <div role="alert" className="rounded-md border border-critical/40 bg-critical/5 p-4">
      <p className="font-semibold">{error.problem.title ?? 'The analysis failed'}</p>
      <p className="mt-1 text-sm">{error.message}</p>
      {diagnostics.length > 0 && (
        <ul className="mt-3 space-y-1 text-sm">
          {diagnostics.map((diagnostic, index) => (
            <li key={index}>
              <span className="text-muted">{where(diagnostic)}: </span>
              {diagnostic.message}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

/** Statements the engine skipped (views, functions and the like); the analysis is only about the rest. */
export function SkippedNotice({ warnings }: { warnings: Diagnostic[] }) {
  if (warnings.length === 0) return null;
  const count = warnings.length;
  return (
    <details className="text-sm text-muted">
      <summary className="cursor-pointer">
        {count === 1 ? '1 statement was' : `${count} statements were`} not analysed
      </summary>
      <ul className="mt-2 space-y-1">
        {warnings.map((warning, index) => (
          <li key={index}>
            {where(warning)}: {warning.message}
          </li>
        ))}
      </ul>
    </details>
  );
}

/** Shown while a request is slow: the free API host sleeps when idle and takes a while to start. */
export function WakingNotice() {
  return (
    <p role="status" className="text-sm text-muted">
      Starting the analysis server. It sleeps when nobody uses it, so the first request can take up to a minute.
    </p>
  );
}
