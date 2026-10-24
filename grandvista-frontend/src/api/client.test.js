import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { API_BASE_URL, ApiError, apiRequest, setUnauthorizedHandler } from './client';
import { tokenStorage } from './tokenStorage';

const reply = (status, body) => ({ ok: status >= 200 && status < 300, status, json: () => Promise.resolve(body) });

describe('apiRequest', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn());
    setUnauthorizedHandler(null);
  });
  afterEach(() => vi.unstubAllGlobals());

  it('sends requests to the API gateway base URL with the bearer token', async () => {
    tokenStorage.save('jwt-value', { id: 1 });
    fetch.mockResolvedValue(reply(200, { ok: true }));

    await apiRequest('/bookings/my');

    const [url, options] = fetch.mock.calls[0];
    expect(url.toString()).toBe(`${API_BASE_URL}/bookings/my`);
    expect(options.method).toBe('GET');
    expect(options.headers.Authorization).toBe('Bearer jwt-value');
  });

  it('omits the Authorization header when nobody is logged in', async () => {
    fetch.mockResolvedValue(reply(200, []));

    await apiRequest('/rooms');

    expect(fetch.mock.calls[0][1].headers.Authorization).toBeUndefined();
  });

  it('serialises JSON bodies and skips empty query parameters', async () => {
    fetch.mockResolvedValue(reply(201, { id: 9 }));

    const result = await apiRequest('/rooms', {
      method: 'POST',
      body: { roomNumber: '101' },
      query: { city: 'Chennai', guests: '', maxPrice: undefined, hotelId: null, roomType: 'SUITE' },
    });

    const [url, options] = fetch.mock.calls[0];
    expect(result).toEqual({ id: 9 });
    expect(options.headers['Content-Type']).toBe('application/json');
    expect(options.body).toBe('{"roomNumber":"101"}');
    expect([...url.searchParams.keys()]).toEqual(['city', 'roomType']);
  });

  it('returns null for 204 responses', async () => {
    fetch.mockResolvedValue({ ok: true, status: 204 });
    expect(await apiRequest('/rooms/1', { method: 'DELETE' })).toBeNull();
  });

  it('turns backend error bodies into ApiError with status, message and field errors', async () => {
    fetch.mockResolvedValue(reply(400, { message: 'Request validation failed', validationErrors: { email: 'must be valid' } }));

    const error = await apiRequest('/auth/register', { method: 'POST', body: {} }).catch((e) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect(error.status).toBe(400);
    expect(error.message).toBe('Request validation failed');
    expect(error.validationErrors).toEqual({ email: 'must be valid' });
  });

  it('falls back to a generic message when the error body is not JSON', async () => {
    fetch.mockResolvedValue({ ok: false, status: 502, json: () => Promise.reject(new Error('not json')) });

    const error = await apiRequest('/rooms').catch((e) => e);

    expect(error.message).toBe('Request failed with status 502');
  });

  it('reports an unreachable gateway as status 0', async () => {
    fetch.mockImplementation(() => Promise.reject(new TypeError('Failed to fetch')));

    const error = await apiRequest('/rooms').catch((e) => e);

    expect(error.status).toBe(0);
    expect(error.message).toContain('Cannot reach the API Gateway');
  });

  it('ends the session on a 401 for an authenticated request', async () => {
    const handler = vi.fn();
    setUnauthorizedHandler(handler);
    tokenStorage.save('expired-jwt', { id: 1 });
    fetch.mockResolvedValue(reply(401, { message: 'Token expired' }));

    await apiRequest('/bookings/my').catch(() => {});

    expect(handler).toHaveBeenCalledWith('Token expired');
  });

  it('does NOT treat a failed login (401 without a token) as an expired session', async () => {
    const handler = vi.fn();
    setUnauthorizedHandler(handler);
    fetch.mockResolvedValue(reply(401, { message: 'Invalid email or password' }));

    const error = await apiRequest('/auth/login', { method: 'POST', body: {} }).catch((e) => e);

    expect(error.message).toBe('Invalid email or password');
    expect(handler).not.toHaveBeenCalled();
  });

  it('does not log the user out on a 403', async () => {
    const handler = vi.fn();
    setUnauthorizedHandler(handler);
    tokenStorage.save('jwt', { id: 1 });
    fetch.mockResolvedValue(reply(403, { message: 'Forbidden' }));

    await apiRequest('/auth/users').catch(() => {});

    expect(handler).not.toHaveBeenCalled();
  });

  it('never sends a stored token to login or register', async () => {
    tokenStorage.save('stale-token', { id: 1 });
    fetch.mockResolvedValue(reply(200, {}));

    await apiRequest('/auth/login', { method: 'POST', body: {} });
    await apiRequest('/auth/register', { method: 'POST', body: {} });

    for (const [, options] of fetch.mock.calls) {
      expect(options.headers.Authorization).toBeUndefined();
    }
  });

  it('a failed login while an older session is still stored does not end that session (regression)', async () => {
    // e.g. a second tab on /login with a mistyped password: used to log the user out of every tab
    const handler = vi.fn();
    setUnauthorizedHandler(handler);
    tokenStorage.save('valid-session', { id: 1 });
    fetch.mockResolvedValue(reply(401, { message: 'Invalid email or password' }));

    const error = await apiRequest('/auth/login', { method: 'POST', body: {} }).catch((e) => e);

    expect(error.message).toBe('Invalid email or password');
    expect(handler).not.toHaveBeenCalled();
    expect(tokenStorage.getToken()).toBe('valid-session');
  });
});
