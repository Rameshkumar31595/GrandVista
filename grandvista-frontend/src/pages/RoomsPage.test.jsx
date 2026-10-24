import { fireEvent, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { hotelApi, roomApi } from '../api/endpoints';
import { renderRoute } from '../test/helpers';
import RoomsPage from './RoomsPage';

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
});
