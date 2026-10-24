import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { bookingApi } from '../api/endpoints';
import { isoDate } from '../components/common';
import { booking, renderRoute } from '../test/helpers';
import MyBookingsPage from './MyBookingsPage';

vi.mock('../api/endpoints', () => ({ bookingApi: { mine: vi.fn(), cancel: vi.fn() } }));

const open = () => renderRoute(<MyBookingsPage />, { path: '/my-bookings' });
const card = (id) => screen.getByText(new RegExp(`#${id} ·`)).closest('article');

describe('MyBookingsPage', () => {
  afterEach(() => vi.useRealTimers());

  it('shows an empty state with a link to the room search', async () => {
    bookingApi.mine.mockResolvedValue([]);
    open();

    expect(await screen.findByText('No bookings yet')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Find a room' })).toHaveAttribute('href', '/rooms');
  });

  it('offers payment and cancellation for an unpaid booking', async () => {
    bookingApi.mine.mockResolvedValue([booking({ bookingId: 11 })]);
    open();
    await screen.findByText(/#11 ·/);

    const item = within(card(11));
    expect(item.getByRole('link', { name: 'Pay now' })).toHaveAttribute('href', '/bookings/11/pay');
    expect(item.getByRole('button', { name: 'Cancel' })).toBeInTheDocument();
    expect(item.getByText(/Pay before/)).toBeInTheDocument();
  });

  it('allows cancelling a confirmed booking only before its check-in date', async () => {
    bookingApi.mine.mockResolvedValue([
      booking({ bookingId: 21, bookingStatus: 'CONFIRMED', checkIn: isoDate(10), checkOut: isoDate(12), paymentReference: 'GV-PAY-1' }),
      booking({ bookingId: 22, bookingStatus: 'CONFIRMED', checkIn: isoDate(0), checkOut: isoDate(2), paymentReference: 'GV-PAY-2' }),
    ]);
    open();
    await screen.findByText(/#21 ·/);

    expect(within(card(21)).getByRole('button', { name: 'Cancel' })).toBeInTheDocument();
    expect(within(card(22)).queryByRole('button', { name: 'Cancel' })).not.toBeInTheDocument();
    expect(within(card(21)).getByText('GV-PAY-1')).toBeInTheDocument();
  });

  it('does not offer pay or cancel while a payment or cancellation is being settled', async () => {
    bookingApi.mine.mockResolvedValue([
      booking({ bookingId: 31, bookingStatus: 'PAYMENT_IN_PROGRESS', statusReason: 'Payment in progress' }),
      booking({ bookingId: 32, bookingStatus: 'CANCELLATION_PENDING', statusReason: 'Cancelled by guest' }),
      booking({ bookingId: 33, bookingStatus: 'CANCELLED', statusReason: 'Cancelled by guest - payment refunded' }),
    ]);
    open();
    await screen.findByText(/#31 ·/);

    for (const id of [31, 32, 33]) {
      expect(within(card(id)).queryByRole('button', { name: 'Cancel' })).not.toBeInTheDocument();
      expect(within(card(id)).queryByRole('link', { name: 'Pay now' })).not.toBeInTheDocument();
    }
    expect(within(card(31)).getByText('Payment processing')).toBeInTheDocument();
    expect(within(card(32)).getByText('Cancelling')).toBeInTheDocument();
    expect(within(card(31)).getByText(/refreshes automatically/)).toBeInTheDocument();
    expect(within(card(33)).queryByText(/refreshes automatically/)).not.toBeInTheDocument();
  });

  it('keeps refreshing while a booking is in flight and shows its final state', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    bookingApi.mine
      .mockResolvedValueOnce([booking({ bookingId: 41, bookingStatus: 'PAYMENT_IN_PROGRESS' })])
      .mockResolvedValue([booking({ bookingId: 41, bookingStatus: 'CONFIRMED', statusReason: 'Payment received and room confirmed' })]);
    open();
    await screen.findByText('Payment processing');

    await act(async () => { await vi.advanceTimersByTimeAsync(4500); });

    expect(await screen.findByText('CONFIRMED')).toBeInTheDocument();
    expect(bookingApi.mine).toHaveBeenCalledTimes(2);
  });

  it('does not poll when nothing is in flight', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    bookingApi.mine.mockResolvedValue([booking({ bookingId: 51, bookingStatus: 'CONFIRMED', checkIn: isoDate(9), checkOut: isoDate(10) })]);
    open();
    await screen.findByText(/#51 ·/);

    await act(async () => { await vi.advanceTimersByTimeAsync(20000); });

    expect(bookingApi.mine).toHaveBeenCalledTimes(1);
  });

  describe('cancelling', () => {
    beforeEach(() => {
      bookingApi.mine.mockResolvedValue([booking({ bookingId: 61, bookingStatus: 'CONFIRMED', checkIn: isoDate(10), checkOut: isoDate(12) })]);
    });

    it('asks for confirmation, mentions the refund and reports the result', async () => {
      const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
      bookingApi.cancel.mockResolvedValue(booking({ bookingId: 61, bookingStatus: 'CANCELLED', statusReason: 'Cancelled by guest - payment refunded' }));
      open();
      await screen.findByText(/#61 ·/);

      await userEvent.click(within(card(61)).getByRole('button', { name: 'Cancel' }));

      expect(confirm).toHaveBeenCalledWith(expect.stringContaining('Your payment will be refunded.'));
      expect(bookingApi.cancel).toHaveBeenCalledWith(61);
      expect(await screen.findByText(/Booking #61 cancelled: Cancelled by guest - payment refunded/)).toBeInTheDocument();
    });

    it('does nothing when the guest declines the confirmation', async () => {
      vi.spyOn(window, 'confirm').mockReturnValue(false);
      open();
      await screen.findByText(/#61 ·/);

      await userEvent.click(within(card(61)).getByRole('button', { name: 'Cancel' }));

      expect(bookingApi.cancel).not.toHaveBeenCalled();
    });

    it('shows the server error, for example when the payment is still being processed', async () => {
      vi.spyOn(window, 'confirm').mockReturnValue(true);
      bookingApi.cancel.mockImplementation(() => Promise.reject(new Error('Payment for booking 61 is being processed - please check again in a moment')));
      open();
      await screen.findByText(/#61 ·/);

      await userEvent.click(within(card(61)).getByRole('button', { name: 'Cancel' }));

      await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('being processed'));
    });
  });
});
