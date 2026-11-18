import { useState } from 'react';
import { CompareMode } from './CompareMode.tsx';
import { LintMode } from './LintMode.tsx';

type Mode = 'compare' | 'lint';

const MODES: { value: Mode; label: string }[] = [
  { value: 'compare', label: 'Compare two schemas' },
  { value: 'lint', label: 'Check a migration' },
];

export function App() {
  const [mode, setMode] = useState<Mode>('compare');
  return (
    <div className="mx-auto max-w-6xl px-4 pb-16 sm:px-6">
      <header className="flex flex-col gap-4 py-6 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <h1 className="flex items-center gap-2 text-2xl font-bold tracking-tight">
            <img src="/favicon.svg" alt="" className="h-7 w-7" />
            SchemaGuard
          </h1>
          <p className="mt-1 max-w-xl text-muted">
            See what a PostgreSQL schema change locks before it ships, and get a migration that keeps the database
            online.
          </p>
        </div>
        <fieldset className="flex rounded-md border border-line bg-surface p-0.5">
          <legend className="sr-only">What to do</legend>
          {MODES.map((option) => (
            <label
              key={option.value}
              className={`cursor-pointer rounded px-3 py-1.5 text-sm font-medium has-[:focus-visible]:outline-2 has-[:focus-visible]:outline-elephant ${
                mode === option.value ? 'bg-elephant text-paper' : 'text-muted hover:text-ink'
              }`}
            >
              <input
                type="radio"
                name="mode"
                value={option.value}
                checked={mode === option.value}
                onChange={() => {
                  setMode(option.value);
                }}
                className="sr-only"
              />
              {option.label}
            </label>
          ))}
        </fieldset>
      </header>
      <main>{mode === 'compare' ? <CompareMode /> : <LintMode />}</main>
      <footer className="mt-16 border-t border-line pt-4 text-sm text-muted">
        Lock behaviour measured on PostgreSQL 16.{' '}
        <a className="text-elephant hover:underline" href="https://github.com/Hanumanth-Raghavendra/schemaguard">
          Source on GitHub
        </a>
        , MIT licence.
      </footer>
    </div>
  );
}
