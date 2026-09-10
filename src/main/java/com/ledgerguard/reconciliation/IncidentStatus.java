package com.ledgerguard.reconciliation;

public enum IncidentStatus {

    /** Detected and awaiting a human decision. */
    OPEN,

    /** Someone has dealt with it. Kept rather than deleted, because what went wrong is itself a record. */
    RESOLVED
}
