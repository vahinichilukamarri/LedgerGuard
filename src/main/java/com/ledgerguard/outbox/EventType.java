package com.ledgerguard.outbox;

/**
 * The ledger events this system publishes.
 *
 * <p>Names are past tense on purpose: an event says what has already happened
 * and committed, not what someone would like to happen. Nothing downstream can
 * refuse a {@code PaymentPosted}; the money already moved.
 */
public enum EventType {

    PAYMENT_POSTED("PaymentPosted", "Payment", Topics.PAYMENTS),
    PAYMENT_REFUNDED("PaymentRefunded", "Payment", Topics.REFUNDS),
    TRANSACTION_REVERSED("TransactionReversed", "Transaction", Topics.REVERSALS);

    private final String wireName;
    private final String aggregateType;
    private final String topic;

    EventType(String wireName, String aggregateType, String topic) {
        this.wireName = wireName;
        this.aggregateType = aggregateType;
        this.topic = topic;
    }

    /** The string that goes on the wire and into the database CHECK constraint. */
    public String wireName() {
        return wireName;
    }

    public String aggregateType() {
        return aggregateType;
    }

    public String topic() {
        return topic;
    }
}
