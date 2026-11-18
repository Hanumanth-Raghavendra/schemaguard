import { vi } from 'vitest';
import diff from './fixtures/diff.json';
import invalid from './fixtures/invalid.json';
import lint from './fixtures/lint.json';
import plan from './fixtures/plan.json';
import planDirect from './fixtures/planDirect.json';
import planRenamed from './fixtures/planRenamed.json';
import report from './fixtures/report.json';

/** Real API answers for the example (recorded from a local server), served by a fake fetch. */
export const fixtures = { diff, invalid, lint, plan, planDirect, planRenamed, report };

export interface Sent {
  path: string;
  body: unknown;
}

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  });
}

/** Replaces fetch with one that answers like the API; returns what was sent. */
export function fakeApi(options: { invalidSchema?: boolean } = {}): Sent[] {
  const sent: Sent[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: Request) => {
      const path = new URL(input.url).pathname;
      const body: unknown = await input.json();
      sent.push({ path, body });
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
        default:
          return json(404, { title: 'Not found' });
      }
    }),
  );
  return sent;
}
