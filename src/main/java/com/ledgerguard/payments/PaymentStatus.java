package com.ledgerguard.payments;

/**
 * Phase 1 has exactly two states. A payment is created PENDING, and becomes
 * POSTED once its balanced transaction has been written. There is no failure
 * state here because a rejected payment never commits at all: the whole flow
 * runs in one database transaction and rolls back together.
 */
public enum PaymentStatus {
    PENDING,
    POSTED
}
