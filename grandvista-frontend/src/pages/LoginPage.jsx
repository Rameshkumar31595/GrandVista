import { useEffect, useRef, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { authApi } from '../api/endpoints';
import { Alert } from '../components/common';
import { useAuth } from '../context/AuthContext';

// Public deployments: the reviewer access code lives only in this browser tab (sessionStorage), never in the build.
const DEMO_CODE_KEY = 'grandvista.demoAccessCode';
const storedCode = () => { try { return sessionStorage.getItem(DEMO_CODE_KEY) || ''; } catch { return ''; } };
const storeCode = (code) => { try { sessionStorage.setItem(DEMO_CODE_KEY, code); } catch { /* storage unavailable */ } };
const forgetCode = () => { try { sessionStorage.removeItem(DEMO_CODE_KEY); } catch { /* storage unavailable */ } };

export default function LoginPage() {
  const { login, demoLogin, notice, clearNotice } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [form, setForm] = useState({ email: '', password: '' });
  const [error, setError] = useState(null);
  const [submitting, setSubmitting] = useState(false);
  // Demo accounts exist only when the server was started with DEMO_MODE_ENABLED=true.
  // 403 = demo mode disabled on this server, so the tab stays hidden; 401/429 = "this server wants
  // a reviewer access code first" (public deployments); anything else (502/503/network while a
  // free-tier service wakes up) is transient - keep the tab and offer a retry instead of hiding it.
  const [demoAccounts, setDemoAccounts] = useState([]);
  const [demoLocked, setDemoLocked] = useState(false);
  const [demoError, setDemoError] = useState(false);
  // true from the first render until the first answer: on a sleeping free-tier server that answer can take a minute
  // (the gateway retries for ~55s), and the tab must not be missing meanwhile.
  const [demoLoading, setDemoLoading] = useState(true);
  const mounted = useRef(true);
  const [accessCode, setAccessCode] = useState('');
  const [codeInput, setCodeInput] = useState('');
  const [mode, setMode] = useState('normal');

  const loadDemoAccounts = () => {
    setDemoError(false);
    setDemoLoading(true);
    const code = storedCode();
    return (code ? authApi.demoAccounts(code) : authApi.demoAccounts())
      .then((accounts) => {
        if (!mounted.current) return;
        if (Array.isArray(accounts)) {
          setDemoAccounts(accounts);
          setAccessCode(code);
          setDemoLocked(false);
        }
      })
      .catch((err) => {
        if (!mounted.current) return;
        if (err?.status === 401 || err?.status === 429) {
          forgetCode();
          setDemoLocked(true);
        } else if (err?.status !== 403) {
          setDemoError(true);
        }
      })
      .finally(() => { if (mounted.current) setDemoLoading(false); });
  };

  useEffect(() => {
    mounted.current = true;
    loadDemoAccounts();
    return () => { mounted.current = false; };
  }, []);

  const unlockDemo = async (event) => {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    const code = codeInput.trim();
    try {
      const accounts = await authApi.demoAccounts(code);
      storeCode(code);
      setAccessCode(code);
      setDemoAccounts(Array.isArray(accounts) ? accounts : []);
      setDemoLocked(false);
      setCodeInput('');
    } catch (err) {
      setError(err.status === 401 ? 'That reviewer access code was not accepted.' : err.message);
    } finally {
      setSubmitting(false);
    }
  };

  const finish = (user) => {
    const fallback = user.role === 'ROLE_ADMIN' ? '/admin' : '/rooms';
    navigate(location.state?.from || fallback, { replace: true });
  };

  const continueAs = async (account) => {
    setError(null);
    setSubmitting(true);
    try {
      finish(await (accessCode ? demoLogin(account.id, accessCode) : demoLogin(account.id)));
    } catch (err) {
      if (err.status === 401 && accessCode) {
        // the code was changed or revoked on the server: ask for it again
        forgetCode();
        setAccessCode('');
        setDemoAccounts([]);
        setDemoLocked(true);
      }
      setError(err.message);
    } finally {
      setSubmitting(false);
    }
  };

  const submit = async (event) => {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      finish(await login(form.email, form.password));
    } catch (err) {
      setError(err.message);
    } finally {
      setSubmitting(false);
    }
  };

  const showDemo = demoAccounts.length > 0 || demoLocked || demoError || demoLoading;

  return (
    <div className="auth-page">
      <div className={`card auth-card${mode === 'demo' ? ' auth-card-wide' : ''}`}>
        <h1>Welcome back</h1>
        <p className="muted">Sign in to book and manage your stays.</p>
        <Alert type="info" onClose={clearNotice}>{notice}</Alert>
        <Alert>{error}</Alert>
        {location.state?.registered && <Alert type="success">Account created. Please sign in.</Alert>}

        {showDemo && (
          <div className="auth-tabs" role="tablist" aria-label="Sign-in method">
            <button type="button" role="tab" aria-selected={mode === 'normal'}
              className={mode === 'normal' ? 'active' : ''} onClick={() => setMode('normal')}>
              Normal Login
            </button>
            <button type="button" role="tab" aria-selected={mode === 'demo'}
              className={mode === 'demo' ? 'active' : ''} onClick={() => setMode('demo')}>
              Demo Login
            </button>
          </div>
        )}

        {mode === 'demo' && demoLoading && demoAccounts.length === 0 && !demoLocked && !demoError ? (
          <div className="demo-panel" role="tabpanel">
            <p className="small muted" role="status">
              Checking the demo accounts - the server may be waking up. This can take a while, please wait.
            </p>
          </div>
        ) : mode === 'demo' && showDemo && demoAccounts.length === 0 && demoError && !demoLocked ? (
          <div className="demo-panel" role="tabpanel">
            <p className="small muted">
              Demo accounts cannot be reached right now - the server may still be waking up. Try again in a moment.
            </p>
            <button type="button" className="btn btn-primary btn-block" onClick={loadDemoAccounts}>
              Try again
            </button>
          </div>
        ) : mode === 'demo' && showDemo && demoAccounts.length === 0 ? (
          <form className="auth-form demo-panel" role="tabpanel" onSubmit={unlockDemo}>
            <p className="small muted">
              This is a public deployment: enter the reviewer access code you were given to unlock the demo accounts.
            </p>
            <label>
              Reviewer access code
              <input type="password" autoComplete="off" value={codeInput}
                onChange={(e) => setCodeInput(e.target.value)} required />
            </label>
            <button className="btn btn-primary btn-block" type="submit" disabled={submitting || !codeInput.trim()}>
              {submitting ? 'Checking…' : 'Unlock demo accounts'}
            </button>
          </form>
        ) : mode === 'demo' && showDemo ? (
          <div className="demo-panel" role="tabpanel">
            <p className="small muted">Review mode: pick a ready-made account, no password needed.</p>
            <div className="demo-grid">
              {demoAccounts.map((account) => {
                const admin = account.role === 'ROLE_ADMIN';
                return (
                  <article key={account.id} className="demo-card" data-testid={`demo-${account.id}`}>
                    <div className="demo-card-head">
                      <h3>{account.displayName}</h3>
                      <span className={`role-chip${admin ? ' admin' : ''}`}>{admin ? 'Administrator' : 'Guest'}</span>
                    </div>
                    <p className="small muted">{account.description}</p>
                    <button type="button" className={`btn btn-block ${admin ? 'btn-gold' : 'btn-primary'}`}
                      disabled={submitting} onClick={() => continueAs(account)}
                      aria-label={`Continue as ${account.displayName}`}>
                      {submitting ? 'Signing in…' : `Continue as ${account.displayName}`}
                    </button>
                  </article>
                );
              })}
            </div>
          </div>
        ) : (
          <form className="auth-form" onSubmit={submit}>
            <label>
              Email
              <input type="email" autoComplete="username" value={form.email}
                onChange={(e) => setForm({ ...form, email: e.target.value })} required />
            </label>
            <label>
              Password
              <input type="password" autoComplete="current-password" value={form.password}
                onChange={(e) => setForm({ ...form, password: e.target.value })} required />
            </label>
            <button className="btn btn-primary btn-block" type="submit" disabled={submitting}>
              {submitting ? 'Signing in…' : 'Sign in'}
            </button>
            <p className="small center">New to GrandVista? <Link to="/register">Create an account</Link></p>
          </form>
        )}
      </div>
    </div>
  );
}
