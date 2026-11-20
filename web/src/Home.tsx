import { useState } from 'react';
import { CompareMode } from './CompareMode.tsx';
import { LintMode } from './LintMode.tsx';
import type { Preset } from './presets.ts';

type Mode = Preset['mode'];

const MODES: { value: Mode; label: string }[] = [
  { value: 'compare', label: 'Compare two schemas' },
  { value: 'lint', label: 'Check a migration' },
];

/** The two ways in: compare two schemas, or check a migration script. */
export function Home({ preset }: { preset: Preset | null }) {
  const [mode, setMode] = useState<Mode>(preset?.mode ?? 'compare');
  return (
    <>
      <fieldset className="mb-6 flex w-fit rounded-md border border-line bg-surface p-0.5">
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
      {mode === 'compare' ? (
        <CompareMode preset={preset?.mode === 'compare' ? preset : null} />
      ) : (
        <LintMode preset={preset?.mode === 'lint' ? preset : null} />
      )}
    </>
  );
}
