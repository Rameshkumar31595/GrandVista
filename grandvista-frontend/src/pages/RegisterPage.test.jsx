import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/client';
import { authApi } from '../api/endpoints';
import { renderRoute } from '../test/helpers';
import RegisterPage from './RegisterPage';

vi.mock('../api/endpoints', () => ({ authApi: { register: vi.fn() } }));

async function fill({ name = 'Asha Rao', email = 'asha@example.com', password = 'Secret#123', confirm = 'Secret#123' } = {}) {
  await userEvent.type(screen.getByLabelText('Full name'), name);
  await userEvent.type(screen.getByLabelText('Email'), email);
  await userEvent.type(screen.getByLabelText('Password'), password);
  await userEvent.type(screen.getByLabelText('Confirm password'), confirm);
  await userEvent.click(screen.getByRole('button', { name: 'Register' }));
}

describe('RegisterPage', () => {
  beforeEach(() => { authApi.register.mockReset(); });

  it('registers without ever sending a role and moves on to the login page', async () => {
    authApi.register.mockResolvedValue({});
    renderRoute(<RegisterPage />, { path: '/register' });

    await fill();

    expect(authApi.register).toHaveBeenCalledWith({ name: 'Asha Rao', email: 'asha@example.com', password: 'Secret#123' });
    await waitFor(() => expect(screen.getByTestId('path')).toHaveTextContent('/login'));
    expect(screen.getByTestId('state')).toHaveTextContent('"registered":true');
  });

  it('catches a password mismatch before calling the server', async () => {
    renderRoute(<RegisterPage />, { path: '/register' });

    await fill({ confirm: 'Different#1' });

    expect(screen.getByText('Passwords do not match')).toBeInTheDocument();
    expect(authApi.register).not.toHaveBeenCalled();
  });

  it('shows per-field validation errors from the server', async () => {
    authApi.register.mockImplementation(() => Promise.reject(new ApiError(400, 'Request validation failed',
      { password: 'Password must be between 8 and 72 characters', email: 'Email is not valid' })));
    renderRoute(<RegisterPage />, { path: '/register' });

    await fill({ password: 'short1', confirm: 'short1' });

    expect(await screen.findByText('Password must be between 8 and 72 characters')).toBeInTheDocument();
    expect(screen.getByText('Email is not valid')).toBeInTheDocument();
  });

  it('reports a duplicate email', async () => {
    authApi.register.mockImplementation(() => Promise.reject(new ApiError(409, 'An account with this email already exists', {})));
    renderRoute(<RegisterPage />, { path: '/register' });

    await fill();

    expect(await screen.findByRole('alert')).toHaveTextContent('already exists');
  });
});
