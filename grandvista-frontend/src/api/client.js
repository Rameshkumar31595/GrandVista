import { tokenStorage } from './tokenStorage';

// Single place where the API Gateway URL is defined.
export const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api').replace(/\/$/, '');

export class ApiError extends Error {
  constructor(status, message, validationErrors = {}) {
    super(message);
    this.status = status;
    this.validationErrors = validationErrors || {};
  }
}

const PUBLIC_AUTH_PATHS = new Set(['/auth/login', '/auth/register']);

let unauthorizedHandler = null;

/** Registered by AuthContext: called when an authenticated request is rejected with 401 (expired/invalid JWT). */
export function setUnauthorizedHandler(handler) {
  unauthorizedHandler = handler;
}

/**
 * Reusable fetch wrapper: adds the Bearer token, serialises JSON, and converts the backend's
 * {timestamp, status, error, message, path, validationErrors} body into an ApiError.
 */
export async function apiRequest(path, { method = 'GET', body, query } = {}) {
  const url = new URL(API_BASE_URL + path);
  Object.entries(query || {}).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      url.searchParams.set(key, value);
    }
  });

  // Login and registration are public: never attach a stored token to them. Otherwise a failed login made while an
  // old session still sits in storage (second tab, mistyped password) would come back as a 401 that is mistaken for
  // "your session expired" and would log the user out everywhere.
  const token = PUBLIC_AUTH_PATHS.has(path) ? null : tokenStorage.getToken();
  const headers = { Accept: 'application/json' };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (token) headers.Authorization = `Bearer ${token}`;

  let response;
  try {
    response = await fetch(url, { method, headers, body: body !== undefined ? JSON.stringify(body) : undefined });
  } catch {
    throw new ApiError(0, `Cannot reach the API Gateway at ${API_BASE_URL}. Is it running?`);
  }

  if (response.status === 204) return null;
  const data = await response.json().catch(() => null);

  if (!response.ok) {
    if (response.status === 401 && token && unauthorizedHandler) {
      unauthorizedHandler(data?.message);
    }
    throw new ApiError(response.status, data?.message || `Request failed with status ${response.status}`,
      data?.validationErrors);
  }
  return data;
}
