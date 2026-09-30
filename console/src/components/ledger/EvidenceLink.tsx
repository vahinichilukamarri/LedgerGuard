import { useState } from 'react';
import { shortId } from '../../format';
import { Icon } from '../icons/Icon';

/**
 * A UUID as evidence: shortened for the row, the full id on hover, and a
 * one-click copy — because "which transaction" is usually answered by
 * pasting the id somewhere else (a `psql` prompt, a support ticket), not by
 * reading it.
 */
export function EvidenceLink({ id, label }: { id: string; label?: string }) {
  const [copied, setCopied] = useState(false);

  async function copy() {
    try {
      await navigator.clipboard.writeText(id);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1200);
    } catch {
      // Clipboard access can be denied by the browser; the full id is still
      // reachable via the title attribute either way.
    }
  }

  return (
    <span className="evidence-link">
      <span className="evidence-id" title={id}>
        {label ?? shortId(id)}
      </span>
      <button type="button" className="evidence-copy" onClick={() => void copy()} aria-label="Copy full id">
        <Icon name={copied ? 'check' : 'copy'} />
      </button>
    </span>
  );
}
