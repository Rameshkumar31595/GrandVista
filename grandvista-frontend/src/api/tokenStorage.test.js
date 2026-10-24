import { describe, expect, it } from 'vitest';
import { makeJwt } from '../test/helpers';
import { tokenExpiry, tokenStorage } from './tokenStorage';

describe('tokenStorage', () => {
  it('stores and clears the token and the user profile', () => {
    tokenStorage.save('abc', { name: 'Asha', role: 'ROLE_USER' });
    expect(tokenStorage.getToken()).toBe('abc');
    expect(tokenStorage.getUser()).toEqual({ name: 'Asha', role: 'ROLE_USER' });

    tokenStorage.clear();
    expect(tokenStorage.getToken()).toBeNull();
    expect(tokenStorage.getUser()).toBeNull();
  });

  it('survives a corrupted stored profile', () => {
    localStorage.setItem('grandvista.user', '{not json');
    expect(tokenStorage.getUser()).toBeNull();
  });

  it('reads the expiry claim in milliseconds without verifying the token', () => {
    expect(tokenExpiry(makeJwt({ exp: 2000000000 }))).toBe(2000000000 * 1000);
  });

  it('returns null for tokens without an expiry or that are not JWTs', () => {
    const noExp = `${btoa('{}')}.${btoa(JSON.stringify({ sub: '1' }))}.sig`;
    expect(tokenExpiry(noExp)).toBeNull();
    expect(tokenExpiry('garbage')).toBeNull();
  });
});
