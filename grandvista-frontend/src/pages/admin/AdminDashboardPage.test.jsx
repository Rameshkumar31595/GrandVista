import { screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { bookingApi, roomApi } from '../../api/endpoints';
import { booking, renderRoute } from '../../test/helpers';
import AdminDashboardPage from './AdminDashboardPage';

vi.mock('../../api/endpoints', () => ({ bookingApi: { all: vi.fn() }, roomApi: { inventorySummary: vi.fn() } }));

describe('AdminDashboardPage', () => {
  it('summarises inventory and bookings, counting in-flight bookings with their group', async () => {
    roomApi.inventorySummary.mockResolvedValue([
      { hotelId: 1, hotelName: 'A', city: 'Chennai', totalRooms: 4, activeRooms: 4, maintenanceRooms: 0, reservedRooms: 1, occupancyPercent: 25 },
      { hotelId: 2, hotelName: 'B', city: 'Pune', totalRooms: 6, activeRooms: 5, maintenanceRooms: 1, reservedRooms: 0, occupancyPercent: 0 },
    ]);
    bookingApi.all.mockResolvedValue([
      booking({ bookingId: 1, bookingStatus: 'CONFIRMED', totalAmount: 1000 }),
      booking({ bookingId: 2, bookingStatus: 'CONFIRMED', totalAmount: 2000 }),
      booking({ bookingId: 3, bookingStatus: 'PENDING', totalAmount: 500 }),
      booking({ bookingId: 4, bookingStatus: 'PAYMENT_IN_PROGRESS', totalAmount: 500 }),
      booking({ bookingId: 5, bookingStatus: 'CANCELLED', totalAmount: 500 }),
    ]);
    renderRoute(<AdminDashboardPage />, { path: '/admin' });

    expect(await screen.findByText('2 / 2 / 1')).toBeInTheDocument();
    expect(screen.getByText('10')).toBeInTheDocument();
    expect(screen.getByText(/3,000/)).toBeInTheDocument();
  });

  it('shows the error when the data cannot be loaded', async () => {
    roomApi.inventorySummary.mockImplementation(() => Promise.reject(new Error('Forbidden')));
    bookingApi.all.mockResolvedValue([]);
    renderRoute(<AdminDashboardPage />, { path: '/admin' });

    expect(await screen.findByRole('alert')).toHaveTextContent('Forbidden');
  });
});
