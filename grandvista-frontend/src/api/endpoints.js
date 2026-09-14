import { apiRequest } from './client';

// Every backend call used by the UI, grouped by microservice. Paths are relative to VITE_API_BASE_URL
// (the API Gateway), which routes /auth, /rooms, /hotels, /pricing-rules, /bookings and /payments.

export const authApi = {
  register: (data) => apiRequest('/auth/register', { method: 'POST', body: data }),
  login: (data) => apiRequest('/auth/login', { method: 'POST', body: data }),
  me: () => apiRequest('/auth/me'),
  listUsers: () => apiRequest('/auth/users'),
  changeRole: (userId, role) => apiRequest(`/auth/users/${userId}/role`, { method: 'PATCH', body: { role } }),
};

export const hotelApi = {
  list: () => apiRequest('/hotels'),
  create: (data) => apiRequest('/hotels', { method: 'POST', body: data }),
  update: (id, data) => apiRequest(`/hotels/${id}`, { method: 'PUT', body: data }),
};

export const roomApi = {
  search: (criteria) => apiRequest('/rooms', { query: criteria }),
  get: (id) => apiRequest(`/rooms/${id}`),
  availability: (id, checkIn, checkOut) => apiRequest(`/rooms/${id}/availability`, { query: { checkIn, checkOut } }),
  price: (id, checkIn, checkOut) => apiRequest(`/rooms/${id}/price`, { query: { checkIn, checkOut } }),
  // admin
  listAll: () => apiRequest('/rooms/all'),
  inventorySummary: () => apiRequest('/rooms/inventory-summary'),
  holds: (id) => apiRequest(`/rooms/${id}/holds`),
  create: (data) => apiRequest('/rooms', { method: 'POST', body: data }),
  update: (id, data) => apiRequest(`/rooms/${id}`, { method: 'PUT', body: data }),
  updatePrice: (id, basePrice) => apiRequest(`/rooms/${id}/price`, { method: 'PATCH', body: { basePrice } }),
  updateStatus: (id, status) => apiRequest(`/rooms/${id}/status`, { method: 'PATCH', body: { status } }),
  deactivate: (id) => apiRequest(`/rooms/${id}`, { method: 'DELETE' }),
};

export const pricingApi = {
  list: () => apiRequest('/pricing-rules'),
  create: (data) => apiRequest('/pricing-rules', { method: 'POST', body: data }),
  update: (id, data) => apiRequest(`/pricing-rules/${id}`, { method: 'PUT', body: data }),
  remove: (id) => apiRequest(`/pricing-rules/${id}`, { method: 'DELETE' }),
};

export const bookingApi = {
  create: (data) => apiRequest('/bookings', { method: 'POST', body: data }),
  pay: (id, data) => apiRequest(`/bookings/${id}/pay`, { method: 'POST', body: data }),
  cancel: (id) => apiRequest(`/bookings/${id}/cancel`, { method: 'POST' }),
  get: (id) => apiRequest(`/bookings/${id}`),
  mine: () => apiRequest('/bookings/my'),
  all: (status) => apiRequest('/bookings', { query: { status } }),
};

export const paymentApi = {
  mine: () => apiRequest('/payments/my'),
  forBooking: (bookingId) => apiRequest(`/payments/booking/${bookingId}`),
  all: () => apiRequest('/payments'),
};
