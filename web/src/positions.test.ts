import { describe, expect, it } from 'vitest';
import { owner } from './positions.ts';
import { splitReason } from './reasons.ts';

describe('owner', () => {
  const statements = [
    { line: 1, column: 1 },
    { line: 1, column: 40 },
    { line: 3, column: 1 },
  ];

  it('finds the statement a position falls in, also on a shared line', () => {
    expect(owner(statements, { line: 1, column: 10 })).toBe(0);
    expect(owner(statements, { line: 1, column: 40 })).toBe(1);
    expect(owner(statements, { line: 2, column: 5 })).toBe(1);
    expect(owner(statements, { line: 9, column: 1 })).toBe(2);
  });

  it('returns -1 before the first statement', () => {
    expect(owner([{ line: 2, column: 1 }], { line: 1, column: 1 })).toBe(-1);
  });
});

describe('splitReason', () => {
  it('splits points, rule and title', () => {
    expect(splitReason('85 SG-R302 Column is dropped with all its values')).toEqual({
      points: '85',
      rule: 'SG-R302',
      title: 'Column is dropped with all its values',
    });
  });

  it('keeps an unexpected reason whole', () => {
    expect(splitReason('Something else')).toEqual({ points: null, rule: null, title: 'Something else' });
  });
});
