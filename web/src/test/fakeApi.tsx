import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { vi } from 'vitest';
import type { DiffResponse, LintResponse, ModelResponse, PlanResponse, ReportResponse } from '../api/client.ts';
import { App } from '../App.tsx';
import diff from './fixtures/diff.json';
import gone from './fixtures/gone.json';
import invalid from './fixtures/invalid.json';
import lint from './fixtures/lint.json';
import model from './fixtures/model.json';
import plan from './fixtures/plan.json';
import planDirect from './fixtures/planDirect.json';
import planRenamed from './fixtures/planRenamed.json';
import report from './fixtures/report.json';
import saved from './fixtures/saved.json';
import savedLint from './fixtures/savedLint.json';
import stored from './fixtures/stored.json';
import storedLint from './fixtures/storedLint.json';
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
};

export interface Sent {
  method: string;
  path: string;
  body: unknown;
  token: string | null;
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

/** Replaces fetch with one that answers like the API; returns what was sent. */
export function fakeApi(options: { invalidSchema?: boolean; sharingOff?: boolean } = {}): Sent[] {
  const sent: Sent[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: Request) => {
      const path = new URL(input.url).pathname;
      const method = input.method;
      const body: unknown = method === 'POST' ? await input.json() : null;
      const token = input.headers.get('X-Delete-Token');
      sent.push({ method, path, body, token });
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
