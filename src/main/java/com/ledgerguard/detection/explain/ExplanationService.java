package com.ledgerguard.detection.explain;

import com.ledgerguard.detection.DetectionService;
import com.ledgerguard.detection.ml.MlDetectionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Assembles an account's explanation from the two layers that produced it.
 *
 * <p>Read-only, like everything else in the detection layer: it issues no
 * statement of its own, it arranges what {@link DetectionService} and
 * {@link MlDetectionService} already computed. Nothing here rescores anything,
 * which is the property that keeps an explanation from being able to disagree
 * with the number it explains.
 */
@Service
public class ExplanationService {

    /** Said in one place, so the API and the summary give the same reason. */
    public static final String NO_MODEL =
            "no model has been trained; POST /detection/model/train to train one";

    private final DetectionService detection;
    private final MlDetectionService ml;
    private final Clock clock;

    public ExplanationService(DetectionService detection, MlDetectionService ml, Clock clock) {
        this.detection = detection;
        this.ml = ml;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AccountExplanation explain(UUID accountId) {
        return explain(accountId, Instant.now(clock));
    }

    @Transactional(readOnly = true)
    public AccountExplanation explain(UUID accountId, Instant asOf) {
        return explain(detection.assess(accountId, asOf));
    }

    /**
     * Explain an account that has already been assessed.
     *
     * <p>The ranked endpoint assesses every account before deciding which to
     * return, and this overload lets it explain the survivors without a second
     * pass over the ledger.
     */
    public AccountExplanation explain(DetectionService.ScoredAccount assessed) {
        return AccountExplanation.of(assessed.score(), ml.explain(assessed), NO_MODEL);
    }
}
