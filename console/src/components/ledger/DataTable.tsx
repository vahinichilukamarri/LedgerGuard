import type { ReactNode } from 'react';
import { Empty } from '../States';

export interface Column<T> {
  key: string;
  header: string;
  render: (row: T) => ReactNode;
  className?: string;
}

/**
 * The one table shell for `/ledger`, `/reconciliation` and `/simulation`.
 * Paging is optional: pass `page`/`totalPages`/`onPageChange` for a
 * `PageResponse`-backed table, or omit them for an unbounded list (the
 * reconciliation incidents endpoint has no pagination — see the API
 * limitations noted in the implementation plan).
 */
export function DataTable<T>({
  columns,
  rows,
  rowKey,
  page,
  totalPages,
  totalElements,
  onPageChange,
  emptyMessage,
  loading = false,
}: {
  columns: Column<T>[];
  rows: T[];
  rowKey: (row: T) => string;
  page?: number;
  totalPages?: number;
  totalElements?: number;
  onPageChange?: (page: number) => void;
  emptyMessage: ReactNode;
  /** Renders skeleton rows matching the column count instead of the caller pre-empting it with `<Pending>`. Grey/neutral only — never a category or severity hue, so a loading row can never be misread as a state signal. */
  loading?: boolean;
}) {
  if (loading) {
    return (
      <div className="table-scroll">
        <table className="data">
          <thead>
            <tr>
              {columns.map((column) => (
                <th key={column.key}>{column.header}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {Array.from({ length: 4 }).map((_, rowIndex) => (
              <tr key={rowIndex}>
                {columns.map((column) => (
                  <td key={column.key}>
                    <span className="skeleton skeleton-line" />
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    );
  }

  if (rows.length === 0) {
    return <Empty>{emptyMessage}</Empty>;
  }

  const showPager = page !== undefined && totalPages !== undefined && onPageChange && totalPages > 1;

  return (
    <>
      {totalElements !== undefined && (
        <div className="results-meta">
          <strong>{totalElements}</strong> {totalElements === 1 ? 'row' : 'rows'} total
        </div>
      )}
      <div className="table-scroll">
        <table className="data">
          <thead>
            <tr>
              {columns.map((column) => (
                <th key={column.key}>{column.header}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={rowKey(row)}>
                {columns.map((column) => (
                  <td key={column.key} className={column.className}>
                    {column.render(row)}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {showPager && (
        <div className="pager">
          <button type="button" disabled={page === 0} onClick={() => onPageChange(page - 1)}>
            Previous
          </button>
          <span>
            Page {page + 1} of {totalPages}
          </span>
          <button type="button" disabled={page + 1 >= totalPages} onClick={() => onPageChange(page + 1)}>
            Next
          </button>
        </div>
      )}
    </>
  );
}
