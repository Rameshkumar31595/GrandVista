import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/client';
import { authApi } from '../api/endpoints';
import { useAuth } from '../context/AuthContext';
import { renderRoute } from '../test/helpers';
import LoginPage from './LoginPage';

vi.mock('../context/AuthContext', () => ({ useAuth: vi.fn() }));
vi.mock('../api/endpoints', () => ({ authApi: { demoAccounts: vi.fn() } }));

const login = vi.fn();
const clearNotice = vi.fn();
const demoLogin = vi.fn();

const DEMO_ACCOUNTS = [
  { id: 'ramesh', displayName: 'Ramesh', role: 'ROLE_USER', description: 'Search rooms, create bookings and test payments.' },
  { id: 'guest2', displayName: 'Demo Guest 2', role: 'ROLE_USER', description: 'A second guest.' },
  { id: 'manager', displayName: 'GrandVista Manager', role: 'ROLE_ADMIN', description: 'Manage hotels, rooms, pricing, bookings and users.' },
];

async function signIn(email = 'asha@example.com', password = 'Secret#123') {
  await userEvent.type(screen.getByLabelText('Email'), email);
  await userEvent.type(screen.getByLabelText('Password'), password);
  await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
}

describe('LoginPage', () => {
  beforeEach(() => {
    login.mockReset();
    demoLogin.mockReset();
    authApi.demoAccounts.mockReset();
    // the real server answers 403 when DEMO_MODE_ENABLED=false (GlobalExceptionHandler)
    authApi.demoAccounts.mockRejectedValue(new ApiError(403, 'Demo login is disabled on this server'));
    useAuth.mockReturnValue({ login, demoLogin, notice: null, clearNotice });
  });

  it('sends a guest to the room search after login', async () => {
    login.mockResolvedValue({ role: 'ROLE_USER' });
    renderRoute(<LoginPage />, { path: '/login' });

    await signIn();

    expect(login).toHaveBeenCalledWith('asha@example.com', 'Secret#123');
    await waitFor(() => expect(screen.getByTestId('path')).toHaveTextContent('/rooms'));
  });

  it('sends an administrator to the dashboard', async () => {
    login.mockResolvedValue({ role: 'ROLE_ADMIN' });
    renderRoute(<LoginPage />, { path: '/login' });

    await signIn('admin@grandvista.com', 'x');

    await waitFor(() => expect(screen.getByTestId('path')).toHaveTextContent('/admin'));
  });

  it('returns to the page the visitor originally asked for', async () => {
    login.mockResolvedValue({ role: 'ROLE_USER' });
    renderRoute(<LoginPage />, { path: '/login', entry: { pathname: '/login', state: { from: '/my-bookings' } } });

    await signIn();

    await waitFor(() => expect(screen.getByTestId('path')).toHaveTextContent('/my-bookings'));
  });

  it('shows the server message when the credentials are wrong and stays on the page', async () => {
    login.mockImplementation(() => Promise.reject(new Error('Invalid email or password')));
    renderRoute(<LoginPage />, { path: '/login' });

    await signIn('asha@example.com', 'wrong');

    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid email or password');
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeEnabled();
  });

  it('explains why the user was logged out', () => {
    useAuth.mockReturnValue({ login, demoLogin, notice: 'Your session has expired. Please log in again.', clearNotice });
    renderRoute(<LoginPage />, { path: '/login' });

    expect(screen.getByText('Your session has expired. Please log in again.')).toBeInTheDocument();
  });

  describe('demo login', () => {
    it('shows no demo option when the server has demo mode disabled', async () => {
      renderRoute(<LoginPage />, { path: '/login' });

      await waitFor(() => expect(authApi.demoAccounts).toHaveBeenCalled());
      expect(screen.queryByRole('tab', { name: 'Demo Login' })).not.toBeInTheDocument();
      expect(screen.getByLabelText('Email')).toBeInTheDocument();
    });

    it('offers Normal Login and Demo Login, keeping the password form by default', async () => {
      authApi.demoAccounts.mockResolvedValue(DEMO_ACCOUNTS);
      renderRoute(<LoginPage />, { path: '/login' });

      expect(await screen.findByRole('tab', { name: 'Demo Login' })).toBeInTheDocument();
      expect(screen.getByRole('tab', { name: 'Normal Login' })).toHaveAttribute('aria-selected', 'true');
      expect(screen.getByLabelText('Email')).toBeInTheDocument();
    });

    it('shows one card per account with its role and a Continue action', async () => {
      authApi.demoAccounts.mockResolvedValue(DEMO_ACCOUNTS);
      renderRoute(<LoginPage />, { path: '/login' });

      await userEvent.click(await screen.findByRole('tab', { name: 'Demo Login' }));

      expect(screen.queryByLabelText('Email')).not.toBeInTheDocument();
      expect(screen.getByTestId('demo-ramesh')).toHaveTextContent('Guest');
      expect(screen.getByTestId('demo-guest2')).toHaveTextContent('Demo Guest 2');
      expect(screen.getByTestId('demo-manager')).toHaveTextContent('Administrator');
      expect(screen.getByRole('button', { name: 'Continue as Ramesh' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Continue as GrandVista Manager' })).toBeInTheDocument();
    });

    it('signs a guest card in by account id and opens the room search', async () => {
      authApi.demoAccounts.mockResolvedValue(DEMO_ACCOUNTS);
      demoLogin.mockResolvedValue({ role: 'ROLE_USER' });
      renderRoute(<LoginPage />, { path: '/login' });

      await userEvent.click(await screen.findByRole('tab', { name: 'Demo Login' }));
      await userEvent.click(screen.getByRole('button', { name: 'Continue as Ramesh' }));

      expect(demoLogin).toHaveBeenCalledWith('ramesh');
      expect(login).not.toHaveBeenCalled();
      await waitFor(() => expect(screen.getByTestId('path')).toHaveTextContent('/rooms'));
    });

    it('signs the manager card in and opens the admin dashboard', async () => {
      authApi.demoAccounts.mockResolvedValue(DEMO_ACCOUNTS);
      demoLogin.mockResolvedValue({ role: 'ROLE_ADMIN' });
      renderRoute(<LoginPage />, { path: '/login' });

      await userEvent.click(await screen.findByRole('tab', { name: 'Demo Login' }));
      await userEvent.click(screen.getByRole('button', { name: 'Continue as GrandVista Manager' }));

      expect(demoLogin).toHaveBeenCalledWith('manager');
      await waitFor(() => expect(screen.getByTestId('path')).toHaveTextContent('/admin'));
    });

    it('shows the server message and stays put when demo login is rejected', async () => {
      authApi.demoAccounts.mockResolvedValue(DEMO_ACCOUNTS);
      demoLogin.mockRejectedValue(new Error('Demo login is disabled on this server'));
      renderRoute(<LoginPage />, { path: '/login' });

      await userEvent.click(await screen.findByRole('tab', { name: 'Demo Login' }));
      await userEvent.click(screen.getByRole('button', { name: 'Continue as Ramesh' }));

      expect(await screen.findByRole('alert')).toHaveTextContent('Demo login is disabled on this server');
      expect(screen.getByRole('button', { name: 'Continue as Ramesh' })).toBeEnabled();
    });
  });

  describe('demo login on a public deployment (reviewer access code)', () => {
    const codeRequired = () => Promise.reject(new ApiError(401, 'Reviewer access code required'));

    beforeEach(() => sessionStorage.clear());

    it('offers the Demo Login tab but asks for the access code before showing any account', async () => {
      authApi.demoAccounts.mockImplementation(codeRequired);
      renderRoute(<LoginPage />, { path: '/login' });

      await userEvent.click(await screen.findByRole('tab', { name: 'Demo Login' }));

      expect(screen.getByLabelText('Reviewer access code')).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Continue as GrandVista Manager' })).not.toBeInTheDocument();
      expect(demoLogin).not.toHaveBeenCalled();
    });

    it('unlocks the three cards with the right code and sends it with the demo login', async () => {
      authApi.demoAccounts.mockImplementation((code) => (code === 'review-code-123' ? Promise.resolve(DEMO_ACCOUNTS)
        : codeRequired()));
      demoLogin.mockResolvedValue({ role: 'ROLE_ADMIN' });
      renderRoute(<LoginPage />, { path: '/login' });

      await userEvent.click(await screen.findByRole('tab', { name: 'Demo Login' }));
      await userEvent.type(screen.getByLabelText('Reviewer access code'), 'review-code-123');
      await userEvent.click(screen.getByRole('button', { name: 'Unlock demo accounts' }));
      const manager = await screen.findByRole('button', { name: 'Continue as GrandVista Manager' });
      expect(screen.getByRole('button', { name: 'Continue as Ramesh' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Continue as Demo Guest 2' })).toBeInTheDocument();
      await userEvent.click(manager);

      expect(demoLogin).toHaveBeenCalledWith('manager', 'review-code-123');
      expect(sessionStorage.getItem('grandvista.demoAccessCode')).toBe('review-code-123');
      await waitFor(() => expect(screen.getByTestId('path')).toHaveTextContent('/admin'));
    });

    it('rejects a wrong code and keeps the accounts hidden', async () => {
      authApi.demoAccounts.mockImplementation(codeRequired);
      renderRoute(<LoginPage />, { path: '/login' });

      await userEvent.click(await screen.findByRole('tab', { name: 'Demo Login' }));
      await userEvent.type(screen.getByLabelText('Reviewer access code'), 'wrong-code-0000');
      await userEvent.click(screen.getByRole('button', { name: 'Unlock demo accounts' }));

      expect(await screen.findByRole('alert')).toHaveTextContent('That reviewer access code was not accepted.');
      expect(screen.queryByRole('button', { name: 'Continue as Ramesh' })).not.toBeInTheDocument();
      expect(sessionStorage.getItem('grandvista.demoAccessCode')).toBeNull();
    });

    it('keeps the Demo Login tab with a retry option when the server is temporarily unavailable', async () => {
      authApi.demoAccounts.mockRejectedValueOnce(new ApiError(503, 'Service Unavailable'))
        .mockResolvedValue(DEMO_ACCOUNTS);
      renderRoute(<LoginPage />, { path: '/login' });

      await userEvent.click(await screen.findByRole('tab', { name: 'Demo Login' }));
      expect(screen.getByText(/may still be waking up/)).toBeInTheDocument();
      expect(screen.queryByLabelText('Reviewer access code')).not.toBeInTheDocument();

      await userEvent.click(screen.getByRole('button', { name: 'Try again' }));

      expect(await screen.findByRole('button', { name: 'Continue as GrandVista Manager' })).toBeInTheDocument();
      expect(authApi.demoAccounts).toHaveBeenCalledTimes(2);
    });

    it('shows the rate-limit message from the server', async () => {
      authApi.demoAccounts.mockImplementationOnce(codeRequired)
        .mockImplementationOnce(() => Promise.reject(new ApiError(429, 'Too many attempts. Please wait and try again later.')));
      renderRoute(<LoginPage />, { path: '/login' });

      await userEvent.click(await screen.findByRole('tab', { name: 'Demo Login' }));
      await userEvent.type(screen.getByLabelText('Reviewer access code'), 'another-guess-1');
      await userEvent.click(screen.getByRole('button', { name: 'Unlock demo accounts' }));

      expect(await screen.findByRole('alert')).toHaveTextContent('Too many attempts');
    });

    it('asks for the code again when the server revokes it', async () => {
      sessionStorage.setItem('grandvista.demoAccessCode', 'old-code-12345');
      authApi.demoAccounts.mockResolvedValue(DEMO_ACCOUNTS);
      demoLogin.mockRejectedValue(new ApiError(401, 'Reviewer access code not accepted'));
      renderRoute(<LoginPage />, { path: '/login' });

      await userEvent.click(await screen.findByRole('tab', { name: 'Demo Login' }));
      await userEvent.click(screen.getByRole('button', { name: 'Continue as Ramesh' }));

      expect(authApi.demoAccounts).toHaveBeenCalledWith('old-code-12345');
      expect(demoLogin).toHaveBeenCalledWith('ramesh', 'old-code-12345');
      expect(await screen.findByLabelText('Reviewer access code')).toBeInTheDocument();
      expect(sessionStorage.getItem('grandvista.demoAccessCode')).toBeNull();
    });
  });
});
