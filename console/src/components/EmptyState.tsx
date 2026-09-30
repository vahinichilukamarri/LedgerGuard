import type { ReactNode } from 'react';
import { Icon, type IconName } from './icons/Icon';

/**
 * A genuine zero-data first-run state: icon, title, one line of context, and
 * an optional call to action straight out of the empty state (e.g. "No
 * accounts yet" → "+ Create account"). `DataTable`'s own bare `.empty` stays
 * in use for "no rows match this filter" — a CTA doesn't make sense there,
 * since narrowing the filter is the fix, not creating something.
 */
export function EmptyState({
  icon = 'empty',
  title,
  children,
  action,
}: {
  icon?: IconName;
  title: string;
  children?: ReactNode;
  action?: ReactNode;
}) {
  return (
    <div className="empty-state">
      <div className="empty-state-icon" aria-hidden="true">
        <Icon name={icon} />
      </div>
      <p className="empty-state-title">{title}</p>
      {children && <p className="empty-state-body">{children}</p>}
      {action && <div className="empty-state-actions">{action}</div>}
    </div>
  );
}
