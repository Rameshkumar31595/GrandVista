import { describe, expect, it } from 'vitest';
import { BOOKING_STATUSES, canAdminCancel, canGuestCancel, dashboardCounts, isInFlight, statusLabel } from './bookingStatus';

describe('bookingStatus', () => {
  it('lists the five statuses the backend can return', () => {
    expect(BOOKING_STATUSES).toEqual(['PENDING', 'PAYMENT_IN_PROGRESS', 'CONFIRMED', 'CANCELLATION_PENDING', 'CANCELLED']);
  });

  it('treats only the two saga intermediate states as in flight', () => {
    expect(BOOKING_STATUSES.filter(isInFlight)).toEqual(['PAYMENT_IN_PROGRESS', 'CANCELLATION_PENDING']);
  });

  it('gives intermediate states a human label and leaves the others unchanged', () => {
    expect(statusLabel('PAYMENT_IN_PROGRESS')).toBe('Payment processing');
    expect(statusLabel('CANCELLATION_PENDING')).toBe('Cancelling');
    expect(statusLabel('CONFIRMED')).toBe('CONFIRMED');
  });

  it('lets a guest cancel unpaid bookings and paid bookings only before check-in', () => {
    const today = '2030-05-10';
    expect(canGuestCancel({ bookingStatus: 'PENDING', checkIn: '2030-05-01' }, today)).toBe(true);
    expect(canGuestCancel({ bookingStatus: 'CONFIRMED', checkIn: '2030-05-11' }, today)).toBe(true);
    expect(canGuestCancel({ bookingStatus: 'CONFIRMED', checkIn: '2030-05-10' }, today)).toBe(false);
    expect(canGuestCancel({ bookingStatus: 'CONFIRMED', checkIn: '2030-05-09' }, today)).toBe(false);
  });

  it('never offers cancel while a payment or a cancellation is still settling, or once cancelled', () => {
    const future = { checkIn: '2031-01-01' };
    for (const status of ['PAYMENT_IN_PROGRESS', 'CANCELLATION_PENDING', 'CANCELLED']) {
      expect(canGuestCancel({ ...future, bookingStatus: status }, '2030-01-01')).toBe(false);
      expect(canAdminCancel({ ...future, bookingStatus: status })).toBe(false);
    }
    expect(canAdminCancel({ bookingStatus: 'PENDING' })).toBe(true);
    expect(canAdminCancel({ bookingStatus: 'CONFIRMED' })).toBe(true);
  });

  it('counts in-flight bookings with the group they are moving to or from; revenue is confirmed only', () => {
    const counts = dashboardCounts([
      { bookingStatus: 'CONFIRMED', totalAmount: '1000' },
      { bookingStatus: 'CONFIRMED', totalAmount: 2500.5 },
      { bookingStatus: 'PENDING', totalAmount: 99 },
      { bookingStatus: 'PAYMENT_IN_PROGRESS', totalAmount: 99 },
      { bookingStatus: 'CANCELLED', totalAmount: 99 },
      { bookingStatus: 'CANCELLATION_PENDING', totalAmount: 99 },
    ]);
    expect(counts).toEqual({ confirmed: 2, pending: 2, cancelled: 2, confirmedValue: 3500.5 });
  });
});
