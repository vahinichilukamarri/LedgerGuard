package com.ledgerguard.settlement;

/**
 * Ways to make the external world disagree with the ledger, on demand.
 *
 * <p>These exist so each discrepancy type can be demonstrated deliberately
 * rather than waited for. Each one maps to exactly one
 * {@code DiscrepancyType} that reconciliation should then find.
 */
public enum FaultType {

    /** Withdraw the external record entirely. Reconciliation should report MISSING_SETTLEMENT. */
    DROP_SETTLEMENT,

    /** Restate the external amount by a delta. Should report AMOUNT_MISMATCH. */
    RESTATE_AMOUNT,

    /** Add a second external record for the same transaction. Should report DUPLICATE_SETTLEMENT. */
    DUPLICATE_SETTLEMENT,

    /** Move the external record to PENDING or FAILED. Should report STATUS_MISMATCH. */
    CHANGE_STATUS,

    /** Invent an external movement referencing nothing internal. Should report UNEXPECTED_EXTERNAL_TRANSACTION. */
    PHANTOM_SETTLEMENT
}
