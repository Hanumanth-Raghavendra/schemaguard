import type { ReactNode } from 'react';

/** The frame of the login and sign-up forms. */
export function AuthCard({ title, intro, children }: { title: string; intro?: string; children: ReactNode }) {
  return (
    <section
      className="mx-auto mt-4 max-w-sm rounded-md border border-line bg-surface p-6 sm:p-8"
      aria-labelledby="auth-title"
    >
      <h2 id="auth-title" className="text-xl font-bold tracking-tight">
        {title}
      </h2>
      {intro && <p className="mt-1 text-sm text-muted">{intro}</p>}
      <div className="mt-6">{children}</div>
    </section>
  );
}
