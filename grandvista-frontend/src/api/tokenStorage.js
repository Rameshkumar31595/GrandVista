// Stores the JWT and the non-sensitive user profile returned at login.
// localStorage keeps the session across page reloads for the demo; see docs/review-1/06-jwt-authentication.md
// for the XSS trade-off and the production alternative (httpOnly cookies).
const TOKEN_KEY = 'grandvista.token';
const USER_KEY = 'grandvista.user';

export const tokenStorage = {
  getToken: () => localStorage.getItem(TOKEN_KEY),
  getUser: () => {
    const raw = localStorage.getItem(USER_KEY);
    try {
      return raw ? JSON.parse(raw) : null;
    } catch {
      return null;
    }
  },
  save: (token, user) => {
    localStorage.setItem(TOKEN_KEY, token);
    localStorage.setItem(USER_KEY, JSON.stringify(user));
  },
  clear: () => {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
  },
};

/** Reads the "exp" claim (seconds) from a JWT without verifying it - verification is the server's job. */
export function tokenExpiry(token) {
  try {
    const payload = JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
    return payload.exp ? payload.exp * 1000 : null;
  } catch {
    return null;
  }
}
