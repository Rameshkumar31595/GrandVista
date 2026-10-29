import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { setUnauthorizedHandler } from '../api/client';
import { authApi } from '../api/endpoints';
import { tokenExpiry, tokenStorage } from '../api/tokenStorage';

const AuthContext = createContext(null);

function loadInitialSession() {
  const token = tokenStorage.getToken();
  const user = tokenStorage.getUser();
  const expiry = token ? tokenExpiry(token) : null;
  if (!token || !user || (expiry && expiry <= Date.now())) {
    tokenStorage.clear();
    return { token: null, user: null };
  }
  return { token, user };
}

export function AuthProvider({ children }) {
  const navigate = useNavigate();
  const [session, setSession] = useState(loadInitialSession);
  const [notice, setNotice] = useState(null);

  const logout = useCallback((reason) => {
    tokenStorage.clear();
    setSession({ token: null, user: null });
    if (reason) {
      setNotice(reason);
      navigate('/login', { replace: true });
    }
  }, [navigate]);

  const startSession = useCallback((response) => {
    tokenStorage.save(response.accessToken, response.user);
    setSession({ token: response.accessToken, user: response.user });
    setNotice(null);
    return response.user;
  }, []);

  const login = useCallback(async (email, password) => startSession(await authApi.login({ email, password })),
    [startSession]);

  // Review demo accounts: the server decides whether this is allowed; the UI never holds a password or token.
  // On a public deployment the reviewer's access code (typed in on the login page) is passed along.
  const demoLogin = useCallback(async (accountId, accessCode) => startSession(
    await (accessCode ? authApi.demoLogin(accountId, accessCode) : authApi.demoLogin(accountId))), [startSession]);

  // Any 401 on an authenticated request (expired / tampered token) ends the session.
  useEffect(() => {
    setUnauthorizedHandler((message) => logout(message || 'Your session has expired. Please log in again.'));
    return () => setUnauthorizedHandler(null);
  }, [logout]);

  // Proactively log out when the token's "exp" time is reached.
  useEffect(() => {
    if (!session.token) return undefined;
    const expiry = tokenExpiry(session.token);
    if (!expiry) return undefined;
    const timer = setTimeout(() => logout('Your session has expired. Please log in again.'),
      Math.max(expiry - Date.now(), 0));
    return () => clearTimeout(timer);
  }, [session.token, logout]);

  const value = useMemo(() => ({
    user: session.user,
    isAuthenticated: Boolean(session.token),
    isAdmin: session.user?.role === 'ROLE_ADMIN',
    isGuest: session.user?.role === 'ROLE_USER',
    login,
    demoLogin,
    logout,
    notice,
    clearNotice: () => setNotice(null),
  }), [session, login, demoLogin, logout, notice]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) throw new Error('useAuth must be used inside AuthProvider');
  return context;
}
