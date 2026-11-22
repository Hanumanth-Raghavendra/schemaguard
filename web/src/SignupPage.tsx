import { useState } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router';
import { useAuth } from './auth/AuthContext.ts';
import { problemMessage, returnTo } from './auth/returnTo.ts';
import { AuthCard } from './components/AuthCard.tsx';
import { PasswordField, TextField } from './components/Field.tsx';
import { WakingNotice } from './components/Notices.tsx';
import { useSlow } from './useSlow.ts';

/** Create an account. The server decides what a usable password is and says why when it is not. */
export function SignupPage() {
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
      setError('Enter your email and choose a password.');
      return;
    }
    setPending(true);
    setError(null);
    try {
      await auth.signUp(email, password);
      void navigate(next, { replace: true });
    } catch (failure) {
      setError(problemMessage(failure));
      setPending(false);
    }
  }

  return (
    <AuthCard title="Create an account" intro="You do not need one to analyse schemas or share links.">
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
          autoComplete="email"
          value={email}
          onChange={(event) => {
            setEmail(event.target.value);
          }}
        />
        <PasswordField
          label="Password"
          autoComplete="new-password"
          hint="At least 8 characters. A few unrelated words make a strong password that is easy to remember."
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
          {pending ? 'Creating your account…' : 'Create account'}
        </button>
        {slow && <WakingNotice />}
      </form>
      <p className="mt-6 text-sm text-muted">
        Already have an account?{' '}
        <Link to="/login" state={from} className="font-medium text-elephant hover:underline">
          Log in
        </Link>
      </p>
    </AuthCard>
  );
}
