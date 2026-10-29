import { fireEvent, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { hotelApi, roomApi } from '../api/endpoints';
import { ApiError } from '../api/client';
import { renderRoute } from '../test/helpers';
import RoomsPage, { WAKE_MAX_RETRIES, WAKE_RETRY_DELAY_MS } from './RoomsPage';

vi.mock('../api/endpoints', () => ({ hotelApi: { list: vi.fn() }, roomApi: { search: vi.fn() } }));

const hotels = [
  { id: 1, name: 'GrandVista Banjara Residency', city: 'Hyderabad' },
  { id: 4, name: 'GrandVista Marina Bay', city: 'Chennai' },
];
const room = (id, extra = {}) => ({
  id, hotelName: 'GrandVista Marina Bay', city: 'Chennai', roomNumber: `10${id}`, roomType: 'DELUXE',
  basePrice: 6200, capacity: 3, description: 'King bed', status: 'ACTIVE', ...extra,
});

describe('RoomsPage', () => {
  beforeEach(() => {
    hotelApi.list.mockResolvedValue(hotels);
    roomApi.search.mockResolvedValue([room(1), room(2)]);
  });

  it('lists rooms with their base price and a link to book', async () => {
    renderRoute(<RoomsPage />, { path: '/rooms' });

    expect(await screen.findAllByRole('heading', { name: 'GrandVista Marina Bay' })).toHaveLength(2);
    expect(roomApi.search).toHaveBeenCalledWith({});
    expect(screen.getAllByRole('link', { name: /View & book/ })[0]).toHaveAttribute('href', '/rooms/1');
  });

  it('offers a city filter built from the hotels', async () => {
    renderRoute(<RoomsPage />, { path: '/rooms' });

    await screen.findAllByRole('heading', { name: 'GrandVista Marina Bay' });
    const cities = [...screen.getByLabelText('City').querySelectorAll('option')].map((option) => option.textContent);
    expect(cities).toEqual(['Any', 'Hyderabad', 'Chennai']);
  });

  it('searches with every selected filter and keeps the dates for the details page', async () => {
    renderRoute(<RoomsPage />, { path: '/rooms' });
    await screen.findAllByRole('heading', { name: 'GrandVista Marina Bay' });

    await userEvent.selectOptions(screen.getByLabelText('City'), 'Chennai');
    await userEvent.selectOptions(screen.getByLabelText('Hotel'), '4');
    await userEvent.selectOptions(screen.getByLabelText('Room type'), 'SUITE');
    await userEvent.type(screen.getByLabelText('Guests'), '2');
    await userEvent.type(screen.getByLabelText('Max base price'), '9000');
    fireEvent.change(screen.getByLabelText('Check-in'), { target: { value: '2030-05-10' } });
    fireEvent.change(screen.getByLabelText('Check-out'), { target: { value: '2030-05-13' } });
    await userEvent.click(screen.getByRole('button', { name: 'Search' }));

    await waitFor(() => expect(roomApi.search).toHaveBeenLastCalledWith({
      city: 'Chennai', hotelId: '4', roomType: 'SUITE', guests: '2', maxPrice: '9000',
      checkIn: '2030-05-10', checkOut: '2030-05-13',
    }));
    expect(await screen.findByText(/Showing rooms free for the whole stay 2030-05-10/)).toBeInTheDocument();
    expect(screen.getAllByRole('link', { name: /View & book/ })[0])
      .toHaveAttribute('href', '/rooms/1?checkIn=2030-05-10&checkOut=2030-05-13&guests=2');
  });

  it('accepts any max price, not only multiples of 100 offset by 1 (regression: step=100 min=1 blocked 9000)', async () => {
    renderRoute(<RoomsPage />, { path: '/rooms' });
    await screen.findAllByRole('heading', { name: 'GrandVista Marina Bay' });

    await userEvent.type(screen.getByLabelText('Max base price'), '9000');

    expect(screen.getByLabelText('Max base price')).toBeValid();
  });

  it('shows an empty state when nothing matches', async () => {
    roomApi.search.mockResolvedValue([]);
    renderRoute(<RoomsPage />, { path: '/rooms' });

    expect(await screen.findByText('No rooms match your search')).toBeInTheDocument();
  });

  it('shows the server message when the search fails', async () => {
    roomApi.search.mockImplementation(() => Promise.reject(new Error('Cannot reach the API Gateway')));
    renderRoute(<RoomsPage />, { path: '/rooms' });

    expect(await screen.findByRole('alert')).toHaveTextContent('Cannot reach the API Gateway');
  });

  it('reset clears the filters and searches again without them', async () => {
    renderRoute(<RoomsPage />, { path: '/rooms', entry: '/rooms?city=Chennai&roomType=SUITE' });
    await waitFor(() => expect(roomApi.search).toHaveBeenCalledWith({ city: 'Chennai', roomType: 'SUITE' }));

    await userEvent.click(screen.getByRole('button', { name: 'Reset' }));

    await waitFor(() => expect(roomApi.search).toHaveBeenLastCalledWith({}));
  });

  describe('while the server is waking up', () => {
    afterEach(() => vi.useRealTimers());

    it('retries a 503 search automatically and then shows the rooms', async () => {
      vi.useFakeTimers({ shouldAdvanceTime: true });
      roomApi.search.mockRejectedValueOnce(new ApiError(503, 'Request failed with status 503'));
      renderRoute(<RoomsPage />, { path: '/rooms' });

      expect(await screen.findByText(/server may still be waking up/)).toBeInTheDocument();
      expect(screen.queryByText('Request failed with status 503')).not.toBeInTheDocument();
      await vi.advanceTimersByTimeAsync(WAKE_RETRY_DELAY_MS);

      expect(await screen.findAllByRole('heading', { name: 'GrandVista Marina Bay' })).toHaveLength(2);
      expect(roomApi.search).toHaveBeenCalledTimes(2);
    });

    it('gives up after a bounded number of retries and offers Try again', async () => {
      vi.useFakeTimers({ shouldAdvanceTime: true });
      roomApi.search.mockRejectedValue(new ApiError(503, 'Request failed with status 503'));
      renderRoute(<RoomsPage />, { path: '/rooms' });
      await screen.findByText(/server may still be waking up/);

      for (let i = 0; i < WAKE_MAX_RETRIES; i += 1) await vi.advanceTimersByTimeAsync(WAKE_RETRY_DELAY_MS);

      expect(await screen.findByText(/could not be loaded right now/)).toBeInTheDocument();
      expect(screen.queryByText(/still waking up/)).not.toBeInTheDocument();   // no claim about the cause
      expect(roomApi.search).toHaveBeenCalledTimes(WAKE_MAX_RETRIES + 1);
      expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
    });

    it.each([[401, 'Unauthorized'], [403, 'Forbidden'], [404, 'Not found']])('does not retry a %i', async (status, message) => {
      roomApi.search.mockRejectedValue(new ApiError(status, message));
      renderRoute(<RoomsPage />, { path: '/rooms' });

      expect(await screen.findByText(message)).toBeInTheDocument();
      expect(roomApi.search).toHaveBeenCalledTimes(1);
      expect(screen.queryByText(/server may still be waking up/)).not.toBeInTheDocument();
    });

    it('stops retrying and fires no stale timer after the page is closed', async () => {
      vi.useFakeTimers({ shouldAdvanceTime: true });
      roomApi.search.mockRejectedValue(new ApiError(503, 'Request failed with status 503'));
      const { unmount } = renderRoute(<RoomsPage />, { path: '/rooms' });
      await screen.findByText(/server may still be waking up/);
      unmount();

      await vi.advanceTimersByTimeAsync(WAKE_RETRY_DELAY_MS * 3);
      expect(roomApi.search).toHaveBeenCalledTimes(1);
    });

    it('drops the old retry loop when the filters change and shows only the new result', async () => {
      vi.useFakeTimers({ shouldAdvanceTime: true });
      roomApi.search.mockRejectedValueOnce(new ApiError(503, 'Request failed with status 503'));
      roomApi.search.mockResolvedValue([room(7, { roomNumber: '777' })]);
      renderRoute(<RoomsPage />, { path: '/rooms' });
      await screen.findByText(/server may still be waking up/);

      await userEvent.selectOptions(screen.getByLabelText('Room type'), 'SUITE');
      await userEvent.click(screen.getByRole('button', { name: 'Search' }));
      expect(await screen.findByText(/777/)).toBeInTheDocument();
      const callsAfterChange = roomApi.search.mock.calls.length;

      await vi.advanceTimersByTimeAsync(WAKE_RETRY_DELAY_MS * 2);
      expect(roomApi.search).toHaveBeenCalledTimes(callsAfterChange);   // the old loop did not fire again
      expect(screen.queryByText(/server may still be waking up/)).not.toBeInTheDocument();
    });

    it('keeps showing rooms when the hotel list fails, and fetches hotels again once rooms load', async () => {
      hotelApi.list.mockRejectedValueOnce(new ApiError(503, 'Request failed with status 503'));
      renderRoute(<RoomsPage />, { path: '/rooms' });

      expect(await screen.findAllByRole('heading', { name: 'GrandVista Marina Bay' })).toHaveLength(2);
      await waitFor(() => expect(hotelApi.list).toHaveBeenCalledTimes(2));
      expect(screen.queryByText(/Request failed/)).not.toBeInTheDocument();
    });

    it('shows other errors immediately without retrying', async () => {
      roomApi.search.mockRejectedValue(new ApiError(500, 'Boom'));
      renderRoute(<RoomsPage />, { path: '/rooms' });

      expect(await screen.findByText('Boom')).toBeInTheDocument();
      expect(roomApi.search).toHaveBeenCalledTimes(1);
    });
  });
});
