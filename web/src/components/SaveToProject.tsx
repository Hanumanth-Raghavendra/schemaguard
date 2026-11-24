import { useState } from 'react';
import { Link } from 'react-router';
import { addVersion, createProject, listProjects, type ProjectSummary, type VersionSummary } from '../api/client.ts';
import { useAuth } from '../auth/AuthContext.ts';
import { problemMessage } from '../auth/returnTo.ts';
import { TextField } from './Field.tsx';

const NEW = 'new';
const BUTTON =
  'rounded-md border border-line bg-surface px-3 py-1.5 text-sm font-medium hover:border-elephant disabled:opacity-50';

/** Keeps the new schema of an analysis as the next version of one of the account's projects. */
export function SaveToProject({ schema }: { schema: string }) {
  const { status, withToken } = useAuth();
  const [open, setOpen] = useState(false);
  const [projects, setProjects] = useState<ProjectSummary[] | null>(null);
  const [choice, setChoice] = useState('');
  const [name, setName] = useState('');
  const [label, setLabel] = useState('');
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState<{ project: { id: string; name: string }; version: VersionSummary } | null>(null);

  if (status !== 'signed-in') return null;

  async function start() {
    setOpen(true);
    setError(null);
    try {
      const list = await withToken((token) => listProjects(token));
      setProjects(list);
      setChoice(list[0]?.id ?? NEW);
    } catch (failure) {
      setError(problemMessage(failure));
    }
  }

  async function save() {
    setPending(true);
    setError(null);
    try {
      const result = await withToken(async (token) => {
        const project =
          choice === NEW ? await createProject(token, name) : (projects ?? []).find((p) => p.id === choice);
        if (project === undefined) throw new Error('Choose a project.');
        return {
          project: { id: project.id, name: project.name },
          version: await addVersion(token, project.id, schema, label),
        };
      });
      setSaved(result);
      setOpen(false);
    } catch (failure) {
      setError(problemMessage(failure));
    } finally {
      setPending(false);
    }
  }

  if (saved !== null) {
    return (
      <p role="status" className="text-sm">
        Saved as version {saved.version.number} of{' '}
        <Link to={`/projects/${saved.project.id}`} className="font-medium text-elephant hover:underline">
          {saved.project.name}
        </Link>
        .
      </p>
    );
  }

  if (!open) {
    return (
      <button type="button" className={BUTTON} onClick={() => void start()}>
        Save the new schema to a project
      </button>
    );
  }

  return (
    <form
      noValidate
      aria-label="Save to a project"
      className="space-y-3 rounded-md border border-line bg-paper p-4"
      onSubmit={(event) => {
        event.preventDefault();
        void save();
      }}
    >
      {projects === null && error === null && (
        <p role="status" className="text-sm text-muted">
          Loading your projects…
        </p>
      )}
      {projects !== null && (
        <div className="grid gap-3 sm:grid-cols-2">
          <div className="space-y-1.5">
            <label htmlFor="save-project" className="block text-sm font-semibold">
              Project
            </label>
            <select
              id="save-project"
              value={choice}
              onChange={(event) => {
                setChoice(event.target.value);
              }}
              className="w-full rounded-md border border-line bg-surface px-3 py-2"
            >
              {projects.map((project) => (
                <option key={project.id} value={project.id}>
                  {project.name}
                </option>
              ))}
              <option value={NEW}>A new project…</option>
            </select>
          </div>
          {choice === NEW && (
            <TextField
              label="New project name"
              maxLength={80}
              value={name}
              onChange={(event) => {
                setName(event.target.value);
              }}
            />
          )}
          <div className="sm:col-span-2">
            <TextField
              label="Label (optional)"
              placeholder="Release 2.4, or a commit"
              maxLength={120}
              value={label}
              onChange={(event) => {
                setLabel(event.target.value);
              }}
            />
          </div>
        </div>
      )}
      {error && (
        <p role="alert" className="text-sm text-critical">
          {error}
        </p>
      )}
      <div className="flex gap-2">
        <button
          type="submit"
          disabled={pending || projects === null}
          className="rounded-md bg-elephant px-3 py-1.5 text-sm font-semibold text-paper disabled:opacity-50"
        >
          {pending ? 'Saving…' : 'Save version'}
        </button>
        <button
          type="button"
          className={BUTTON}
          onClick={() => {
            setOpen(false);
            setError(null);
          }}
        >
          Cancel
        </button>
      </div>
    </form>
  );
}
