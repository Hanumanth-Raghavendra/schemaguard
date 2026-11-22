import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { vi } from 'vitest';
import type { DiffResponse, LintResponse, ModelResponse, PlanResponse, ReportResponse } from '../api/client.ts';
import { App } from '../App.tsx';
import account from './fixtures/account.json';
import diff from './fixtures/diff.json';
import emailTaken from './fixtures/emailTaken.json';
import gone from './fixtures/gone.json';
import invalid from './fixtures/invalid.json';
import lint from './fixtures/lint.json';
import locked from './fixtures/locked.json';
import model from './fixtures/model.json';
import plan from './fixtures/plan.json';
import planDirect from './fixtures/planDirect.json';
import planRenamed from './fixtures/planRenamed.json';
import report from './fixtures/report.json';
import saved from './fixtures/saved.json';
import savedLint from './fixtures/savedLint.json';
import session from './fixtures/session.json';
import sessionEnded from './fixtures/sessionEnded.json';
import stored from './fixtures/stored.json';
import storedLint from './fixtures/storedLint.json';
import tokenRejected from './fixtures/tokenRejected.json';
import weakPassword from './fixtures/weakPassword.json';
import wrongLogin from './fixtures/wrongLogin.json';
import wrongPassword from './fixtures/wrongPassword.json';
import wrongToken from './fixtures/wrongToken.json';

/** Real API answers for the example (recorded from a local server), served by a fake fetch. */
export const fixtures = {
  diff: diff as DiffResponse,
  gone,
  invalid,
  lint: lint as LintResponse,
  model: model as ModelResponse,
  plan: plan as PlanResponse,
  planDirect: planDirect as PlanResponse,
  planRenamed: planRenamed as PlanResponse,
  report: report as ReportResponse,
  saved,
  savedLint,
  stored,
  storedLint,
  session,
  account,
};

/** The account the fake API knows, with the password it accepts. */
export const ACCOUNT = { email: account.email, password: 'correct horse battery staple' };

export interface Sent {
  method: string;
  path: string;
  body: unknown;
  token: string | null;
  authorization: string | null;
}

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  });
}

const SHARING_OFF = {
  type: 'about:blank',
  title: 'Service Unavailable',
  status: 503,
  detail: 'Saved analyses are switched off on this server.',
  code: 'SG-API-503',
};

function analyses(method: string, path: string, token: string | null, sharingOff: boolean): Response {
  if (sharingOff) return json(503, SHARING_OFF);
  if (method === 'POST') return json(201, path.endsWith('/lint') ? savedLint : saved);
  const id = path.slice('/api/v1/analyses/'.length);
  const found = id === stored.id ? stored : id === storedLint.id ? storedLint : null;
  if (found === null) return json(404, gone);
  if (method === 'GET') return json(200, found);
  if (token !== saved.deleteToken) return json(403, wrongToken);
  return new Response(null, { status: 204 });
}

interface FakeOptions {
  invalidSchema?: boolean;
  sharingOff?: boolean;
  /** The browser holds a refresh cookie the server accepts. */
  loggedIn?: boolean;
  /** The first call with an access token is refused as expired, as after the token's 15 minutes. */
  expireFirstToken?: boolean;
}

/** The account endpoints, with the refresh cookie modelled as a flag the server holds. */
function accounts(
  method: string,
  path: string,
  body: unknown,
  authorization: string | null,
  server: { session: boolean; expire: boolean },
) {
  const request = (body ?? {}) as { email?: string; password?: string };
  const start = (status: number) => {
    server.session = true;
    return json(status, session);
  };
  switch (`${method} ${path}`) {
    case 'POST /api/v1/auth/signup':
      if (request.email === ACCOUNT.email) return json(409, emailTaken);
      if (request.password === 'iloveyou') return json(422, weakPassword);
      return start(201);
    case 'POST /api/v1/auth/login':
      if (request.email === 'locked@example.com') return json(429, locked);
      if (request.email !== ACCOUNT.email || request.password !== ACCOUNT.password) return json(401, wrongLogin);
      return start(200);
    case 'POST /api/v1/auth/refresh':
      return server.session ? start(200) : json(401, sessionEnded);
    case 'POST /api/v1/auth/logout':
      server.session = false;
      return new Response(null, { status: 204 });
  }
  if (authorization !== `Bearer ${session.accessToken}` || server.expire) {
    server.expire = false;
    return json(401, tokenRejected);
  }
  if (method === 'GET') return json(200, account);
  if (request.password !== ACCOUNT.password) return json(403, wrongPassword);
  server.session = false;
  return new Response(null, { status: 204 });
}

/** Replaces fetch with one that answers like the API; returns what was sent. */
export function fakeApi(options: FakeOptions = {}): Sent[] {
  const sent: Sent[] = [];
  const server = { session: options.loggedIn ?? false, expire: options.expireFirstToken ?? false };
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: Request) => {
      const path = new URL(input.url).pathname;
      const method = input.method;
      const text = method === 'GET' ? '' : await input.text();
      const body: unknown = text === '' ? null : JSON.parse(text);
      const token = input.headers.get('X-Delete-Token');
      const authorization = input.headers.get('Authorization');
      sent.push({ method, path, body, token, authorization });
      if (path.startsWith('/api/v1/auth/') || path === '/api/v1/account') {
        return accounts(method, path, body, authorization, server);
      }
      if (path.startsWith('/api/v1/analyses/')) return analyses(method, path, token, options.sharingOff ?? false);
      if (options.invalidSchema) return json(422, invalid);
      const request = body as { route?: string; renames?: unknown[] };
      switch (path) {
        case '/api/v1/diff':
          return json(200, diff);
        case '/api/v1/report':
          return json(200, report);
        case '/api/v1/plan':
          if (request.route === 'DIRECT') return json(200, planDirect);
          return json(200, (request.renames ?? []).length > 0 ? planRenamed : plan);
        case '/api/v1/lint':
          return json(200, lint);
        case '/api/v1/model':
          return json(200, model);
        default:
          return json(404, { title: 'Not found' });
      }
    }),
  );
  return sent;
}

/** The app inside a router at the given address, as the browser would show it. */
export function renderApp(path = '/') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <App />
    </MemoryRouter>,
  );
}
