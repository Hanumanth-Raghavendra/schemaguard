import { EXAMPLES, type Example } from '../examples.ts';

/** Real-world changes; picking one fills both editors and runs the analysis. */
export function Gallery({ onPick, disabled }: { onPick: (example: Example) => void; disabled: boolean }) {
  return (
    <section aria-labelledby="gallery-heading" className="space-y-2">
      <h2 id="gallery-heading" className="text-sm font-semibold">
        Try an example
      </h2>
      <ul className="grid gap-2 sm:grid-cols-2 lg:grid-cols-4">
        {EXAMPLES.map((example) => (
          <li key={example.id}>
            <button
              type="button"
              disabled={disabled}
              onClick={() => {
                onPick(example);
              }}
              className="h-full w-full rounded-md border border-line border-l-4 border-l-elephant bg-surface px-3 py-2 text-left hover:border-elephant disabled:opacity-50"
            >
              <span className="block text-sm font-semibold">{example.title}</span>
              <span className="mt-0.5 block text-xs text-muted">{example.summary}</span>
            </button>
          </li>
        ))}
      </ul>
    </section>
  );
}
