import { describe, expect, it, vi } from 'vitest';
import { fakeApi, fixtures } from '../test/fakeApi.tsx';
import { ApiError, diff, plan } from './client.ts';

describe('API client', () => {
  it('posts to the API and returns its answer', async () => {
    const sent = fakeApi();
    const result = await diff('CREATE TABLE a (id int);', 'CREATE TABLE b (id int);');
    expect(result.significant).toBe(fixtures.diff.significant);
    expect(sent).toEqual([
      {
        method: 'POST',
        path: '/api/v1/diff',
        body: { before: 'CREATE TABLE a (id int);', after: 'CREATE TABLE b (id int);' },
        token: null,
        authorization: null,
      },
    ]);
  });

  it('sends the route and the confirmed renames to /plan', async () => {
    const sent = fakeApi();
    const rename = { kind: 'column' as const, table: { schema: 'public', name: 'customers' }, from: 'name', to: 'full_name' };
    await plan({ before: 'x', after: 'y', route: 'SAFE', renames: [rename] });
    expect(sent[0]?.body).toEqual({ before: 'x', after: 'y', route: 'SAFE', renames: [rename] });
  });

  it('turns a problem response into an ApiError with the SQL diagnostics', async () => {
    fakeApi({ invalidSchema: true });
    const error: unknown = await diff('CREATE TABLE t (id int;', 'x').catch((cause: unknown) => cause);
    expect(error).toBeInstanceOf(ApiError);
    const apiError = error as ApiError;
    expect(apiError.status).toBe(422);
    expect(apiError.message).toBe('The before schema has 1 error and cannot be analysed.');
    expect(apiError.problem.diagnostics?.[0]).toMatchObject({ input: 'before', line: 1, column: 23 });
  });

  it('reports an unreachable server as status 0', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new TypeError('Failed to fetch'))));
    const error = (await diff('a', 'b').catch((cause: unknown) => cause)) as ApiError;
    expect(error.status).toBe(0);
    expect(error.problem.title).toBe('Cannot reach the SchemaGuard API');
    expect(error.message).toContain(window.location.origin);
  });
});
