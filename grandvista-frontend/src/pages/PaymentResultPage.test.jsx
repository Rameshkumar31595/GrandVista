import { act, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { bookingApi } from '../api/endpoints';
import { booking } from '../test/helpers';
import PaymentResultPage from './PaymentResultPage';

vi.mock('../api/endpoints', () => ({ bookingApi: { get: vi.fn() } }));

const open = (outcome) => render(
  <MemoryRouter initialEntries={[{ pathname: '/bookings/11/result', state: outcome ? { outcome } : undefined }]}>
    <Routes><Route path="/bookings/:bookingId/result" element={<PaymentResultPage />} /></Routes>
  </MemoryRouter>,
);

describe('PaymentResultPage', () => {
  afterEach(() => vi.useRealTimers());

  it('celebrates a confirmed booking with its transaction reference', () => {
    open({
      booking: booking({ bookingStatus: 'CONFIRMED', paymentReference: 'GV-PAY-20300501-ABCDEF12' }),
      paymentStatus: 'SUCCESS', transactionReference: 'GV-PAY-20300501-ABCDEF12', message: 'Payment successful. Your booking is confirmed.',
    });

    expect(screen.getByRole('heading', { name: 'Booking confirmed' })).toBeInTheDocument();
    expect(screen.getByText('GV-PAY-20300501-ABCDEF12')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Try again' })).not.toBeInTheDocument();
  });

  it('explains a declined payment and offers to try again on the room page', () => {
    open({
      booking: booking({ bookingStatus: 'CANCELLED', statusReason: 'Payment failed: Payment declined by the simulated gateway' }),
      paymentStatus: 'FAILED', message: 'Payment failed. The room has been released.',
    });

    expect(screen.getByRole('heading', { name: 'Booking not confirmed' })).toBeInTheDocument();
    expect(screen.getByText('Payment failed. The room has been released.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Try again' })).toHaveAttribute('href', '/rooms/7');
  });

  it('says the payment is being verified - not failed - when the server could not confirm it yet', () => {
    open({
      booking: booking({ bookingStatus: 'PAYMENT_IN_PROGRESS', statusReason: 'Payment in progress' }),
      paymentStatus: 'UNKNOWN', message: 'We could not confirm your payment yet. Please do not pay again.',
    });

    expect(screen.getByRole('heading', { name: 'Verifying your payment' })).toBeInTheDocument();
    expect(screen.getByText(/Please do not pay again/, { selector: 'p.small' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Try again' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Pay again' })).not.toBeInTheDocument();
  });

  it('updates by itself to "confirmed" once the server has verified the payment', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    bookingApi.get.mockResolvedValue(booking({ bookingStatus: 'CONFIRMED', paymentId: 9, paymentReference: 'GV-PAY-1', statusReason: 'Payment received and room confirmed' }));
    open({ booking: booking({ bookingStatus: 'PAYMENT_IN_PROGRESS' }), paymentStatus: 'UNKNOWN', message: 'We could not confirm your payment yet.' });

    await act(async () => { await vi.advanceTimersByTimeAsync(4500); });

    expect(await screen.findByRole('heading', { name: 'Booking confirmed' })).toBeInTheDocument();
    expect(screen.getByText('SUCCESS')).toBeInTheDocument();
    expect(screen.queryByText('UNKNOWN')).not.toBeInTheDocument();
  });

  it('tells the guest no money was taken when verification finds no payment and the room is still held', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    bookingApi.get.mockResolvedValue(booking({ bookingStatus: 'PENDING', statusReason: 'Awaiting payment' }));
    open({ booking: booking({ bookingStatus: 'PAYMENT_IN_PROGRESS' }), paymentStatus: 'UNKNOWN', message: 'We could not confirm your payment yet.' });

    await act(async () => { await vi.advanceTimersByTimeAsync(4500); });

    expect(await screen.findByRole('heading', { name: 'Payment not completed' })).toBeInTheDocument();
    expect(screen.getByText(/No payment was taken/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Pay again' })).toHaveAttribute('href', '/bookings/11/pay');
  });

  it('shows a refund in progress as a cancellation being finished', () => {
    open({
      booking: booking({ bookingStatus: 'CANCELLATION_PENDING', statusReason: 'Room could not be confirmed' }),
      paymentStatus: 'SUCCESS', message: 'Room could not be confirmed - your refund is being processed and will complete automatically',
    });

    expect(screen.getByRole('heading', { name: 'Finishing your cancellation' })).toBeInTheDocument();
    expect(screen.getByText(/refund is being processed/)).toBeInTheDocument();
  });

  it('rebuilds the result from the stored booking after a page reload', async () => {
    bookingApi.get.mockResolvedValue(booking({ bookingStatus: 'CONFIRMED', statusReason: 'Payment received and room confirmed' }));
    open(null);

    expect(await screen.findByRole('heading', { name: 'Booking confirmed' })).toBeInTheDocument();
    expect(bookingApi.get).toHaveBeenCalledWith('11');
  });

  it('shows an error if the stored booking cannot be loaded', async () => {
    bookingApi.get.mockImplementation(() => Promise.reject(new Error('Booking 11 not found')));
    open(null);

    expect(await screen.findByRole('alert')).toHaveTextContent('not found');
  });
});
