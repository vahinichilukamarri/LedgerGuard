package com.ledgerguard.detection.signals;

import com.ledgerguard.detection.AccountActivity;
import com.ledgerguard.detection.AnomalySignal;
import com.ledgerguard.detection.DetectionSettings;
import com.ledgerguard.detection.Signal;
import com.ledgerguard.detection.SignalScore;

/**
 * Are this account's payments being refunded or reversed unusually often?
 *
 * <p>A payment that comes straight back is the shape of several different
 * problems: a compromised account being tested, a merchant whose customers are
 * disputing, or an integration quietly failing and being corrected by hand. The
 * signal does not distinguish between them and does not try to — it says the
 * rate is out of line and leaves the reason to whoever investigates.
 *
 * <p>Refunds and reversals are counted together on purpose. They are different
 * operations with different rules — a refund is partial and capped, a reversal
 * is total and single-use, and since Phase 7 a payment can have one or the other
 * but never both — yet from this signal's point of view they are the same
 * observable event: money went out and came back. Splitting them would halve
 * both denominators and leave each half too thin to say anything.
 */
public class RefundReversalRateSignal implements AnomalySignal {

    @Override
    public Signal signal() {
        return Signal.REFUND_REVERSAL_RATE;
    }

    @Override
    public SignalScore evaluate(AccountActivity activity, DetectionSettings settings) {
        return RateSpike.evaluate(signal(), settings,
                activity.refundedOrReversedPaymentsInWindow(),
                activity.paymentsInWindow(),
                activity.globalRates().refundedOrReversedPayments(),
                activity.globalRates().totalPayments(),
                "payments were refunded or reversed");
    }
}
