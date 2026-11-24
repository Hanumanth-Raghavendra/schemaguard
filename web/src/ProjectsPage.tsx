import { useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { createProject, listProjects } from './api/client.ts';
import { useAuth } from './auth/AuthContext.ts';
import { problemMessage } from './auth/returnTo.ts';
import { useAuthed } from './auth/useAuthed.ts';
import { TextField } from './components/Field.tsx';
import { RiskTag } from './components/RiskTag.tsx';
import { formatDate } from './dates.ts';
import { plural } from './words.ts';

/** The account's projects, and a form to start one. */
export function ProjectsPage() {
  const projects = useAuthed(listProjects);
  return (
    <div className="max-w-3xl space-y-8">
      <section className="space-y-2" aria-labelledby="projects-title">
        <h2 id="projects-title" className="text-xl font-bold tracking-tight">
          Projects
        </h2>
        <p className="text-muted">
          A project keeps every version of one schema and how risky each change between them was.
        </p>
      </section>
      <NewProject />
      <section aria-label="Your projects">
        {projects.error !== null ? (
          <p role="alert" className="text-sm text-critical">
            {problemMessage(projects.error)}
          </p>
        ) : projects.data === null ? (
          <p role="status" className="text-sm text-muted">
            Loading your projects…
          </p>
        ) : projects.data.length === 0 ? (
          <p className="rounded-md border border-dashed border-line p-6 text-sm text-muted">
            No projects yet. Create one above, then save a schema as its first version, here or from the analyser.
          </p>
        ) : (
          <ul className="divide-y divide-line rounded-md border border-line bg-surface">
            {projects.data.map((project) => (
              <li key={project.id}>
                <Link
                  to={`/projects/${project.id}`}
                  className="flex flex-col gap-1 px-4 py-3 hover:bg-paper sm:flex-row sm:items-center sm:justify-between"
                >
                  <span className="min-w-0">
                    <span className="block truncate font-semibold text-elephant">{project.name}</span>
                    <span className="block text-sm text-muted">
                      {project.latest === null
                        ? 'No versions yet'
                        : `${plural(project.versionCount, 'version')}, last saved ${formatDate(project.latest.createdAt)}`}
                    </span>
                  </span>
                  {project.latest !== null && <RiskTag risk={project.latest.risk} />}
                </Link>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}

function NewProject() {
  const { withToken } = useAuth();
  const navigate = useNavigate();
  const [name, setName] = useState('');
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit() {
    if (name.trim() === '') {
      setError('Name the project first.');
      return;
    }
    setPending(true);
    setError(null);
    try {
      const project = await withToken((token) => createProject(token, name));
      void navigate(`/projects/${project.id}`);
    } catch (failure) {
      setError(problemMessage(failure));
      setPending(false);
    }
  }

  return (
    <form
      noValidate
      aria-label="Create a project"
      className="flex flex-col gap-3 sm:flex-row sm:flex-wrap sm:items-end"
      onSubmit={(event) => {
        event.preventDefault();
        void submit();
      }}
    >
      <div className="flex-1">
        <TextField
          label="New project"
          placeholder="shop"
          maxLength={80}
          value={name}
          onChange={(event) => {
            setName(event.target.value);
          }}
        />
      </div>
      <button
        type="submit"
        disabled={pending}
        className="rounded-md bg-elephant px-4 py-2 font-semibold text-paper hover:opacity-90 disabled:opacity-50"
      >
        {pending ? 'Creating…' : 'Create project'}
      </button>
      {error && (
        <p role="alert" className="text-sm text-critical sm:basis-full">
          {error}
        </p>
      )}
    </form>
  );
}
