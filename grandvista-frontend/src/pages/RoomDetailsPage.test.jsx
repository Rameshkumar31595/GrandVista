import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/client';
import { bookingApi, roomApi } from '../api/endpoints';
import { useAuth } from '../context/AuthContext';
import { renderRoute } from '../test/helpers';
import RoomDetailsPage from './RoomDetailsPage';

vi.mock('../api/endpoints', () => ({
  roomApi: { get: vi.fn(), availability: vi.fn(), price: vi.fn() },
  bookingApi: { create: vi.fn() },
}));
vi.mock('../context/AuthContext', () => ({ useAuth: vi.fn() }));

const room = { id: 7, hotelName: 'GrandVista Marina Bay', city: 'Chennai', roomNumber: '203', roomType: 'DELUXE',
  basePrice: 5000, capacity: 2, description: 'King bed', status: 'ACTIVE' };
const quote = {
  roomId: 7, nights: 3, basePricePerNight: 5000, baseTotal: 15000, occupancyPercent: 75, demandMultiplier: 1.1,
  appliedRules: ['High Demand'], pricingAdjustment: 1500, finalTotal: 16500,
  nightlyBreakdown: [{ date: '2030-05-10', seasonalMultiplier: 1, demandMultiplier: 1.1, price: 5500 }],
};
const ENTRY = '/rooms/7?checkIn=2030-05-10&checkOut=2030-05-13&guests=2';
const guest = { isAuthenticated: true, isGuest: true, isAdmin: false };

const open = () => renderRoute(<RoomDetailsPage />, { path: '/rooms/:roomId', entry: ENTRY });
const check = () => userEvent.click(screen.getByRole('button', { name: 'Check availability & price' }));

describe('RoomDetailsPage', () => {
  beforeEach(() => {
    useAuth.mockReturnValue(guest);
    roomApi.get.mockResolvedValue(room);
    roomApi.availability.mockResolvedValue({ available: true, reason: 'Room is available for the selected dates' });
    roomApi.price.mockResolvedValue(quote);
  });

  it('prefills the stay from the search and shows the room', async () => {
    open();

    expect(await screen.findByText('GrandVista Marina Bay')).toBeInTheDocument();
    expect(screen.getByLabelText('Check-in')).toHaveValue('2030-05-10');
    expect(screen.getByLabelText('Check-out')).toHaveValue('2030-05-13');
    expect(screen.getByLabelText('Guests')).toHaveValue(2);
  });

  it('checks availability and the dynamic price with the chosen dates, and explains the price', async () => {
    open();
    await screen.findByText('GrandVista Marina Bay');

    await check();

    expect(roomApi.availability).toHaveBeenCalledWith('7', '2030-05-10', '2030-05-13');
    expect(roomApi.price).toHaveBeenCalledWith('7', '2030-05-10', '2030-05-13');
    expect(await screen.findByText('Room is available for the selected dates')).toBeInTheDocument();
    expect(screen.getByText('High Demand')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Reserve for/ })).toHaveTextContent('16,500');
  });

  it('creates the booking from the quoted stay and goes to the payment page', async () => {
    bookingApi.create.mockResolvedValue({ bookingId: 55 });
    open();
    await screen.findByText('GrandVista Marina Bay');
    await check();

    await userEvent.click(await screen.findByRole('button', { name: /Reserve for/ }));

    expect(bookingApi.create).toHaveBeenCalledWith({ roomId: 7, checkIn: '2030-05-10', checkOut: '2030-05-13', numberOfGuests: 2 });
    await waitFor(() => expect(screen.getByTestId('path')).toHaveTextContent('/bookings/55/pay'));
  });

  it('shows the conflict when someone else took the dates meanwhile and hides the reserve button', async () => {
    bookingApi.create.mockImplementation(() => Promise.reject(new ApiError(409, 'Room 203 is already reserved for some of the selected dates', {})));
    open();
    await screen.findByText('GrandVista Marina Bay');
    await check();

    await userEvent.click(await screen.findByRole('button', { name: /Reserve for/ }));

    expect(await screen.findByRole('alert')).toHaveTextContent('already reserved');
    expect(screen.queryByRole('button', { name: /Reserve for/ })).not.toBeInTheDocument();
  });

  it('reports an unavailable room instead of offering to reserve it', async () => {
    roomApi.availability.mockResolvedValue({ available: false, reason: 'Room is already reserved for some of these dates' });
    open();
    await screen.findByText('GrandVista Marina Bay');

    await check();

    expect(await screen.findByText('Room is already reserved for some of these dates')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Reserve for/ })).not.toBeInTheDocument();
  });

  it('blocks reserving for more guests than the room holds', async () => {
    open();
    await screen.findByText('GrandVista Marina Bay');
    await check();
    await screen.findByRole('button', { name: /Reserve for/ });

    await userEvent.clear(screen.getByLabelText('Guests'));
    await userEvent.type(screen.getByLabelText('Guests'), '5');

    expect(screen.getByText('This room accommodates at most 2 guests.')).toBeInTheDocument();
  });

  it('invalidates the quote when the dates change so a stale price cannot be reserved', async () => {
    open();
    await screen.findByText('GrandVista Marina Bay');
    await check();
    await screen.findByRole('button', { name: /Reserve for/ });

    await userEvent.type(screen.getByLabelText('Guests'), '1');

    expect(screen.queryByRole('button', { name: /Reserve for/ })).not.toBeInTheDocument();
  });

  it('asks anonymous visitors to sign in, remembering the room', async () => {
    useAuth.mockReturnValue({ isAuthenticated: false, isGuest: false, isAdmin: false });
    open();
    await screen.findByText('GrandVista Marina Bay');
    await check();

    const link = await screen.findByRole('link', { name: 'Sign in to reserve' });

    expect(link).toHaveAttribute('href', '/login');
  });

  it('tells administrators they cannot make guest bookings', async () => {
    useAuth.mockReturnValue({ isAuthenticated: true, isGuest: false, isAdmin: true });
    open();
    await screen.findByText('GrandVista Marina Bay');
    await check();

    expect(await screen.findByText('Administrators cannot make guest bookings.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Reserve for/ })).not.toBeInTheDocument();
  });
});
