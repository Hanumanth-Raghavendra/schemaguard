import { useCallback, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import {
  addVersion,
  ApiError,
  deleteProject,
  getProject,
  getVersion,
  renameProject,
  type Project,
} from './api/client.ts';
import { useAuth } from './auth/AuthContext.ts';
import { problemMessage } from './auth/returnTo.ts';
import { useAuthed } from './auth/useAuthed.ts';
import { TextField } from './components/Field.tsx';
import { ErrorNotice } from './components/Notices.tsx';
import { RiskTag } from './components/RiskTag.tsx';
import { SqlEditor } from './components/SqlEditor.tsx';
import { formatDate } from './dates.ts';
import { splitReason } from './reasons.ts';
import { plural } from './words.ts';

const BUTTON = 'rounded-md border border-line bg-surface px-3 py-1.5 text-sm font-medium hover:border-elephant';

/** One project: its name, a form to save the next version, and the timeline of versions. */
export function ProjectPage() {
  const { id = '' } = useParams();
  const project = useAuthed(useCallback((token: string, signal: AbortSignal) => getProject(token, id, signal), [id]));

  if (project.error !== null) {
    const missing = project.error instanceof ApiError && project.error.status === 404;
    return (
      <div className="space-y-3">
        <p role="alert">{missing ? 'There is no such project in your account.' : problemMessage(project.error)}</p>
        <Link to="/projects" className="font-medium text-elephant hover:underline">
          Your projects
        </Link>
      </div>
    );
  }
  if (project.data === null) return <p role="status">Loading the project…</p>;

  return (
    <div className="max-w-4xl space-y-10">
      <ProjectHeader project={project.data} onRenamed={project.replace} />
      <NewVersion project={project.data} onSaved={project.reload} />
      <Timeline project={project.data} />
    </div>
  );
}

function ProjectHeader({ project, onRenamed }: { project: Project; onRenamed: (project: Project) => void }) {
  const { withToken } = useAuth();
  const navigate = useNavigate();
  const [mode, setMode] = useState<'view' | 'rename' | 'delete'>('view');
  const [name, setName] = useState(project.name);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function run(action: () => Promise<void>) {
    setPending(true);
    setError(null);
    try {
      await action();
    } catch (failure) {
      setError(problemMessage(failure));
    } finally {
      setPending(false);
    }
  }

  const rename = () =>
    run(async () => {
      onRenamed(await withToken((token) => renameProject(token, project.id, name)));
      setMode('view');
    });
  const remove = () =>
    run(async () => {
      await withToken((token) => deleteProject(token, project.id));
      void navigate('/projects', { replace: true });
    });

  return (
    <section aria-labelledby="project-title" className="space-y-3">
      <p className="text-sm">
        <Link to="/projects" className="font-medium text-elephant hover:underline">
          Projects
        </Link>
      </p>
      {mode === 'rename' ? (
        <form
          noValidate
          aria-label="Rename project"
          className="flex flex-col gap-3 sm:flex-row sm:items-end"
          onSubmit={(event) => {
            event.preventDefault();
            void rename();
          }}
        >
          <div className="flex-1">
            <TextField
              label="Project name"
              maxLength={80}
              value={name}
              onChange={(event) => {
                setName(event.target.value);
              }}
            />
          </div>
          <div className="flex gap-2">
            <button
              type="submit"
              disabled={pending}
              className="rounded-md bg-elephant px-3 py-1.5 text-sm font-semibold text-paper disabled:opacity-50"
            >
              Save name
            </button>
            <button
              type="button"
              className={BUTTON}
              onClick={() => {
                setMode('view');
                setName(project.name);
                setError(null);
              }}
            >
              Cancel
            </button>
          </div>
        </form>
      ) : (
        <div className="flex flex-wrap items-baseline justify-between gap-3">
          <h2 id="project-title" className="min-w-0 text-2xl font-bold tracking-tight break-words">
            {project.name}
          </h2>
          {mode === 'view' && (
            <div className="flex gap-2">
              <button
                type="button"
                className={BUTTON}
                onClick={() => {
                  setMode('rename');
                }}
              >
                Rename
              </button>
              <button
                type="button"
                className={`${BUTTON} text-critical`}
                onClick={() => {
                  setMode('delete');
                }}
              >
                Delete…
              </button>
            </div>
          )}
        </div>
      )}
      {mode === 'delete' && (
        <div
          role="group"
          aria-label="Confirm delete"
          className="space-y-3 rounded-md border border-critical/40 bg-critical/5 p-4"
        >
          <p className="text-sm">
            Delete {project.name} and its {plural(project.versions.length, 'version')}? This cannot be undone.
          </p>
          <div className="flex gap-2">
            <button
              type="button"
              disabled={pending}
              className="rounded-md bg-critical px-3 py-1.5 text-sm font-semibold text-paper disabled:opacity-50"
              onClick={() => void remove()}
            >
              {pending ? 'Deleting…' : 'Delete project'}
            </button>
            <button
              type="button"
              className={BUTTON}
              onClick={() => {
                setMode('view');
              }}
            >
              Cancel
            </button>
          </div>
        </div>
      )}
      {error && (
        <p role="alert" className="text-sm text-critical">
          {error}
        </p>
      )}
    </section>
  );
}

function NewVersion({ project, onSaved }: { project: Project; onSaved: () => void }) {
  const { withToken } = useAuth();
  const [sql, setSql] = useState('');
  const [label, setLabel] = useState('');
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const latest = project.versions[0] ?? null;
  // An empty project opens on the form; one with versions opens on its timeline.
  const [open, setOpen] = useState(latest === null);

  async function startFromLatest() {
    if (latest === null) return;
    setError(null);
    try {
      const version = await withToken((token) => getVersion(token, project.id, latest.number));
      setSql(version.sql);
    } catch (failure) {
      setError(failure);
    }
  }

  async function save() {
    setPending(true);
    setError(null);
    try {
      await withToken((token) => addVersion(token, project.id, sql, label));
      setSql('');
      setLabel('');
      setOpen(false);
      onSaved();
    } catch (failure) {
      setError(failure);
    } finally {
      setPending(false);
    }
  }

  const apiError = error instanceof ApiError ? error : null;
  if (!open) {
    return (
      <button
        type="button"
        className="rounded-md bg-elephant px-4 py-2 font-semibold text-paper hover:opacity-90"
        onClick={() => {
          setOpen(true);
        }}
      >
        Save version {(latest?.number ?? 0) + 1}
      </button>
    );
  }
  return (
    <section aria-labelledby="new-version-title" className="space-y-4">
      <div className="flex flex-wrap items-baseline justify-between gap-3">
        <h3 id="new-version-title" className="text-lg font-semibold">
          Save version {(latest?.number ?? 0) + 1}
        </h3>
        {latest !== null && (
          <button type="button" className={BUTTON} onClick={() => void startFromLatest()}>
            Start from version {latest.number}
          </button>
        )}
      </div>
      <p className="text-sm text-muted">
        Paste the whole schema, as <code className="font-mono">pg_dump --schema-only</code> prints it.
        {latest !== null && ' It is compared with the latest version, and the risk of that change is kept with it.'}
      </p>
      <form
        noValidate
        aria-label="New version"
        className="space-y-4"
        onSubmit={(event) => {
          event.preventDefault();
          void save();
        }}
      >
        <SqlEditor
          label="Schema"
          value={sql}
          onChange={setSql}
          diagnostics={apiError?.problem.diagnostics ?? []}
          placeholder="CREATE TABLE ..."
        />
        <TextField
          label="Label (optional)"
          placeholder="Release 2.4, or a commit"
          maxLength={120}
          value={label}
          onChange={(event) => {
            setLabel(event.target.value);
          }}
        />
        {apiError !== null && apiError.problem.diagnostics?.length ? (
          <ErrorNotice error={apiError} />
        ) : error !== null ? (
          <p role="alert" className="rounded-md border border-critical/40 bg-critical/5 px-3 py-2 text-sm">
            {problemMessage(error)}
          </p>
        ) : null}
        <button
          type="submit"
          disabled={pending || sql.trim() === ''}
          className="rounded-md bg-elephant px-4 py-2 font-semibold text-paper hover:opacity-90 disabled:opacity-50"
        >
          {pending ? 'Saving…' : 'Save version'}
        </button>
        {latest !== null && (
          <button
            type="button"
            className={`${BUTTON} ml-2`}
            onClick={() => {
              setOpen(false);
              setError(null);
            }}
          >
            Cancel
          </button>
        )}
      </form>
    </section>
  );
}

function Timeline({ project }: { project: Project }) {
  return (
    <section aria-labelledby="timeline-title" className="space-y-4">
      <h3 id="timeline-title" className="text-lg font-semibold">
        Versions
      </h3>
      {project.versions.length === 0 ? (
        <p className="rounded-md border border-dashed border-line p-6 text-sm text-muted">
          No versions yet. Save the schema as it is today; each version after it shows how risky the change was.
        </p>
      ) : (
        <ol className="space-y-3 border-l-2 border-line pl-5">
          {project.versions.map((version) => (
            <li key={version.number} className="relative">
              <span
                className="absolute top-2 -left-[27px] h-3 w-3 rounded-full border-2 border-line bg-paper"
                aria-hidden="true"
              />
              <Link
                to={`/projects/${project.id}/versions/${version.number}`}
                className="block rounded-md border border-line bg-surface px-4 py-3 hover:border-elephant"
              >
                <span className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
                  <span className="font-semibold">
                    Version {version.number}
                    {version.label !== null && <span className="font-normal text-muted"> {version.label}</span>}
                  </span>
                  <span className="text-sm text-muted">{formatDate(version.createdAt)}</span>
                </span>
                <span className="mt-1 block">
                  <RiskTag risk={version.risk} />
                </span>
                {version.risk !== null && version.risk.reasons.length > 0 && (
                  <span className="mt-1 block truncate text-sm text-muted">
                    {splitReason(version.risk.reasons[0] ?? '').title}
                  </span>
                )}
              </Link>
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}
