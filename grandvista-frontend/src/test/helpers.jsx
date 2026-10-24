import { render } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';

/** Unsigned JWT-shaped string: the UI only ever reads the "exp" claim from it. */
export function makeJwt(claims = {}) {
  const encode = (value) => btoa(JSON.stringify(value)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  return `${encode({ alg: 'HS256', typ: 'JWT' })}.${encode({ sub: '5', exp: Math.floor(Date.now() / 1000) + 3600, ...claims })}.sig`;
}

/** Shows where the router currently is, so tests can assert on navigation. */
export function LocationProbe() {
  const location = useLocation();
  return (
    <div>
      <span data-testid="path">{location.pathname}{location.search}</span>
      <span data-testid="state">{JSON.stringify(location.state ?? null)}</span>
    </div>
  );
}

/**
 * Renders `element` at `path` inside a MemoryRouter. Any other location lands on a LocationProbe, so
 * a redirect or navigate() can be observed with screen.getByTestId('path').
 */
export function renderRoute(element, { path = '/', entry = path, extraRoutes = [] } = {}) {
  return render(
    <MemoryRouter initialEntries={[entry]}>
      <Routes>
        <Route path={path} element={element} />
        {extraRoutes.map((extra) => <Route key={extra} path={extra} element={<LocationProbe />} />)}
        <Route path="*" element={<LocationProbe />} />
      </Routes>
    </MemoryRouter>,
  );
}

export const booking = (overrides = {}) => ({
  bookingId: 11,
  userId: 5,
  roomId: 7,
  hotelId: 1,
  hotelName: 'GrandVista Banjara Residency',
  roomNumber: '203',
  roomType: 'DELUXE',
  checkIn: '2030-05-10',
  checkOut: '2030-05-13',
  nights: 3,
  numberOfGuests: 2,
  totalAmount: 16500,
  currency: 'INR',
  bookingStatus: 'PENDING',
  statusReason: 'Awaiting payment',
  paymentDueBy: '2030-05-01T10:15:00Z',
  paymentId: null,
  paymentReference: null,
  ...overrides,
});
