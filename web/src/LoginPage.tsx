import { useState } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router';
import { useAuth } from './auth/AuthContext.ts';
import { problemMessage, returnTo } from './auth/returnTo.ts';
import { AuthCard } from './components/AuthCard.tsx';
import { PasswordField, TextField } from './components/Field.tsx';
import { WakingNotice } from './components/Notices.tsx';
import { useSlow } from './useSlow.ts';

/** Log in with email and password. */
export function LoginPage() {
  const auth = useAuth();
  const location = useLocation();
  const navigate = useNavigate();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const slow = useSlow(pending);
  const from: unknown = location.state;
  const next = returnTo(from);

  if (auth.status === 'signed-in' && !pending) return <Navigate to={next} replace />;

  async function submit() {
    if (email.trim() === '' || password === '') {
      setError('Enter your email and your password.');
      return;
    }
    setPending(true);
    setError(null);
    try {
      await auth.logIn(email, password);
      void navigate(next, { replace: true });
    } catch (failure) {
      setError(problemMessage(failure));
      setPending(false);
    }
  }

  return (
    <AuthCard title="Log in">
      <form
        noValidate
        className="space-y-4"
        onSubmit={(event) => {
          event.preventDefault();
          void submit();
        }}
      >
        <TextField
          label="Email"
          type="email"
          autoComplete="username"
          value={email}
          onChange={(event) => {
            setEmail(event.target.value);
          }}
        />
        <PasswordField
          label="Password"
          autoComplete="current-password"
          value={password}
          onChange={(event) => {
            setPassword(event.target.value);
          }}
        />
        {error && (
          <p role="alert" className="rounded-md border border-critical/40 bg-critical/5 px-3 py-2 text-sm">
            {error}
          </p>
        )}
        <button
          type="submit"
          disabled={pending}
          className="w-full rounded-md bg-elephant px-4 py-2 font-semibold text-paper hover:opacity-90 disabled:opacity-50"
        >
          {pending ? 'Logging in…' : 'Log in'}
        </button>
        {slow && <WakingNotice />}
      </form>
      <p className="mt-6 text-sm text-muted">
        New here?{' '}
        <Link to="/signup" state={from} className="font-medium text-elephant hover:underline">
          Create an account
        </Link>
      </p>
    </AuthCard>
  );
}
