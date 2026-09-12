import { Link } from 'react-router-dom';

export function NotFoundPage() {
  return (
    <section className="card">
      <h2>No such page</h2>
      <p className="card-note">
        The console has three screens: the <Link to="/anomalies">ranking</Link>, an account, and the{' '}
        <Link to="/model">model</Link>.
      </p>
    </section>
  );
}
