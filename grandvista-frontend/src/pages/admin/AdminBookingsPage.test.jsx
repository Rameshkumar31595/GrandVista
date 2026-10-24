import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { bookingApi } from '../../api/endpoints';
import { booking, renderRoute } from '../../test/helpers';
import AdminBookingsPage from './AdminBookingsPage';

vi.mock('../../api/endpoints', () => ({ bookingApi: { all: vi.fn(), cancel: vi.fn() } }));

const rows = [
  booking({ bookingId: 1, bookingStatus: 'PENDING' }),
  booking({ bookingId: 2, bookingStatus: 'CONFIRMED', paymentReference: 'GV-PAY-2' }),
  booking({ bookingId: 3, bookingStatus: 'PAYMENT_IN_PROGRESS' }),
  booking({ bookingId: 4, bookingStatus: 'CANCELLATION_PENDING' }),
  booking({ bookingId: 5, bookingStatus: 'CANCELLED' }),
];
const open = () => renderRoute(<AdminBookingsPage />, { path: '/admin/bookings' });
const row = (id) => screen.getAllByRole('row').find((r) => r.querySelector('td')?.textContent === String(id));

describe('AdminBookingsPage', () => {
  beforeEach(() => { bookingApi.all.mockResolvedValue(rows); });

  it('lets the admin filter by every status the backend can return', async () => {
    open();
    await screen.findByText('GV-PAY-2');

    const options = [...screen.getByLabelText('Status').querySelectorAll('option')].map((o) => o.value);
    expect(options).toEqual(['', 'PENDING', 'PAYMENT_IN_PROGRESS', 'CONFIRMED', 'CANCELLATION_PENDING', 'CANCELLED']);
  });

  it('asks the server for the selected status only', async () => {
    open();
    await screen.findByText('GV-PAY-2');

    await userEvent.selectOptions(screen.getByLabelText('Status'), 'CANCELLATION_PENDING');

    await waitFor(() => expect(bookingApi.all).toHaveBeenLastCalledWith('CANCELLATION_PENDING'));
  });

  it('offers cancel only for pending and confirmed bookings', async () => {
    open();
    await screen.findByText('GV-PAY-2');

    expect(within(row(1)).getByRole('button', { name: 'Cancel' })).toBeInTheDocument();
    expect(within(row(2)).getByRole('button', { name: 'Cancel' })).toBeInTheDocument();
    for (const id of [3, 4, 5]) {
      expect(within(row(id)).queryByRole('button', { name: 'Cancel' })).not.toBeInTheDocument();
    }
  });

  it('cancels after confirmation, shows the refund outcome and reloads the list', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    bookingApi.cancel.mockResolvedValue(booking({ bookingId: 2, bookingStatus: 'CANCELLED', statusReason: 'Cancelled by administrator - payment refunded' }));
    open();
    await screen.findByText('GV-PAY-2');

    await userEvent.click(within(row(2)).getByRole('button', { name: 'Cancel' }));

    expect(bookingApi.cancel).toHaveBeenCalledWith(2);
    expect(await screen.findByText(/Booking #2: Cancelled by administrator - payment refunded/)).toBeInTheDocument();
    expect(bookingApi.all).toHaveBeenCalledTimes(2);
  });

  it('shows an empty table message', async () => {
    bookingApi.all.mockResolvedValue([]);
    open();

    expect(await screen.findByText('No bookings.')).toBeInTheDocument();
  });
});
