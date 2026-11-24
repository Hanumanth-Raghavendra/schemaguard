import { useCallback, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { ApiError, deleteVersion, getProject, getVersion, type Project, type Version } from './api/client.ts';
import { useAuth } from './auth/AuthContext.ts';
import { problemMessage } from './auth/returnTo.ts';
import { useAuthed } from './auth/useAuthed.ts';
import { RiskSummary } from './components/RiskSummary.tsx';
import { VersionReport } from './components/VersionReport.tsx';
import { formatDate } from './dates.ts';
import { download, fileName } from './export.ts';
import type { Preset } from './presets.ts';
import { plural } from './words.ts';

/** One saved version: its risk as scored when saved, its SQL, and the way into the analyser. */
export function VersionPage() {
  const { id = '', number = '' } = useParams();
  const wanted = Number.parseInt(number, 10);
  const loaded = useAuthed(
    useCallback(
      async (token: string, signal: AbortSignal) => {
        const [project, version] = await Promise.all([
          getProject(token, id, signal),
          getVersion(token, id, wanted, signal),
        ]);
        return { project, version };
      },
      [id, wanted],
    ),
  );

  if (loaded.error !== null) {
    const missing = loaded.error instanceof ApiError && (loaded.error.status === 404 || loaded.error.status === 400);
    return (
      <div className="space-y-3">
        <p role="alert">{missing ? 'There is no such version in your projects.' : problemMessage(loaded.error)}</p>
        <Link to={`/projects/${id}`} className="font-medium text-elephant hover:underline">
          Back to the project
        </Link>
      </div>
    );
  }
  if (loaded.data === null) return <p role="status">Loading the version…</p>;
  return <VersionView project={loaded.data.project} version={loaded.data.version} />;
}

function VersionView({ project, version }: { project: Project; version: Version }) {
  const { withToken } = useAuth();
  const navigate = useNavigate();
  const [confirming, setConfirming] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const latest = project.versions[0]?.number === version.number;
  const previousSql = version.previousSql;

  function openInAnalyser() {
    if (previousSql === null) return;
    const preset: Preset = { mode: 'compare', before: previousSql, after: version.sql };
    void navigate('/', { state: preset });
  }

  async function remove() {
    setError(null);
    try {
      await withToken((token) => deleteVersion(token, project.id, version.number));
      void navigate(`/projects/${project.id}`, { replace: true });
    } catch (failure) {
      setError(problemMessage(failure));
    }
  }

  const button = 'rounded-md border border-line bg-surface px-3 py-1.5 text-sm font-medium hover:border-elephant';
  return (
    <div className="max-w-4xl space-y-8">
      <section aria-labelledby="version-title" className="space-y-3">
        <p className="text-sm">
          <Link to="/projects" className="font-medium text-elephant hover:underline">
            Projects
          </Link>
          <span className="text-muted"> / </span>
          <Link to={`/projects/${project.id}`} className="font-medium text-elephant hover:underline">
            {project.name}
          </Link>
        </p>
        <h2 id="version-title" className="text-2xl font-bold tracking-tight">
          Version {version.number}
          {version.label !== null && <span className="font-normal text-muted"> {version.label}</span>}
        </h2>
        <p className="text-sm text-muted">Saved {formatDate(version.createdAt)}</p>
      </section>

      <section
        aria-label="Risk of this version"
        className="space-y-4 rounded-lg border border-line bg-surface p-5 sm:p-6"
      >
        {version.risk === null ? (
          <p>The first version: there is nothing before it to compare with.</p>
        ) : (
          <>
            <RiskSummary
              score={{ value: version.risk.score, level: version.risk.level, reasons: version.risk.reasons }}
              subject={`the change from version ${version.number - 1}`}
            />
            <p className="text-sm text-muted">
              {plural(version.risk.changes, 'change')} and {plural(version.risk.findings, 'risk')}, scored when the
              version was saved. The analyser shows every change, the diagram and a migration that keeps the database
              online, with today's rules.
            </p>
            <button
              type="button"
              className="rounded-md bg-elephant px-4 py-2 font-semibold text-paper hover:opacity-90"
              onClick={openInAnalyser}
            >
              Open the change from version {version.number - 1} in the analyser
            </button>
          </>
        )}
      </section>

      {previousSql !== null && <VersionReport project={project} version={version} before={previousSql} />}

      <section aria-labelledby="sql-title" className="space-y-2">
        <div className="flex flex-wrap items-baseline justify-between gap-3">
          <h3 id="sql-title" className="font-semibold">
            Schema
          </h3>
          <button
            type="button"
            className={button}
            onClick={() => {
              download(`${fileName(project.name)}-v${String(version.number)}.sql`, version.sql, 'application/sql');
            }}
          >
            Download SQL
          </button>
        </div>
        <pre className="max-h-[28rem] overflow-auto rounded-md border border-line bg-paper p-4 font-mono text-sm whitespace-pre">
          {version.sql}
        </pre>
      </section>

      {latest && (
        <section aria-labelledby="delete-version-title" className="space-y-3 border-t border-line pt-6">
          <h3 id="delete-version-title" className="font-semibold">
            Delete this version
          </h3>
          <p className="text-sm text-muted">
            Only the latest version can be deleted, since each version's risk is measured against the one before it.
          </p>
          {confirming ? (
            <div className="flex gap-2">
              <button
                type="button"
                className="rounded-md bg-critical px-3 py-1.5 text-sm font-semibold text-paper"
                onClick={() => void remove()}
              >
                Delete version {version.number}
              </button>
              <button
                type="button"
                className={button}
                onClick={() => {
                  setConfirming(false);
                }}
              >
                Cancel
              </button>
            </div>
          ) : (
            <button
              type="button"
              className={`${button} text-critical`}
              onClick={() => {
                setConfirming(true);
              }}
            >
              Delete…
            </button>
          )}
          {error && (
            <p role="alert" className="text-sm text-critical">
              {error}
            </p>
          )}
        </section>
      )}
    </div>
  );
}
