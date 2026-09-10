package com.ledgerguard.outbox;

import java.util.List;

/**
 * Kafka topic names, shaped {@code <system>.<aggregate>.<version>}.
 *
 * <p>Separate topics per event family, so a consumer that only cares about
 * refunds is not made to filter everything else.
 *
 * <p>The {@code v1} is part of the name rather than a header. A breaking change
 * to a payload then becomes a new topic that existing consumers simply do not
 * read, instead of a silent deserialization failure in production.
 */
public final class Topics {

    public static final String PAYMENTS = "ledgerguard.payments.v1";
    public static final String REFUNDS = "ledgerguard.refunds.v1";
    public static final String REVERSALS = "ledgerguard.reversals.v1";

    public static final List<String> ALL = List.of(PAYMENTS, REFUNDS, REVERSALS);

    private Topics() {
    }
}
