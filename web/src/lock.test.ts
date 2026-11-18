import { describe, expect, it } from 'vitest';
import { describeWork, lockLevel, sentenceCase, traffic } from './lock.ts';

describe('lock scale', () => {
  it('ranks the eight PostgreSQL lock modes', () => {
    expect(lockLevel('ACCESS SHARE')).toBe(1);
    expect(lockLevel('SHARE UPDATE EXCLUSIVE')).toBe(4);
    expect(lockLevel('SHARE')).toBe(5);
    expect(lockLevel('ACCESS EXCLUSIVE')).toBe(8);
    expect(lockLevel(null)).toBe(0);
    expect(lockLevel('SOMETHING ELSE')).toBe(0);
  });

  it('says what ordinary traffic does under each lock', () => {
    expect(traffic(lockLevel('ROW EXCLUSIVE'))).toBe('flows');
    expect(traffic(lockLevel('SHARE UPDATE EXCLUSIVE'))).toBe('flows');
    expect(traffic(lockLevel('SHARE'))).toBe('writes-wait');
    expect(traffic(lockLevel('EXCLUSIVE'))).toBe('writes-wait');
    expect(traffic(lockLevel('ACCESS EXCLUSIVE'))).toBe('all-wait');
  });

  it('words table work and levels for people', () => {
    expect(describeWork('REWRITE')).toBe('Rewrites the table');
    expect(describeWork('NEW')).toBe('NEW');
    expect(sentenceCase('CRITICAL')).toBe('Critical');
    expect(sentenceCase('NOT_SCHEMA')).toBe('Not schema');
  });
});
