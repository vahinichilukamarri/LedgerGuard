package com.ledgerguard.detection.signals;

import com.ledgerguard.detection.AccountActivity;
import com.ledgerguard.detection.AnomalySignal;
import com.ledgerguard.detection.DetectionSettings;
import com.ledgerguard.detection.Signal;
import com.ledgerguard.detection.SignalScore;

/**
 * Are this account's transactions failing reconciliation unusually often?
 *
 * <h2>Why this signal carries weight out of proportion to its precision</h2>
 *
 * Every other signal in this layer is the ledger describing itself. This one is
 * not: a reconciliation incident means an <em>independent</em> external record
 * disagreed with the ledger about money that was supposed to have moved. A
 * ledger can be internally perfect and still be wrong, and this is the only
 * signal positioned to notice.
 *
 * <p>It is also the coarsest. It works from incident counts per account, which
 * are thin, and it compares against a ledger-wide rate rather than the account's
 * own. Both limitations are real. The weight reflects the independence of the
 * evidence rather than the sharpness of the measurement.
 *
 * <h2>It does not re-derive anything</h2>
 *
 * The signal reads {@code reconciliation_incidents} as the reconciler already
 * wrote them. Re-classifying discrepancies here would mean two implementations
 * of the same judgement drifting apart, and the reconciler's is the one with six
 * documented outcome types and a test suite behind it.
 */
public class ReconciliationMismatchSignal implements AnomalySignal {

    @Override
    public Signal signal() {
        return Signal.RECONCILIATION_MISMATCH_RATE;
    }

    @Override
    public SignalScore evaluate(AccountActivity activity, DetectionSettings settings) {
        return RateSpike.evaluate(signal(), settings,
                activity.mismatchedTransactionsInWindow(),
                activity.transactionsInWindow(),
                activity.globalRates().mismatchedTransactions(),
                activity.globalRates().totalTransactions(),
                "transactions raised a reconciliation incident");
    }
}
