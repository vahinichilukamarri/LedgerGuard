import type { ReactNode } from 'react';
import { Icon, type IconName } from './icons/Icon';

/**
 * The one page-header shape, used everywhere in scope: an icon, a title, a
 * one-line description, and — when the page has one — a primary action
 * aligned to the right. Replaces the `<h1>…</h1><p className="card-note"
 * style={{ marginBottom: 20 }}>` pattern that used to be repeated, slightly
 * differently, on every page.
 */
export function PageHeader({
  icon,
  title,
  description,
  action,
  admin = false,
}: {
  icon: IconName;
  title: string;
  description?: ReactNode;
  action?: ReactNode;
  /** The amber admin treatment, for the one section (Simulation) that's deliberately walled off. */
  admin?: boolean;
}) {
  return (
    <div className={admin ? 'page-header page-header-admin' : 'page-header'}>
      <div className="page-header-icon" aria-hidden="true">
        <Icon name={icon} />
      </div>
      <div className="page-header-text">
        <h1 className="page-header-title">{title}</h1>
        {description && <p className="page-header-description">{description}</p>}
      </div>
      {action && <div className="page-header-actions">{action}</div>}
    </div>
  );
}
