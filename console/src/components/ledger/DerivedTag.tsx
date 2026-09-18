/**
 * Marks a figure as computed on read rather than stored — currently just the
 * account balance, which `Account.java` deliberately has no column for: it is
 * the sum of that account's postings, recomputed on every request. The tag
 * and its tooltip echo that reasoning so the distinction from the immutable
 * postings list underneath is visible, not just implied by layout.
 */
export function DerivedTag() {
  return (
    <span
      className="derived-tag"
      title="Not stored. Recomputed by summing this account's postings on every request, so it can never disagree with the ledger that produced it."
    >
      Derived
    </span>
  );
}
