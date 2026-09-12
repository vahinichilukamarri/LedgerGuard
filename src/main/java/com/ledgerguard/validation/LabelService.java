package com.ledgerguard.validation;

import com.ledgerguard.accounts.AccountNotFoundException;
import com.ledgerguard.accounts.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Records what people conclude, and measures how often they conclude differently.
 *
 * <h2>Disagreement is the ceiling</h2>
 *
 * {@link #agreement()} is the number every other figure in this phase should be
 * read against. If two careful reviewers, looking at the same account, reach
 * different verdicts a fifth of the time, then the labels are not ground truth —
 * they are one sample from a distribution of opinions — and no detector can be
 * meaningfully "95% accurate" against them. Reporting precision to two decimal
 * places on top of labels that disagree with each other is the statistical
 * equivalent of quoting a weight to the milligram on a bathroom scale.
 *
 * <p>Most systems never measure this, because measuring it costs a second
 * review of an account already labelled and produces no new coverage. It is
 * measured here for the same reason Phase 8 published {@code applicableSignals}
 * beside the composite: a number whose reliability is invisible gets trusted
 * more than it has earned.
 */
@Service
public class LabelService {

    private static final Logger log = LoggerFactory.getLogger(LabelService.class);

    private final AccountLabelRepository labels;
    private final AccountRepository accounts;
    private final Clock clock;

    public LabelService(AccountLabelRepository labels, AccountRepository accounts, Clock clock) {
        this.labels = labels;
        this.accounts = accounts;
        this.clock = clock;
    }

    /**
     * Record a reviewer's verdict.
     *
     * <p>Idempotent by the data's own identity rather than by a header: one
     * reviewer's verdict about one account at one instant is one fact, so a
     * retried submission returns the label that already exists instead of
     * writing a second one that would read as a reviewer disagreeing with
     * themselves.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AccountLabel record(UUID accountId, Verdict verdict, String reviewer, Stratum stratum,
                               boolean scoresVisible, Instant labelledAsOf, String notes) {
        if (!accounts.existsById(accountId)) {
            throw new AccountNotFoundException(accountId);
        }
        Instant observedAt = Instant.now(clock);
        Instant about = labelledAsOf == null ? observedAt : labelledAsOf;

        try {
            return labels.saveAndFlush(AccountLabel.byReviewer(
                    accountId, verdict, reviewer, stratum, scoresVisible, about, observedAt, notes));
        } catch (DataIntegrityViolationException duplicate) {
            // The partial unique index fired: this reviewer has already judged
            // this account at this instant. A replay, not a conflict.
            log.debug("label: replay from {} for account {}", reviewer, accountId);
            return existing(accountId, reviewer, about)
                    .orElseThrow(() -> duplicate);
        }
    }

    @Transactional(readOnly = true)
    public List<AccountLabel> forAccount(UUID accountId) {
        return labels.findByAccountId(accountId);
    }

    @Transactional(readOnly = true)
    public List<AccountLabel> fromSources(Collection<LabelSource> sources) {
        return labels.findBySources(List.copyOf(sources));
    }

    /**
     * How often reviewers who judged the same account agreed.
     *
     * <p>Only accounts with two or more <em>human</em> verdicts count. Comparing
     * a reviewer against a chargeback is a different question — that is the
     * detector's problem, not the labellers' — and pooling them would make the
     * agreement figure move whenever the dispute rate did.
     */
    @Transactional(readOnly = true)
    public Agreement agreement() {
        Map<UUID, Set<Verdict>> verdictsByAccount = new HashMap<>();
        Map<UUID, Integer> reviewersByAccount = new HashMap<>();

        for (AccountLabel label : labels.findBySource(LabelSource.HUMAN_REVIEW)) {
            verdictsByAccount
                    .computeIfAbsent(label.getAccountId(), key -> new HashSet<>())
                    .add(label.getVerdict());
            reviewersByAccount.merge(label.getAccountId(), 1, Integer::sum);
        }

        int doublyReviewed = 0;
        int agreed = 0;
        for (Map.Entry<UUID, Integer> entry : reviewersByAccount.entrySet()) {
            if (entry.getValue() < 2) {
                continue;
            }
            doublyReviewed++;
            if (verdictsByAccount.get(entry.getKey()).size() == 1) {
                agreed++;
            }
        }
        return new Agreement(doublyReviewed, agreed);
    }

    private Optional<AccountLabel> existing(UUID accountId, String reviewer, Instant about) {
        return labels.findByAccountId(accountId).stream()
                .filter(label -> label.getSource() == LabelSource.HUMAN_REVIEW)
                .filter(label -> reviewer.equals(label.getReviewer()))
                .filter(label -> about.equals(label.getLabelledAsOf()))
                .findFirst();
    }

    /**
     * @param doublyReviewed accounts two or more people judged
     * @param agreed         of those, how many they all judged the same way
     */
    public record Agreement(int doublyReviewed, int agreed) {

        /**
         * The share of re-reviewed accounts where reviewers concurred, or empty
         * when nobody has been re-reviewed.
         *
         * <p>Empty rather than 1.0. An unmeasured agreement rate is not perfect
         * agreement, and the difference decides whether any other figure here
         * can be quoted with a straight face.
         */
        public Optional<Double> rate() {
            return doublyReviewed == 0
                    ? Optional.empty()
                    : Optional.of(agreed / (double) doublyReviewed);
        }
    }
}
