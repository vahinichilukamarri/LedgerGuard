import { Link } from 'react-router-dom';
import { PageHeader } from '../components/PageHeader';

export function NotFoundPage() {
  return (
    <>
      <PageHeader icon="empty" title="Page not found" description="Nothing in the console lives at this address." />
      <section className="card">
        <h2>No such page</h2>
        <p className="card-note">
          Start from the <Link to="/overview">overview</Link>, or pick a section from the sidebar.
        </p>
      </section>
    </>
  );
}
