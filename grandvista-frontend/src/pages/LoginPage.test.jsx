import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuth } from '../context/AuthContext';
import { renderRoute } from '../test/helpers';
import LoginPage from './LoginPage';

vi.mock('../context/AuthContext', () => ({ useAuth: vi.fn() }));

const login = vi.fn();
const clearNotice = vi.fn();

async function signIn(email = 'asha@example.com', password = 'Secret#123') {
  await userEvent.type(screen.getByLabelText('Email'), email);
  await userEvent.type(screen.getByLabelText('Password'), password);
  await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
}

describe('LoginPage', () => {
  beforeEach(() => {
    login.mockReset();
    useAuth.mockReturnValue({ login, notice: null, clearNotice });
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
    useAuth.mockReturnValue({ login, notice: 'Your session has expired. Please log in again.', clearNotice });
    renderRoute(<LoginPage />, { path: '/login' });

    expect(screen.getByText('Your session has expired. Please log in again.')).toBeInTheDocument();
  });
});
