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
export type ModelResponse = Schemas['ModelResponse'];
export type ModelTable = Schemas['ModelTable'];
export type ModelColumn = Schemas['ModelColumn'];
export type ModelReference = Schemas['ModelReference'];
export type SavedAnalysis = Schemas['SavedAnalysis'];
export type StoredAnalysis = Schemas['StoredAnalysis'];
export type Session = Schemas['Session'];
export type Account = Schemas['Account'];
export type ProjectSummary = Schemas['ProjectSummary'];
export type Project = Schemas['Project'];
export type VersionSummary = Schemas['VersionSummary'];
export type Version = Schemas['Version'];
export type VersionRisk = Schemas['VersionRisk'];
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

/**
 * Where the API is. By default the page's own origin: Vercel forwards /api/* to the API in production
 * and Vite does the same in development and preview, so the browser only ever talks to one site
 * (first-party cookies, no CORS; D-090). VITE_API_URL points the app straight at another API.
 */
export const API_URL: string = import.meta.env.VITE_API_URL ?? '';

/** The API host itself, pinged directly to wake it (only the free production host sleeps). */
const WAKE_URL: string | null = import.meta.env.PROD
  ? 'https://schemaguard-api-c00y.onrender.com/actuator/health/liveness'
  : null;

function base(): string {
  return API_URL === '' ? window.location.origin : API_URL;
}

const client = createClient<paths>({
  baseUrl: base(),
  // Look fetch up on every call, so tests can replace it.
  fetch: (request) => globalThis.fetch(request),
});

function asProblem(error: unknown, status: number): Problem {
  if (error !== null && typeof error === 'object') return error;
  const problem: Problem = { status, title: 'Unexpected answer from the API' };
  if (typeof error === 'string' && error !== '') problem.detail = error;
  return problem;
}

interface Answer<T> {
  data?: T;
  error?: unknown;
  response: Response;
}

/** Sends a request; a network failure becomes an ApiError with status 0, an error status an ApiError. */
async function send<T>(request: () => Promise<Answer<T>>): Promise<Answer<T>> {
  let answer: Answer<T>;
  try {
    answer = await request();
  } catch (cause) {
    if (cause instanceof DOMException && cause.name === 'AbortError') throw cause;
    throw new ApiError(0, {
      title: 'Cannot reach the SchemaGuard API',
      detail: `No answer from ${base()}. Check your connection and try again.`,
    });
  }
  if (!answer.response.ok) throw new ApiError(answer.response.status, asProblem(answer.error, answer.response.status));
  return answer;
}

async function call<T>(request: () => Promise<Answer<T>>): Promise<T> {
  const { data, response } = await send(request);
  if (data === undefined) throw new ApiError(response.status, asProblem(undefined, response.status));
  return data;
}

export function diff(before: string, after: string, signal?: AbortSignal): Promise<DiffResponse> {
  return call(() => client.POST('/api/v1/diff', { body: { before, after }, signal: signal ?? null }));
}

export function report(before: string, after: string, signal?: AbortSignal): Promise<ReportResponse> {
  return call(() => client.POST('/api/v1/report', { body: { before, after }, signal: signal ?? null }));
}

/** Both schemas as one entity-relationship model, for the diagram. */
export function model(before: string, after: string, signal?: AbortSignal): Promise<ModelResponse> {
  return call(() => client.POST('/api/v1/model', { body: { before, after }, signal: signal ?? null }));
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

/** Saves a risk report so it can be shared; the answer holds the id and the delete token. */
export function saveReport(before: string, after: string): Promise<SavedAnalysis> {
  return call(() => client.POST('/api/v1/analyses/report', { body: { before, after } }));
}

/** Saves a migration check so it can be shared. */
export function saveLint(schema: string, migration: string): Promise<SavedAnalysis> {
  return call(() => client.POST('/api/v1/analyses/lint', { body: { schema, migration } }));
}

export function openAnalysis(id: string, signal?: AbortSignal): Promise<StoredAnalysis> {
  return call(() => client.GET('/api/v1/analyses/{id}', { params: { path: { id } }, signal: signal ?? null }));
}

export async function deleteAnalysis(id: string, token: string): Promise<void> {
  await send(() =>
    client.DELETE('/api/v1/analyses/{id}', { params: { path: { id }, header: { 'X-Delete-Token': token } } }),
  );
}

// Accounts. The refresh token is an httpOnly cookie the browser sends to /api/v1/auth by itself
// (same origin, D-090); the access token comes back in the JSON and is kept in memory only.

/** Creates an account; the answer is a logged-in session. */
export function signUp(email: string, password: string): Promise<Session> {
  return call(() => client.POST('/api/v1/auth/signup', { body: { email, password } }));
}

export function logIn(email: string, password: string): Promise<Session> {
  return call(() => client.POST('/api/v1/auth/login', { body: { email, password } }));
}

/** Exchanges the refresh cookie for a new session; 401 when there is none. */
export function refreshSession(): Promise<Session> {
  return call(() => client.POST('/api/v1/auth/refresh'));
}

export async function logOut(): Promise<void> {
  await send(() => client.POST('/api/v1/auth/logout'));
}

function bearer(accessToken: string): { Authorization: string } {
  return { Authorization: `Bearer ${accessToken}` };
}

export function getAccount(accessToken: string, signal?: AbortSignal): Promise<Account> {
  return call(() => client.GET('/api/v1/account', { headers: bearer(accessToken), signal: signal ?? null }));
}

export async function deleteAccount(accessToken: string, password: string): Promise<void> {
  await send(() => client.DELETE('/api/v1/account', { headers: bearer(accessToken), body: { password } }));
}

// Projects. Every call needs an access token; pass these through useAuth().withToken.

export function listProjects(accessToken: string, signal?: AbortSignal): Promise<ProjectSummary[]> {
  return call(() => client.GET('/api/v1/projects', { headers: bearer(accessToken), signal: signal ?? null }));
}

export function createProject(accessToken: string, name: string): Promise<Project> {
  return call(() => client.POST('/api/v1/projects', { headers: bearer(accessToken), body: { name } }));
}

export function getProject(accessToken: string, id: string, signal?: AbortSignal): Promise<Project> {
  return call(() =>
    client.GET('/api/v1/projects/{id}', {
      headers: bearer(accessToken),
      params: { path: { id } },
      signal: signal ?? null,
    }),
  );
}

export function renameProject(accessToken: string, id: string, name: string): Promise<Project> {
  return call(() =>
    client.PATCH('/api/v1/projects/{id}', { headers: bearer(accessToken), params: { path: { id } }, body: { name } }),
  );
}

export async function deleteProject(accessToken: string, id: string): Promise<void> {
  await send(() => client.DELETE('/api/v1/projects/{id}', { headers: bearer(accessToken), params: { path: { id } } }));
}

/** Saves a schema as the project's next version; a blank label means none. */
export function addVersion(accessToken: string, id: string, sql: string, label: string): Promise<VersionSummary> {
  const body = label.trim() === '' ? { sql } : { sql, label };
  return call(() =>
    client.POST('/api/v1/projects/{id}/versions', { headers: bearer(accessToken), params: { path: { id } }, body }),
  );
}

export function getVersion(accessToken: string, id: string, number: number, signal?: AbortSignal): Promise<Version> {
  return call(() =>
    client.GET('/api/v1/projects/{id}/versions/{number}', {
      headers: bearer(accessToken),
      params: { path: { id, number } },
      signal: signal ?? null,
    }),
  );
}

export async function deleteVersion(accessToken: string, id: string, number: number): Promise<void> {
  await send(() =>
    client.DELETE('/api/v1/projects/{id}/versions/{number}', {
      headers: bearer(accessToken),
      params: { path: { id, number } },
    }),
  );
}

/** Starts the sleeping API host early. The answer is opaque (no CORS on /actuator) and not needed. */
export function wakeApi(): void {
  if (WAKE_URL === null) return;
  void globalThis.fetch(WAKE_URL, { mode: 'no-cors' }).catch(() => undefined);
}
