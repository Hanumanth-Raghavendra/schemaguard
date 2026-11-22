import { Link, Route, Routes, useLocation, useParams } from 'react-router';
import { AccountPage } from './AccountPage.tsx';
import { AuthProvider } from './auth/AuthProvider.tsx';
import { AccountNav } from './components/AccountNav.tsx';
import { Home } from './Home.tsx';
import { LoginPage } from './LoginPage.tsx';
import { isPreset } from './presets.ts';
import { SharedAnalysis } from './SharedAnalysis.tsx';
import { SignupPage } from './SignupPage.tsx';

function HomeRoute() {
  const location = useLocation();
  // A new key on every navigation, so inputs handed over in the state start a fresh page.
  return <Home key={location.key} preset={isPreset(location.state) ? location.state : null} />;
}

function SharedRoute() {
  const { id = '' } = useParams();
  return <SharedAnalysis key={id} />;
}

function NotFound() {
  return (
    <div className="space-y-3">
      <p className="font-semibold">There is no page at this address.</p>
      <Link to="/" className="text-elephant hover:underline">
        Start a new analysis
      </Link>
    </div>
  );
}

export function App() {
  return (
    <AuthProvider>
      <Layout />
    </AuthProvider>
  );
}

function Layout() {
  return (
    <div className="mx-auto max-w-6xl px-4 pb-16 sm:px-6">
      <header className="flex flex-wrap items-start justify-between gap-x-6 gap-y-3 py-6">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">
            <Link to="/" className="flex w-fit items-center gap-2">
              <img src="/favicon.svg" alt="" className="h-7 w-7" />
              SchemaGuard
            </Link>
          </h1>
          <p className="mt-1 max-w-xl text-muted">
            See what a PostgreSQL schema change locks before it ships, and get a migration that keeps the database
            online.
          </p>
        </div>
        <AccountNav />
      </header>
      <main>
        <Routes>
          <Route path="/" element={<HomeRoute />} />
          <Route path="/a/:id" element={<SharedRoute />} />
          <Route path="/login" element={<LoginPage />} />
          <Route path="/signup" element={<SignupPage />} />
          <Route path="/account" element={<AccountPage />} />
          <Route path="*" element={<NotFound />} />
        </Routes>
      </main>
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
