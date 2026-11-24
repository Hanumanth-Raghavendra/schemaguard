import { useEffect, useState } from 'react';
import { Link, Navigate } from 'react-router';
import { getAccount, type Account } from './api/client.ts';
import { useAuth } from './auth/AuthContext.ts';
import { problemMessage } from './auth/returnTo.ts';
import { ApiKeys } from './components/ApiKeys.tsx';
import { PasswordField } from './components/Field.tsx';
import { formatDate } from './dates.ts';

/** The logged-in account: who it is, its API keys, and deleting it. Logging out is in the header. */
export function AccountPage() {
  const auth = useAuth();
  const { status, withToken } = auth;
  const [details, setDetails] = useState<Account | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [deleted, setDeleted] = useState(false);

  // The session already names the account; asking the API checks the access token end to end.
  useEffect(() => {
    if (status !== 'signed-in') return;
    const controller = new AbortController();
    withToken((token) => getAccount(token, controller.signal)).then(setDetails, (error: unknown) => {
      if (!controller.signal.aborted) setLoadError(problemMessage(error));
    });
    return () => {
      controller.abort();
    };
  }, [status, withToken]);

  if (deleted) {
    return (
      <section className="max-w-xl space-y-3" aria-labelledby="account-title">
        <h2 id="account-title" className="text-xl font-bold tracking-tight">
          Account deleted
        </h2>
        <p>Your account is gone, and every device it was logged in on is logged out.</p>
        <Link to="/" className="font-medium text-elephant hover:underline">
          Start a new analysis
        </Link>
      </section>
    );
  }
  if (status === 'checking') return <p role="status">Checking your session…</p>;
  if (status === 'anonymous' || auth.account === null)
    return <Navigate to="/login" state={{ from: '/account' }} replace />;

  const account = details ?? auth.account;

  return (
    <div className="max-w-xl space-y-8">
      <section aria-labelledby="account-title" className="space-y-4">
        <h2 id="account-title" className="text-xl font-bold tracking-tight">
          Your account
        </h2>
        <dl className="grid grid-cols-[auto_1fr] gap-x-6 gap-y-2 rounded-md border border-line bg-surface p-4">
          <dt className="text-muted">Email</dt>
          <dd className="font-medium break-all">{account.email}</dd>
          <dt className="text-muted">Member since</dt>
          <dd>{formatDate(account.createdAt)}</dd>
        </dl>
        {loadError && (
          <p role="alert" className="text-sm text-critical">
            {loadError}
          </p>
        )}
      </section>
      <ApiKeys />
      <DeleteAccount
        onDeleted={() => {
          setDeleted(true);
        }}
      />
    </div>
  );
}

/** Deleting asks for the password once more, inline, so a borrowed open laptop cannot do it in one click. */
function DeleteAccount({ onDeleted }: { onDeleted: () => void }) {
  const auth = useAuth();
  const [confirming, setConfirming] = useState(false);
  const [password, setPassword] = useState('');
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit() {
    if (password === '') {
      setError('Enter your password to delete the account.');
      return;
    }
    setPending(true);
    setError(null);
    try {
      await auth.deleteAccount(password);
      onDeleted();
    } catch (failure) {
      setError(problemMessage(failure));
      setPending(false);
    }
  }

  return (
    <section aria-labelledby="delete-title" className="space-y-3 border-t border-line pt-6">
      <h3 id="delete-title" className="font-semibold">
        Delete account
      </h3>
      <p className="text-sm text-muted">
        Deletes the account and logs it out everywhere. Links you shared are not part of it; they expire on their own.
      </p>
      {confirming ? (
        <form
          noValidate
          className="max-w-sm space-y-3"
          aria-label="Confirm delete"
          onSubmit={(event) => {
            event.preventDefault();
            void submit();
          }}
        >
          <PasswordField
            label="Password"
            autoComplete="current-password"
            value={password}
            onChange={(event) => {
              setPassword(event.target.value);
            }}
          />
          {error && (
            <p role="alert" className="text-sm text-critical">
              {error}
            </p>
          )}
          <div className="flex gap-2">
            <button
              type="submit"
              disabled={pending}
              className="rounded-md bg-critical px-3 py-1.5 text-sm font-semibold text-paper disabled:opacity-50"
            >
              {pending ? 'Deleting…' : 'Delete my account'}
            </button>
            <button
              type="button"
              className="rounded-md border border-line bg-surface px-3 py-1.5 text-sm font-medium hover:border-elephant"
              onClick={() => {
                setConfirming(false);
                setPassword('');
                setError(null);
              }}
            >
              Cancel
            </button>
          </div>
        </form>
      ) : (
        <button
          type="button"
          className="rounded-md border border-critical/50 bg-surface px-3 py-1.5 text-sm font-medium text-critical hover:border-critical"
          onClick={() => {
            setConfirming(true);
          }}
        >
          Delete account…
        </button>
      )}
    </section>
  );
}
