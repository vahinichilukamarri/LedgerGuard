package com.ledgerguard.reconciliation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The comparison engine, as a pure function.
 *
 * <p>No Spring, no database, no clock. Feed it two lists and it returns one
 * classification per comparison. That is deliberate: deciding what counts as a
 * discrepancy is the interesting part of this phase, and it should be provable
 * without standing anything up.
 *
 * <h2>The matching key</h2>
 *
 * An external record is paired to an internal transaction by
 * {@code externalReference == transactionId}. That is the processor's echo of
 * our own reference, which is how real settlement files correlate.
 *
 * <p>Ambiguity in that key is not an edge case to be defended against; it
 * <em>is</em> three of the six outcomes. No external record for a transaction
 * is {@code MISSING_SETTLEMENT}. Two or more is {@code DUPLICATE_SETTLEMENT}.
 * A reference that is blank, unparseable, or names a transaction we do not have
 * is {@code UNEXPECTED_EXTERNAL_TRANSACTION}.
 */
public final class Reconciler {

    /** The external state we expect to see opposite an internal transaction that committed. */
    public static final String EXPECTED_EXTERNAL_STATUS = "SETTLED";

    /** An internal transaction exists only if it committed, so its state is always this. */
    public static final String INTERNAL_STATUS_POSTED = "POSTED";

    /** One side of the comparison: what LedgerGuard believes. */
    public record InternalTransaction(UUID transactionId, long amountMinor, String currency) {
    }

    /** The other side: what the processor reports. */
    public record ExternalRecord(UUID settlementRecordId, String externalId, String externalReference,
                                 long amountMinor, String currency, String status) {
    }

    /** One classification, carrying its own evidence. */
    public record Comparison(
            DiscrepancyType type,
            Severity severity,
            UUID transactionId,
            UUID settlementRecordId,
            Long internalAmountMinor,
            Long externalAmountMinor,
            long differenceMinor,
            String currency,
            String internalStatus,
            String externalStatus,
            String detail) {
    }

    /**
     * Compare the two worlds.
     *
     * <p>The returned list includes {@code MATCHED} entries so a run can report
     * how much agreed. Persisting them is a separate decision, made by the
     * service, which does not.
     */
    public List<Comparison> reconcile(List<InternalTransaction> internal, List<ExternalRecord> external) {
        Map<String, List<ExternalRecord>> byReference = new LinkedHashMap<>();
        for (ExternalRecord record : external) {
            String key = normalise(record.externalReference());
            if (key != null) {
                byReference.computeIfAbsent(key, k -> new ArrayList<>()).add(record);
            }
        }

        List<Comparison> results = new ArrayList<>();
        Set<String> internalKeys = new HashSet<>();

        for (InternalTransaction transaction : internal) {
            String key = transaction.transactionId().toString();
            internalKeys.add(key);
            results.add(classify(transaction, byReference.getOrDefault(key, List.of())));
        }

        // Anything the processor knows about that we do not. A blank reference
        // lands here too: unusable is the same as unmatched, and the alternative
        // would be silently dropping a real movement of money.
        for (ExternalRecord record : external) {
            String key = normalise(record.externalReference());
            if (key == null || !internalKeys.contains(key)) {
                results.add(unexpected(record));
            }
        }

        return results;
    }

    private Comparison classify(InternalTransaction transaction, List<ExternalRecord> candidates) {
        if (candidates.isEmpty()) {
            return missing(transaction);
        }
        if (candidates.size() > 1) {
            return duplicate(transaction, candidates);
        }
        ExternalRecord record = candidates.get(0);

        boolean sameCurrency = transaction.currency().equalsIgnoreCase(record.currency());
        long difference = transaction.amountMinor() - record.amountMinor();

        // Amount before status, deliberately. Exactly one classification is
        // allowed, and when both differ the money is the more actionable fact.
        // A differing currency is treated the same way: the amounts are not
        // comparable, so they do not agree.
        if (difference != 0 || !sameCurrency) {
            return amountMismatch(transaction, record, difference, sameCurrency);
        }
        if (!EXPECTED_EXTERNAL_STATUS.equalsIgnoreCase(record.status())) {
            return statusMismatch(transaction, record);
        }
        return matched(transaction, record);
    }

    // --- the six outcomes -----------------------------------------------------

    private Comparison matched(InternalTransaction transaction, ExternalRecord record) {
        return new Comparison(DiscrepancyType.MATCHED, Severity.LOW,
                transaction.transactionId(), record.settlementRecordId(),
                transaction.amountMinor(), record.amountMinor(), 0L,
                transaction.currency(), INTERNAL_STATUS_POSTED, record.status(),
                "internal and external agree on amount, currency and status");
    }

    private Comparison missing(InternalTransaction transaction) {
        // The whole amount is at risk: we believe it moved and nobody external agrees.
        Severity severity = Severity.escalateFor(
                DiscrepancyType.MISSING_SETTLEMENT.baseSeverity(), transaction.amountMinor());

        return new Comparison(DiscrepancyType.MISSING_SETTLEMENT, severity,
                transaction.transactionId(), null,
                transaction.amountMinor(), null, transaction.amountMinor(),
                transaction.currency(), INTERNAL_STATUS_POSTED, null,
                "no external settlement record references transaction " + transaction.transactionId());
    }

    private Comparison amountMismatch(InternalTransaction transaction, ExternalRecord record,
                                      long difference, boolean sameCurrency) {
        Severity severity = Severity.escalateFor(
                DiscrepancyType.AMOUNT_MISMATCH.baseSeverity(), difference);

        String detail = sameCurrency
                ? "internal %d vs external %d %s, difference %d minor units".formatted(
                        transaction.amountMinor(), record.amountMinor(), transaction.currency(), difference)
                : "currency disagreement: internal %d %s vs external %d %s, amounts are not comparable".formatted(
                        transaction.amountMinor(), transaction.currency(),
                        record.amountMinor(), record.currency());

        return new Comparison(DiscrepancyType.AMOUNT_MISMATCH, severity,
                transaction.transactionId(), record.settlementRecordId(),
                transaction.amountMinor(), record.amountMinor(), difference,
                transaction.currency(), INTERNAL_STATUS_POSTED, record.status(), detail);
    }

    private Comparison duplicate(InternalTransaction transaction, List<ExternalRecord> candidates) {
        // The exposure is the extra copies, not the whole amount: one of them
        // was supposed to happen.
        long extraCopies = candidates.size() - 1L;
        long exposure = extraCopies * candidates.get(0).amountMinor();
        Severity severity = Severity.escalateFor(
                DiscrepancyType.DUPLICATE_SETTLEMENT.baseSeverity(), exposure);

        String ids = candidates.stream().map(ExternalRecord::externalId).reduce((a, b) -> a + ", " + b).orElse("");

        return new Comparison(DiscrepancyType.DUPLICATE_SETTLEMENT, severity,
                transaction.transactionId(), candidates.get(0).settlementRecordId(),
                transaction.amountMinor(), candidates.get(0).amountMinor(), exposure,
                transaction.currency(), INTERNAL_STATUS_POSTED, candidates.get(0).status(),
                "%d external records reference transaction %s (external ids: %s); excess exposure %d minor units"
                        .formatted(candidates.size(), transaction.transactionId(), ids, exposure));
    }

    private Comparison statusMismatch(InternalTransaction transaction, ExternalRecord record) {
        // Amounts agree, so the difference is zero and severity stays at the
        // type floor. Still reported: a zero-difference disagreement about
        // state is often what precedes a real loss.
        return new Comparison(DiscrepancyType.STATUS_MISMATCH,
                DiscrepancyType.STATUS_MISMATCH.baseSeverity(),
                transaction.transactionId(), record.settlementRecordId(),
                transaction.amountMinor(), record.amountMinor(), 0L,
                transaction.currency(), INTERNAL_STATUS_POSTED, record.status(),
                "amounts agree at %d %s but internal is %s while external is %s".formatted(
                        transaction.amountMinor(), transaction.currency(),
                        INTERNAL_STATUS_POSTED, record.status()));
    }

    private Comparison unexpected(ExternalRecord record) {
        Severity severity = Severity.escalateFor(
                DiscrepancyType.UNEXPECTED_EXTERNAL_TRANSACTION.baseSeverity(), record.amountMinor());

        String reference = normalise(record.externalReference()) == null
                ? "no usable reference"
                : "reference " + record.externalReference() + " matches no internal transaction";

        return new Comparison(DiscrepancyType.UNEXPECTED_EXTERNAL_TRANSACTION, severity,
                null, record.settlementRecordId(),
                null, record.amountMinor(), record.amountMinor(),
                record.currency(), null, record.status(),
                "external record %s moved %d %s but %s".formatted(
                        record.externalId(), record.amountMinor(), record.currency(), reference));
    }

    private static String normalise(String reference) {
        return reference == null || reference.isBlank() ? null : reference.trim();
    }
}
