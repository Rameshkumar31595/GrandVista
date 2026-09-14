import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { authApi } from '../api/endpoints';
import { Alert, FieldError } from '../components/common';

export default function RegisterPage() {
  const navigate = useNavigate();
  const [form, setForm] = useState({ name: '', email: '', password: '', confirmPassword: '' });
  const [error, setError] = useState(null);
  const [fieldErrors, setFieldErrors] = useState({});
  const [submitting, setSubmitting] = useState(false);

  const submit = async (event) => {
    event.preventDefault();
    setError(null);
    setFieldErrors({});
    if (form.password !== form.confirmPassword) {
      setFieldErrors({ confirmPassword: 'Passwords do not match' });
      return;
    }
    setSubmitting(true);
    try {
      // No role is sent: the Auth Service always creates ROLE_USER accounts.
      await authApi.register({ name: form.name, email: form.email, password: form.password });
      navigate('/login', { state: { registered: true } });
    } catch (err) {
      setError(err.message);
      setFieldErrors(err.validationErrors);
    } finally {
      setSubmitting(false);
    }
  };

  const update = (field) => (e) => setForm({ ...form, [field]: e.target.value });

  return (
    <div className="auth-page">
      <form className="card auth-card" onSubmit={submit} noValidate>
        <h1>Create your account</h1>
        <p className="muted">Book rooms at any GrandVista property.</p>
        <Alert>{error}</Alert>

        <label>Full name<input value={form.name} onChange={update('name')} required /></label>
        <FieldError errors={fieldErrors} name="name" />
        <label>Email<input type="email" autoComplete="username" value={form.email} onChange={update('email')} required /></label>
        <FieldError errors={fieldErrors} name="email" />
        <label>Password<input type="password" autoComplete="new-password" value={form.password} onChange={update('password')} required /></label>
        <small className="hint">At least 8 characters, including a letter and a digit.</small>
        <FieldError errors={fieldErrors} name="password" />
        <label>Confirm password<input type="password" autoComplete="new-password" value={form.confirmPassword} onChange={update('confirmPassword')} required /></label>
        <FieldError errors={fieldErrors} name="confirmPassword" />

        <button className="btn btn-primary btn-block" type="submit" disabled={submitting}>
          {submitting ? 'Creating account…' : 'Register'}
        </button>
        <p className="small center">Already registered? <Link to="/login">Sign in</Link></p>
      </form>
    </div>
  );
}
