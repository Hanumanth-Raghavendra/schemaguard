import { describe, expect, it } from 'vitest';
import { compareMarkdown, lintMarkdown } from './export.ts';
import { isPreset } from './presets.ts';
import { fixtures } from './test/fakeApi.tsx';

describe('Markdown export', () => {
  it('writes the report with the worst risk first, the changes and the plan', () => {
    const markdown = compareMarkdown({ report: fixtures.report, diff: fixtures.diff, plan: fixtures.plan });
    const first = markdown.indexOf('### Critical: Column is dropped with all its values (SG-R302)');
    expect(first).toBeGreaterThan(0);
    expect(markdown.indexOf('### Low:')).toBeGreaterThan(first);
    expect(markdown).toContain('- `~ column public.orders.id: type integer -> bigint`');
    expect(markdown).toContain('```sql\n');
    expect(markdown).toContain('Why: ');
    expect(markdown).toContain('Instead: ');
  });

  it('writes a migration check statement by statement with its errors', () => {
    const lint = fixtures.lint;
    const markdown = lintMarkdown(lint);
    expect(markdown.match(/^## Line \d+$/gm)).toHaveLength(lint.statements.length);
    expect(markdown).toContain('Status: Would fail');
    expect(markdown).toContain('Error: relation "missing" does not exist');
  });
});

describe('isPreset', () => {
  it('accepts only complete inputs for a known mode', () => {
    expect(isPreset({ mode: 'compare', before: 'a', after: 'b' })).toBe(true);
    expect(isPreset({ mode: 'lint', schema: 'a', migration: 'b' })).toBe(true);
    expect(isPreset({ mode: 'lint', schema: 'a' })).toBe(false);
    expect(isPreset({ mode: 'other' })).toBe(false);
    expect(isPreset(null)).toBe(false);
  });
});
