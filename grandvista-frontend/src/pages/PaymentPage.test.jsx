import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/client';
import { bookingApi } from '../api/endpoints';
import { booking, renderRoute } from '../test/helpers';
import PaymentPage from './PaymentPage';

vi.mock('../api/endpoints', () => ({ bookingApi: { get: vi.fn(), pay: vi.fn(), cancel: vi.fn() } }));

const inOneHour = () => new Date(Date.now() + 3600 * 1000).toISOString();
const open = () => renderRoute(<PaymentPage />, { path: '/bookings/:bookingId/pay', entry: '/bookings/11/pay', extraRoutes: ['/bookings/11/result'] });
const payButton = () => screen.getByRole('button', { name: /^Pay/ });

describe('PaymentPage', () => {
  beforeEach(() => { bookingApi.get.mockResolvedValue(booking({ paymentDueBy: inOneHour() })); });
  afterEach(() => vi.useRealTimers());

  it('shows what is being paid for and the countdown for the room hold', async () => {
    open();

    expect(await screen.findByText('Complete your payment')).toBeInTheDocument();
    expect(screen.getByText('GrandVista Banjara Residency')).toBeInTheDocument();
    expect(payButton()).toHaveTextContent('16,500');
    expect(screen.getByText(/The room is held for you/)).toBeInTheDocument();
  });

  it('pays with UPI by default and opens the result page with the outcome', async () => {
    const outcome = { booking: booking({ bookingStatus: 'CONFIRMED' }), paymentStatus: 'SUCCESS', message: 'Payment successful.' };
    bookingApi.pay.mockResolvedValue(outcome);
    open();
    await screen.findByText('Complete your payment');

    await userEvent.click(payButton());

    expect(bookingApi.pay).toHaveBeenCalledWith('11', { paymentMethod: 'UPI', simulateFailure: false });
    await waitFor(() => expect(screen.getByTestId('path')).toHaveTextContent('/bookings/11/result'));
    expect(screen.getByTestId('state')).toHaveTextContent('Payment successful.');
  });

  it('sends the selected method and the demo "decline" switch', async () => {
    bookingApi.pay.mockResolvedValue({ booking: booking(), paymentStatus: 'FAILED', message: 'declined' });
    open();
    await screen.findByText('Complete your payment');

    await userEvent.click(screen.getByLabelText('Credit / Debit card'));
    await userEvent.click(screen.getByLabelText('Demo: simulate a declined payment'));
    await userEvent.click(payButton());

    expect(bookingApi.pay).toHaveBeenCalledWith('11', { paymentMethod: 'CARD', simulateFailure: true });
  });

  it('never asks for card numbers or PINs', async () => {
    open();
    await screen.findByText('Complete your payment');

    expect(screen.queryByLabelText(/card number|cvv|pin/i)).not.toBeInTheDocument();
    expect(screen.getByText(/never asks for card numbers/)).toBeInTheDocument();
  });

  it('disables the pay button while the payment is being processed', async () => {
    let finish;
    bookingApi.pay.mockReturnValue(new Promise((resolve) => { finish = resolve; }));
    open();
    await screen.findByText('Complete your payment');

    await userEvent.click(payButton());

    expect(screen.getByRole('button', { name: 'Processing payment…' })).toBeDisabled();
    await act(async () => finish({ booking: booking(), paymentStatus: 'SUCCESS', message: 'ok' }));
  });

  it('shows the server message and stays on the page when payment is refused, e.g. a second click', async () => {
    bookingApi.pay.mockImplementation(() => Promise.reject(new ApiError(409, 'Booking 11 is PAYMENT_IN_PROGRESS and cannot be paid', {})));
    open();
    await screen.findByText('Complete your payment');

    await userEvent.click(payButton());

    expect(await screen.findByRole('alert')).toHaveTextContent('cannot be paid');
    expect(payButton()).toBeEnabled();
  });

  it('refuses to pay after the hold has expired', async () => {
    bookingApi.get.mockResolvedValue(booking({ paymentDueBy: new Date(Date.now() - 1000).toISOString() }));
    open();
    await screen.findByText('Complete your payment');

    expect(screen.getByText(/payment window has expired/)).toBeInTheDocument();
    expect(payButton()).toBeDisabled();
  });

  it('does not offer payment for a booking that is no longer pending', async () => {
    bookingApi.get.mockResolvedValue(booking({ bookingStatus: 'CONFIRMED', statusReason: 'Payment received and room confirmed' }));
    open();

    expect(await screen.findByText(/This booking is CONFIRMED/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Pay/ })).not.toBeInTheDocument();
  });

  it('follows a payment that is still settling instead of offering to pay again', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    bookingApi.get
      .mockResolvedValueOnce(booking({ bookingStatus: 'PAYMENT_IN_PROGRESS', statusReason: 'Payment in progress' }))
      .mockResolvedValue(booking({ bookingStatus: 'CONFIRMED', statusReason: 'Payment received and room confirmed' }));
    open();

    expect(await screen.findByText(/This booking is Payment processing/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Pay/ })).not.toBeInTheDocument();

    await act(async () => { await vi.advanceTimersByTimeAsync(4500); });

    expect(await screen.findByText(/This booking is CONFIRMED/)).toBeInTheDocument();
  });

  it('cancels an unpaid booking from the payment page', async () => {
    bookingApi.cancel.mockResolvedValue(booking({ bookingStatus: 'CANCELLED', statusReason: 'Cancelled by guest before payment' }));
    open();
    await screen.findByText('Complete your payment');

    await userEvent.click(screen.getByRole('button', { name: 'Cancel booking' }));

    expect(bookingApi.cancel).toHaveBeenCalledWith('11');
    expect(await screen.findByText(/This booking is CANCELLED/)).toBeInTheDocument();
  });
});
