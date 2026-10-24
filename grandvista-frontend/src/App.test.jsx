import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import App from './App';
import { tokenStorage } from './api/tokenStorage';
import { AuthProvider } from './context/AuthContext';
import { makeJwt } from './test/helpers';

// Every API call resolves to an empty list: these tests are about routing and access control only.
vi.mock('./api/endpoints', () => {
  const api = new Proxy({}, { get: () => () => Promise.resolve([]) });
  return { authApi: api, hotelApi: api, roomApi: api, pricingApi: api, bookingApi: api, paymentApi: api };
});

const renderApp = (path) => render(
  <MemoryRouter initialEntries={[path]}>
    <AuthProvider><App /></AuthProvider>
  </MemoryRouter>,
);

const signInAs = (role) => tokenStorage.save(makeJwt(), { name: 'Test User', email: 't@example.com', role });

describe('routing and access control', () => {
  it('redirects an anonymous visitor from My bookings to the login page', async () => {
    renderApp('/my-bookings');
    expect(await screen.findByRole('heading', { name: 'Welcome back' })).toBeInTheDocument();
  });

  it('redirects an anonymous visitor from the admin area to the login page', async () => {
    renderApp('/admin/bookings');
    expect(await screen.findByRole('heading', { name: 'Welcome back' })).toBeInTheDocument();
  });

  it('shows "Access denied" to a guest who opens an admin page by URL', async () => {
    signInAs('ROLE_USER');
    renderApp('/admin/users');
    expect(await screen.findByRole('heading', { name: 'Access denied' })).toBeInTheDocument();
  });

  it('shows "Access denied" to an administrator who opens a guest-only page', async () => {
    signInAs('ROLE_ADMIN');
    renderApp('/my-bookings');
    expect(await screen.findByRole('heading', { name: 'Access denied' })).toBeInTheDocument();
  });

  it('shows guest navigation to a guest and admin navigation to an administrator', async () => {
    signInAs('ROLE_USER');
    const { unmount } = renderApp('/rooms');
    expect(await screen.findByRole('link', { name: 'My Bookings' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Users' })).not.toBeInTheDocument();
    unmount();

    signInAs('ROLE_ADMIN');
    renderApp('/rooms');
    expect(await screen.findByRole('link', { name: 'Users' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'My Bookings' })).not.toBeInTheDocument();
  });

  it('shows a friendly page for unknown addresses', async () => {
    renderApp('/no/such/page');
    expect(await screen.findByRole('heading', { name: 'Page not found' })).toBeInTheDocument();
  });

  it('keeps the guest pages public: room search and registration need no login', async () => {
    const first = renderApp('/rooms');
    expect(await screen.findByRole('heading', { name: 'Find a room' })).toBeInTheDocument();
    first.unmount();

    renderApp('/register');
    expect(await screen.findByRole('heading', { name: 'Create your account' })).toBeInTheDocument();
  });
});
