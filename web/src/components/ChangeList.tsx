import type { Change } from '../api/client.ts';

// The engine starts every change line with a marker: + added, - removed, ~ changed, ? possible rename.
const MARKERS: Record<string, { label: string; tone: string }> = {
  '+': { label: 'Added', tone: 'border-low' },
  '-': { label: 'Removed', tone: 'border-critical' },
  '~': { label: 'Changed', tone: 'border-medium' },
  '?': { label: 'Possible rename', tone: 'border-elephant' },
};

function split(summary: string): { marker: { label: string; tone: string } | null; text: string } {
  const marker = MARKERS[summary.charAt(0)];
  return marker && summary.charAt(1) === ' ' ? { marker, text: summary.slice(2) } : { marker: null, text: summary };
}

/** Every difference between the two schemas, marked as added, removed, changed or a possible rename. */
export function ChangeList({ changes }: { changes: Change[] }) {
  if (changes.length === 0) return <p className="text-sm text-muted">The two schemas are the same.</p>;
  return (
    <ul className="space-y-1.5">
      {changes.map((change, index) => {
        const { marker, text } = split(change.summary);
        return (
          <li
            key={`${change.kind}-${index}`}
            className={`border-l-4 py-0.5 pl-3 ${marker?.tone ?? 'border-line'} ${change.cosmetic ? 'opacity-70' : ''}`}
          >
            <span className="mr-2 text-xs font-semibold text-muted">{marker?.label ?? change.kind}</span>
            <code className="font-mono text-sm">{text}</code>
            {change.cosmetic && <span className="ml-2 text-xs text-muted">same meaning, written differently</span>}
          </li>
        );
      })}
    </ul>
  );
}
