// Single place that knows what each booking status means for the UI.
//
// The backend runs the booking -> payment -> room workflow as a saga with two persisted intermediate states:
//   PAYMENT_IN_PROGRESS  - a payment attempt is running (or its outcome is still being verified)
//   CANCELLATION_PENDING - the booking is being cancelled; the refund / room release is being completed
// Neither can be acted on by the guest; both settle on their own, so the UI polls until they do.

export const BOOKING_STATUSES = ['PENDING', 'PAYMENT_IN_PROGRESS', 'CONFIRMED', 'CANCELLATION_PENDING', 'CANCELLED'];

const IN_FLIGHT = new Set(['PAYMENT_IN_PROGRESS', 'CANCELLATION_PENDING']);

const LABELS = {
  PAYMENT_IN_PROGRESS: 'Payment processing',
  CANCELLATION_PENDING: 'Cancelling',
};

export const isInFlight = (status) => IN_FLIGHT.has(status);

export const statusLabel = (status) => LABELS[status] || status;

/** Guest may cancel an unpaid booking, or a paid one until the check-in date. `today` is yyyy-mm-dd. */
export function canGuestCancel(booking, today) {
  if (booking.bookingStatus === 'PENDING') return true;
  return booking.bookingStatus === 'CONFIRMED' && booking.checkIn > today;
}

/** Admins can cancel the same bookings; the server enforces the check-in rule. */
export const canAdminCancel = (booking) => booking.bookingStatus === 'PENDING' || booking.bookingStatus === 'CONFIRMED';

/** Dashboard figures: in-flight bookings count with the state they are heading away from / towards. */
export function dashboardCounts(bookings) {
  const count = (...statuses) => bookings.filter((b) => statuses.includes(b.bookingStatus)).length;
  return {
    confirmed: count('CONFIRMED'),
    pending: count('PENDING', 'PAYMENT_IN_PROGRESS'),
    cancelled: count('CANCELLED', 'CANCELLATION_PENDING'),
    confirmedValue: bookings.filter((b) => b.bookingStatus === 'CONFIRMED').reduce((sum, b) => sum + Number(b.totalAmount), 0),
  };
}
