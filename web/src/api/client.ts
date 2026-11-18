import createClient from 'openapi-fetch';
import type { components, paths } from './schema';

type Schemas = components['schemas'];
export type DiffResponse = Schemas['DiffResponse'];
export type ReportResponse = Schemas['ReportResponse'];
export type PlanResponse = Schemas['PlanResponse'];
export type LintResponse = Schemas['LintResponse'];
export type Change = Schemas['Change'];
export type Finding = Schemas['Finding'];
export type Operation = Schemas['Operation'];
export type Diagnostic = Schemas['Diagnostic'];
export type Score = Schemas['Score'];
export type Step = Schemas['Step'];
export type LintedStatement = Schemas['LintedStatement'];
export type RenameDto = Schemas['RenameDto'];
export type Route = 'DIRECT' | 'SAFE';

/** RFC 9457 problem as the API sends it; `code` and `diagnostics` are its extension members. */
export type Problem = Schemas['ProblemDetail'] & { code?: string; diagnostics?: Diagnostic[] };

export class ApiError extends Error {
  readonly status: number;
  readonly problem: Problem;

  constructor(status: number, problem: Problem) {
    super(problem.detail ?? problem.title ?? `Request failed with status ${status}`);
    this.name = 'ApiError';
    this.status = status;
    this.problem = problem;
  }
}

/** The live API for a production build, a local one in development; VITE_API_URL overrides both. */
export const API_URL: string =
  import.meta.env.VITE_API_URL ??
  (import.meta.env.PROD ? 'https://schemaguard-api-c00y.onrender.com' : 'http://localhost:8080');

const client = createClient<paths>({
  baseUrl: API_URL,
  // Look fetch up on every call, so tests can replace it.
  fetch: (request) => globalThis.fetch(request),
});

function asProblem(error: unknown, status: number): Problem {
  if (error !== null && typeof error === 'object') return error;
  const problem: Problem = { status, title: 'Unexpected answer from the API' };
  if (typeof error === 'string' && error !== '') problem.detail = error;
  return problem;
}

async function call<T>(request: () => Promise<{ data?: T; error?: unknown; response: Response }>): Promise<T> {
  let result: { data?: T; error?: unknown; response: Response };
  try {
    result = await request();
  } catch (cause) {
    if (cause instanceof DOMException && cause.name === 'AbortError') throw cause;
    throw new ApiError(0, {
      title: 'Cannot reach the SchemaGuard API',
      detail: `No answer from ${API_URL}. Check your connection and try again.`,
    });
  }
  const { data, error, response } = result;
  if (!response.ok || data === undefined) throw new ApiError(response.status, asProblem(error, response.status));
  return data;
}

export function diff(before: string, after: string, signal?: AbortSignal): Promise<DiffResponse> {
  return call(() => client.POST('/api/v1/diff', { body: { before, after }, signal: signal ?? null }));
}

export function report(before: string, after: string, signal?: AbortSignal): Promise<ReportResponse> {
  return call(() => client.POST('/api/v1/report', { body: { before, after }, signal: signal ?? null }));
}

export interface PlanInput {
  before: string;
  after: string;
  route: Route;
  renames: RenameDto[];
}

export function plan(input: PlanInput, signal?: AbortSignal): Promise<PlanResponse> {
  return call(() => client.POST('/api/v1/plan', { body: input, signal: signal ?? null }));
}

export function lint(schema: string, migration: string, signal?: AbortSignal): Promise<LintResponse> {
  return call(() => client.POST('/api/v1/lint', { body: { schema, migration }, signal: signal ?? null }));
}

/** Starts the sleeping API host early. The answer is opaque (no CORS on /actuator) and not needed. */
export function wakeApi(): void {
  void globalThis.fetch(`${API_URL}/actuator/health/liveness`, { mode: 'no-cors' }).catch(() => undefined);
}
