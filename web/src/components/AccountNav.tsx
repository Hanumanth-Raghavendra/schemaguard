import { Link, useLocation, useNavigate } from 'react-router';
import { useAuth } from '../auth/AuthContext.ts';

/** The header's account links: log in and sign up, or the account and log out. */
export function AccountNav() {
  const auth = useAuth();
  const location = useLocation();
  const navigate = useNavigate();

  // Nothing while a session is being renewed, rather than links that change a moment later.
  if (auth.status === 'checking') return <div className="h-9" aria-hidden="true" />;

  if (auth.status === 'anonymous' || auth.account === null) {
    return (
      <nav aria-label="Account" className="-ml-3 flex items-center gap-1 text-sm sm:ml-0">
        <Link to="/login" className="rounded-md px-3 py-1.5 font-medium text-elephant hover:underline">
          Log in
        </Link>
        <Link
          to="/signup"
          className="rounded-md border border-line bg-surface px-3 py-1.5 font-medium hover:border-elephant"
        >
          Sign up
        </Link>
      </nav>
    );
  }

  async function logOut() {
    await auth.logOut();
    // Leave the account page; anywhere else, stay (an analysis in progress keeps its inputs).
    if (location.pathname === '/account') void navigate('/', { replace: true });
  }

  return (
    <nav aria-label="Account" className="-ml-3 flex min-w-0 items-center gap-1 text-sm sm:ml-0">
      <Link to="/projects" className="rounded-md px-3 py-1.5 font-medium text-elephant hover:underline">
        Projects
      </Link>
      <Link
        to="/account"
        title={auth.account.email}
        className="max-w-[14rem] truncate rounded-md px-3 py-1.5 font-medium text-elephant hover:underline"
      >
        {auth.account.email}
      </Link>
      <button
        type="button"
        className="rounded-md border border-line bg-surface px-3 py-1.5 font-medium hover:border-elephant"
        onClick={() => void logOut()}
      >
        Log out
      </button>
    </nav>
  );
}
