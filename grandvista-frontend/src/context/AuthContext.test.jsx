import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiRequest } from '../api/client';
import { tokenStorage } from '../api/tokenStorage';
import { authApi } from '../api/endpoints';
import { LocationProbe, makeJwt } from '../test/helpers';
import { AuthProvider, useAuth } from './AuthContext';

vi.mock('../api/endpoints', () => ({ authApi: { login: vi.fn(), demoLogin: vi.fn() } }));

function Probe() {
  const auth = useAuth();
  return (
    <div>
      <span data-testid="authenticated">{String(auth.isAuthenticated)}</span>
      <span data-testid="role">{auth.user?.role ?? 'none'}</span>
      <span data-testid="notice">{auth.notice ?? ''}</span>
      <button type="button" onClick={() => auth.login('asha@example.com', 'pw').catch(() => {})}>login</button>
      <button type="button" onClick={() => auth.demoLogin('manager').catch(() => {})}>demo</button>
      <button type="button" onClick={() => auth.logout()}>logout</button>
    </div>
  );
}

const renderProvider = () => render(
  <MemoryRouter initialEntries={['/']}>
    <AuthProvider>
      <Routes>
        <Route path="/" element={<Probe />} />
        <Route path="*" element={<><LocationProbe /><Probe /></>} />
      </Routes>
    </AuthProvider>
  </MemoryRouter>,
);

describe('AuthProvider', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn());
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  it('starts logged out', () => {
    renderProvider();
    expect(screen.getByTestId('authenticated')).toHaveTextContent('false');
  });

  it('restores a valid stored session', () => {
    tokenStorage.save(makeJwt(), { name: 'Asha', role: 'ROLE_USER' });
    renderProvider();
    expect(screen.getByTestId('authenticated')).toHaveTextContent('true');
    expect(screen.getByTestId('role')).toHaveTextContent('ROLE_USER');
  });

  it('discards a stored session whose token has already expired', () => {
    tokenStorage.save(makeJwt({ exp: Math.floor(Date.now() / 1000) - 60 }), { name: 'Asha', role: 'ROLE_USER' });
    renderProvider();
    expect(screen.getByTestId('authenticated')).toHaveTextContent('false');
    expect(tokenStorage.getToken()).toBeNull();
  });

  it('stores the token and profile after a successful login', async () => {
    authApi.login.mockResolvedValue({ accessToken: 'new-token', user: { name: 'Asha', role: 'ROLE_ADMIN' } });
    renderProvider();

    await userEvent.click(screen.getByText('login'));

    expect(await screen.findByText('true')).toBeInTheDocument();
    expect(screen.getByTestId('role')).toHaveTextContent('ROLE_ADMIN');
    expect(tokenStorage.getToken()).toBe('new-token');
  });

  it('stores the session after a demo login without sending any password', async () => {
    authApi.demoLogin.mockResolvedValue({ accessToken: 'demo-token', user: { name: 'GrandVista Manager', role: 'ROLE_ADMIN' } });
    renderProvider();

    await userEvent.click(screen.getByText('demo'));

    expect(await screen.findByText('true')).toBeInTheDocument();
    expect(authApi.demoLogin).toHaveBeenCalledWith('manager');
    expect(screen.getByTestId('role')).toHaveTextContent('ROLE_ADMIN');
    expect(tokenStorage.getToken()).toBe('demo-token');
  });

  it('stays logged out when the demo login is rejected', async () => {
    authApi.demoLogin.mockImplementation(() => Promise.reject(new Error('Demo login is disabled on this server')));
    renderProvider();

    await userEvent.click(screen.getByText('demo'));

    expect(screen.getByTestId('authenticated')).toHaveTextContent('false');
    expect(tokenStorage.getToken()).toBeNull();
  });

  it('stays logged out when the login is rejected', async () => {
    authApi.login.mockImplementation(() => Promise.reject(new Error('Invalid email or password')));
    renderProvider();

    await userEvent.click(screen.getByText('login'));

    expect(screen.getByTestId('authenticated')).toHaveTextContent('false');
    expect(tokenStorage.getToken()).toBeNull();
  });

  it('logs out, explains why and returns to /login when the API answers 401', async () => {
    tokenStorage.save(makeJwt(), { name: 'Asha', role: 'ROLE_USER' });
    fetch.mockResolvedValue({ ok: false, status: 401, json: () => Promise.resolve({ message: 'Token has expired' }) });
    renderProvider();

    await act(async () => { await apiRequest('/bookings/my').catch(() => {}); });

    expect(screen.getByTestId('path')).toHaveTextContent('/login');
    expect(screen.getByTestId('notice')).toHaveTextContent('Token has expired');
    expect(screen.getByTestId('authenticated')).toHaveTextContent('false');
    expect(tokenStorage.getToken()).toBeNull();
  });

  it('logs out by itself when the token reaches its expiry time', () => {
    vi.useFakeTimers();
    tokenStorage.save(makeJwt({ exp: Math.floor(Date.now() / 1000) + 5 }), { name: 'Asha', role: 'ROLE_USER' });
    renderProvider();
    expect(screen.getByTestId('authenticated')).toHaveTextContent('true');

    act(() => { vi.advanceTimersByTime(6000); });

    expect(screen.getByTestId('authenticated')).toHaveTextContent('false');
    expect(screen.getByTestId('notice')).toHaveTextContent('session has expired');
  });

  it('explicit logout clears the session without a notice', async () => {
    tokenStorage.save(makeJwt(), { name: 'Asha', role: 'ROLE_USER' });
    renderProvider();

    await userEvent.click(screen.getByText('logout'));

    expect(screen.getByTestId('authenticated')).toHaveTextContent('false');
    expect(tokenStorage.getToken()).toBeNull();
    expect(screen.getByTestId('notice')).toHaveTextContent('');
  });
});
