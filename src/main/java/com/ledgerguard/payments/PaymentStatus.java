package com.ledgerguard.payments;

/**
 * A payment is created PENDING and becomes POSTED once its balanced transaction
 * has been written. There is no failure state: a rejected payment never commits
 * at all, because the whole flow runs in one database transaction and rolls back
 * together.
 *
 * <p>REVERSED is the third state, added in Phase 6. It exists so that reversing
 * a payment's transaction is visible on the payment itself, and not only in the
 * {@code reversals} table. Without it the refund path had no way to know the
 * money had already gone back, and would happily return it a second time — see
 * the bug log in README.md.
 */
public enum PaymentStatus {
    PENDING,
    POSTED,
    REVERSED
}
