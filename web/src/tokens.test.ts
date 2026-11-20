import { afterEach, describe, expect, it, vi } from 'vitest';
import { forgetToken, rememberToken, tokenFor } from './tokens.ts';

const LATER = '2999-01-01T00:00:00Z';

describe('delete tokens', () => {
  afterEach(() => {
    localStorage.clear();
  });

  it('remembers and forgets the token of each shared analysis', () => {
    rememberToken('a', 'token-a', LATER);
    rememberToken('b', 'token-b', LATER);
    expect(tokenFor('a')).toBe('token-a');
    forgetToken('a');
    expect(tokenFor('a')).toBeNull();
    expect(tokenFor('b')).toBe('token-b');
  });

  it('drops tokens of expired links and ignores damaged storage', () => {
    rememberToken('old', 'token-old', '2000-01-01T00:00:00Z');
    expect(tokenFor('old')).toBeNull();
    localStorage.setItem('schemaguard.deleteTokens', '{not json');
    expect(tokenFor('a')).toBeNull();
    rememberToken('a', 'token-a', LATER);
    expect(tokenFor('a')).toBe('token-a');
  });

  it('keeps working when storage refuses to be used', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    expect(() => {
      rememberToken('a', 'token-a', LATER);
    }).not.toThrow();
    expect(tokenFor('a')).toBeNull();
  });
});
