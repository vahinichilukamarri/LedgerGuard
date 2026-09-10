package com.ledgerguard.transactions;

import java.util.Map;

/**
 * Thrown when a set of postings does not satisfy the core ledger invariant:
 * for every currency in a transaction, the sum of debits must equal the sum
 * of credits.
 *
 * <p>Throwing this is what stops the transaction from ever reaching the
 * database. It is raised before any INSERT is issued.
 */
public class UnbalancedTransactionException extends RuntimeException {

    public UnbalancedTransactionException(String message) {
        super(message);
    }

    /**
     * @param netByCurrency currency to (sum of debits - sum of credits), in minor units
     */
    public static UnbalancedTransactionException of(Map<String, Long> netByCurrency,
                                                    Map<String, Long> debitsByCurrency,
                                                    Map<String, Long> creditsByCurrency) {
        StringBuilder detail = new StringBuilder(
                "transaction rejected: debits must equal credits for every currency.");
        netByCurrency.forEach((currency, net) -> {
            if (net != 0L) {
                detail.append(" [%s: debits=%d, credits=%d, difference=%d minor units]".formatted(
                        currency,
                        debitsByCurrency.getOrDefault(currency, 0L),
                        creditsByCurrency.getOrDefault(currency, 0L),
                        net));
            }
        });
        return new UnbalancedTransactionException(detail.toString());
    }
}
