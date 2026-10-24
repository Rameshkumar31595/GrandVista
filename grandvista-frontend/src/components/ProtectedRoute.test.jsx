import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { LocationProbe } from '../test/helpers';
import { useAuth } from '../context/AuthContext';
import ProtectedRoute from './ProtectedRoute';

vi.mock('../context/AuthContext', () => ({ useAuth: vi.fn() }));

const renderGuard = (role, entry = '/secret') => render(
  <MemoryRouter initialEntries={[entry]}>
    <Routes>
      <Route element={<ProtectedRoute role={role} />}>
        <Route path="/secret" element={<p>secret content</p>} />
      </Route>
      <Route path="*" element={<LocationProbe />} />
    </Routes>
  </MemoryRouter>,
);

describe('ProtectedRoute', () => {
  it('sends anonymous visitors to the login page and remembers where they wanted to go', () => {
    useAuth.mockReturnValue({ isAuthenticated: false, user: null });
    renderGuard('ROLE_USER', '/secret?x=1');

    expect(screen.getByTestId('path')).toHaveTextContent('/login');
    expect(screen.getByTestId('state')).toHaveTextContent('"from":"/secret?x=1"');
  });

  it('shows the page to a user with the required role', () => {
    useAuth.mockReturnValue({ isAuthenticated: true, user: { role: 'ROLE_USER' } });
    renderGuard('ROLE_USER');
    expect(screen.getByText('secret content')).toBeInTheDocument();
  });

  it('sends a guest away from an administrator page', () => {
    useAuth.mockReturnValue({ isAuthenticated: true, user: { role: 'ROLE_USER' } });
    renderGuard('ROLE_ADMIN');
    expect(screen.getByTestId('path')).toHaveTextContent('/forbidden');
  });

  it('sends an administrator away from a guest-only page', () => {
    useAuth.mockReturnValue({ isAuthenticated: true, user: { role: 'ROLE_ADMIN' } });
    renderGuard('ROLE_USER');
    expect(screen.getByTestId('path')).toHaveTextContent('/forbidden');
  });
});
