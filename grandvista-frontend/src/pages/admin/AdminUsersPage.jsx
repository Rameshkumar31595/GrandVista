import { useCallback, useEffect, useState } from 'react';
import { authApi } from '../../api/endpoints';
import { Alert, Loading, formatDateTime } from '../../components/common';
import { useAuth } from '../../context/AuthContext';

/** The only way to create additional administrators: an existing admin promotes a registered user. */
export default function AdminUsersPage() {
  const { user: currentUser } = useAuth();
  const [users, setUsers] = useState(null);
  const [error, setError] = useState(null);
  const [message, setMessage] = useState(null);

  const load = useCallback(() => authApi.listUsers().then(setUsers).catch((err) => setError(err.message)), []);
  useEffect(() => { load(); }, [load]);

  const changeRole = async (target, role) => {
    if (!window.confirm(`Change ${target.email} to ${role}?`)) return;
    setError(null);
    try {
      await authApi.changeRole(target.id, role);
      setMessage(`${target.email} is now ${role}.`);
      load();
    } catch (err) {
      setError(err.message);
    }
  };

  if (!users) return <div className="container section">{error ? <Alert>{error}</Alert> : <Loading />}</div>;

  return (
    <div className="container section">
      <h1>Users</h1>
      <p className="muted">Public registration always creates ROLE_USER. Administrators can promote or demote accounts here.</p>
      <Alert type="success" onClose={() => setMessage(null)}>{message}</Alert>
      <Alert>{error}</Alert>
      <div className="table-wrap card">
        <table className="table">
          <thead><tr><th>#</th><th>Name</th><th>Email</th><th>Role</th><th>Registered</th><th /></tr></thead>
          <tbody>
            {users.map((account) => (
              <tr key={account.id}>
                <td>{account.id}</td><td>{account.name}</td><td>{account.email}</td>
                <td><span className={`role-chip ${account.role === 'ROLE_ADMIN' ? 'admin' : ''}`}>{account.role}</span></td>
                <td>{formatDateTime(account.createdAt)}</td>
                <td>{account.email !== currentUser.email && (
                  account.role === 'ROLE_USER'
                    ? <button className="btn btn-outline btn-sm" type="button" onClick={() => changeRole(account, 'ROLE_ADMIN')}>Make admin</button>
                    : <button className="btn btn-outline btn-sm" type="button" onClick={() => changeRole(account, 'ROLE_USER')}>Make user</button>
                )}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
