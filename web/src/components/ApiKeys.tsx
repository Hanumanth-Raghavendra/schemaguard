import { useEffect, useRef, useState } from 'react';
import { createKey, deleteKey, listKeys, type ApiKey, type NewApiKey } from '../api/client.ts';
import { useAuth } from '../auth/AuthContext.ts';
import { problemMessage } from '../auth/returnTo.ts';
import { useAuthed } from '../auth/useAuthed.ts';
import { formatDate } from '../dates.ts';
import { TextField } from './Field.tsx';

const BUTTON = 'rounded-md border border-line bg-surface px-3 py-1.5 text-sm font-medium hover:border-elephant';

/** The account's API keys: make one (its value is shown once), see when each was last used, delete one. */
export function ApiKeys() {
  const keys = useAuthed(listKeys);
  const [made, setMade] = useState<NewApiKey | null>(null);
  const list = keys.data;

  return (
    <section aria-labelledby="keys-title" className="space-y-4 border-t border-line pt-6">
      <div className="space-y-1">
        <h3 id="keys-title" className="font-semibold">
          API keys
        </h3>
        <p className="text-sm text-muted">
          For the command line and CI. A key can list your projects and save new versions to them, nothing else: it
          cannot rename or delete anything, or open this page.
        </p>
      </div>
      {made !== null && (
        <NewKey
          made={made}
          onDone={() => {
            setMade(null);
          }}
        />
      )}
      <MakeKey
        onMade={(key) => {
          setMade(key);
          if (list === null) keys.reload();
          else keys.replace([{ ...key, lastUsedAt: null }, ...list]);
        }}
      />
      {keys.error !== null ? (
        <p role="alert" className="text-sm text-critical">
          {problemMessage(keys.error)}
        </p>
      ) : list === null ? (
        <p role="status" className="text-sm text-muted">
          Loading your keys…
        </p>
      ) : list.length === 0 ? (
        <p className="rounded-md border border-dashed border-line p-4 text-sm text-muted">
          No keys yet. Make one for each place that uses SchemaGuard, so deleting one never breaks the others.
        </p>
      ) : (
        <ul aria-label="Your API keys" className="divide-y divide-line rounded-md border border-line bg-surface">
          {list.map((key) => (
            <KeyRow
              key={key.id}
              apiKey={key}
              onDeleted={() => {
                keys.replace(list.filter((other) => other.id !== key.id));
              }}
            />
          ))}
        </ul>
      )}
    </section>
  );
}

function MakeKey({ onMade }: { onMade: (key: NewApiKey) => void }) {
  const { withToken } = useAuth();
  const [name, setName] = useState('');
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit() {
    if (name.trim() === '') {
      setError('Name the key first.');
      return;
    }
    setPending(true);
    setError(null);
    try {
      const key = await withToken((token) => createKey(token, name));
      setName('');
      onMade(key);
    } catch (failure) {
      setError(problemMessage(failure));
    }
    setPending(false);
  }

  return (
    <form
      noValidate
      aria-label="Make an API key"
      className="flex flex-col gap-3 sm:flex-row sm:flex-wrap sm:items-end"
      onSubmit={(event) => {
        event.preventDefault();
        void submit();
      }}
    >
      <div className="flex-1">
        <TextField
          label="Key name"
          placeholder="GitHub Actions"
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
        {pending ? 'Making…' : 'Make key'}
      </button>
      {error && (
        <p role="alert" className="text-sm text-critical sm:basis-full">
          {error}
        </p>
      )}
    </form>
  );
}

/** The one moment the whole key is on screen. Focus moves to it, so a screen reader announces it. */
function NewKey({ made, onDone }: { made: NewApiKey; onDone: () => void }) {
  const field = useRef<HTMLInputElement>(null);
  const [copy, setCopy] = useState<'idle' | 'copied' | 'failed'>('idle');

  useEffect(() => {
    field.current?.focus();
    field.current?.select();
  }, [made.id]);

  return (
    <div role="region" aria-label="New API key" className="space-y-3 rounded-md border border-elephant bg-surface p-4">
      <p className="text-sm">
        <span className="font-semibold">Copy the key for “{made.name}” now.</span> It is not shown again, here or
        anywhere else.
      </p>
      <div className="flex gap-2">
        <input
          ref={field}
          readOnly
          aria-label={`Key for ${made.name}`}
          value={made.key}
          onFocus={(event) => {
            event.target.select();
          }}
          className="min-w-0 flex-1 rounded-md border border-line bg-paper px-3 py-1.5 font-mono text-sm"
        />
        <button
          type="button"
          className={BUTTON}
          onClick={() => {
            navigator.clipboard.writeText(made.key).then(
              () => {
                setCopy('copied');
              },
              () => {
                setCopy('failed');
              },
            );
          }}
        >
          {copy === 'copied' ? 'Copied' : 'Copy'}
        </button>
      </div>
      {copy === 'failed' && (
        <p role="alert" className="text-sm text-critical">
          The browser did not allow copying. Select the key and copy it yourself.
        </p>
      )}
      <p className="text-sm text-muted">
        Send it as <code className="font-mono text-ink">Authorization: Bearer &lt;key&gt;</code>, for example:
      </p>
      <pre className="overflow-x-auto rounded-md bg-paper px-3 py-2 font-mono text-xs">
        {`curl -H "Authorization: Bearer $SCHEMAGUARD_KEY" \\\n  ${window.location.origin}/api/v1/projects`}
      </pre>
      <button type="button" className={BUTTON} onClick={onDone}>
        Done, I have copied it
      </button>
    </div>
  );
}

function KeyRow({ apiKey, onDeleted }: { apiKey: ApiKey; onDeleted: () => void }) {
  const { withToken } = useAuth();
  const [confirming, setConfirming] = useState(false);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function remove() {
    setPending(true);
    setError(null);
    try {
      await withToken((token) => deleteKey(token, apiKey.id));
      onDeleted();
    } catch (failure) {
      setError(problemMessage(failure));
      setPending(false);
    }
  }

  return (
    <li className="space-y-2 px-4 py-3" aria-label={apiKey.name}>
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <span className="min-w-0">
          <span className="block truncate font-semibold">{apiKey.name}</span>
          <span className="block text-sm text-muted">
            <code className="font-mono">{apiKey.hint}…</code>, made {formatDate(apiKey.createdAt)},{' '}
            {apiKey.lastUsedAt === null ? 'never used' : `last used ${formatDate(apiKey.lastUsedAt)}`}
          </span>
        </span>
        {!confirming && (
          <button
            type="button"
            className="rounded-md border border-critical/50 bg-surface px-3 py-1.5 text-sm font-medium text-critical hover:border-critical"
            aria-label={`Delete key ${apiKey.name}…`}
            onClick={() => {
              setConfirming(true);
            }}
          >
            Delete…
          </button>
        )}
      </div>
      {confirming && (
        <div className="flex flex-wrap items-center gap-2">
          <p className="text-sm">Anything still using this key stops working at once.</p>
          <button
            type="button"
            disabled={pending}
            className="rounded-md bg-critical px-3 py-1.5 text-sm font-semibold text-paper disabled:opacity-50"
            onClick={() => {
              void remove();
            }}
          >
            {pending ? 'Deleting…' : 'Delete key'}
          </button>
          <button
            type="button"
            className={BUTTON}
            onClick={() => {
              setConfirming(false);
              setError(null);
            }}
          >
            Cancel
          </button>
        </div>
      )}
      {error && (
        <p role="alert" className="text-sm text-critical">
          {error}
        </p>
      )}
    </li>
  );
}
