package com.ledgerguard.validation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One statement about one account, by one party, at one moment.
 *
 * <h2>Append-only, like a posting</h2>
 *
 * There is no setter on this class and no update path in the repository. A
 * reviewer who changes their mind writes a second label; the first stays. Three
 * things depend on that:
 *
 * <ul>
 *   <li><b>Disagreement becomes measurable.</b> Two reviewers reaching different
 *       verdicts on one account is the single most important number in this
 *       phase, because it is a ceiling on every other one — if people disagree
 *       on a fifth of accounts, no detector is meaningfully "95% accurate"
 *       against them.</li>
 *   <li><b>Evaluations stay reproducible.</b> A label set that changed underneath
 *       a published metric would make that metric unrepeatable, which is the
 *       same argument Phase 8 makes for deriving scores at query time instead of
 *       storing them.</li>
 *   <li><b>Contamination stays visible.</b> A verdict revised after the reviewer
 *       saw a score is exactly what {@link #scoresVisible} exists to expose, and
 *       it can only be seen if both verdicts survive.</li>
 * </ul>
 *
 * <h2>Two timestamps, and why neither is optional</h2>
 *
 * {@link #labelledAsOf} is the instant in ledger time the statement is about.
 * {@link #observedAt} is when it became known. For a chargeback those are weeks
 * apart, and conflating them is temporal leakage: scoring an account with data
 * that only existed after the label was earned makes any detector look
 * clairvoyant.
 */
@Entity
@Table(name = "account_labels")
public class AccountLabel {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "verdict", nullable = false, updatable = false, length = 20)
    private Verdict verdict;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, updatable = false, length = 20)
    private LabelSource source;

    /** Null for everything but human review; see {@link Stratum}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "stratum", updatable = false, length = 20)
    private Stratum stratum;

    @Column(name = "reviewer", updatable = false, length = 100)
    private String reviewer;

    @Column(name = "scores_visible", nullable = false, updatable = false)
    private boolean scoresVisible;

    @Column(name = "labelled_as_of", nullable = false, updatable = false)
    private Instant labelledAsOf;

    @Column(name = "observed_at", nullable = false, updatable = false)
    private Instant observedAt;

    @Column(name = "evidence_id", updatable = false)
    private UUID evidenceId;

    @Column(name = "notes", updatable = false)
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AccountLabel() {
    }

    private AccountLabel(UUID accountId, Verdict verdict, LabelSource source, Stratum stratum,
                         String reviewer, boolean scoresVisible, Instant labelledAsOf,
                         Instant observedAt, UUID evidenceId, String notes, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.accountId = Objects.requireNonNull(accountId, "accountId");
        this.verdict = Objects.requireNonNull(verdict, "verdict");
        this.source = Objects.requireNonNull(source, "source");
        this.stratum = stratum;
        this.reviewer = reviewer;
        this.scoresVisible = scoresVisible;
        this.labelledAsOf = Objects.requireNonNull(labelledAsOf, "labelledAsOf");
        this.observedAt = Objects.requireNonNull(observedAt, "observedAt");
        this.evidenceId = evidenceId;
        this.notes = notes;
        this.createdAt = createdAt;

        if (observedAt.isBefore(labelledAsOf)) {
            throw new IllegalArgumentException(
                    "a label cannot be observed before the behaviour it describes: observed %s, about %s"
                            .formatted(observedAt, labelledAsOf));
        }
    }

    /**
     * A person's verdict.
     *
     * <p>Both {@code stratum} and {@code scoresVisible} are required rather than
     * defaulted. A human label that cannot say which pool it was drawn from
     * cannot be weighted, and one that cannot say whether the reviewer saw the
     * score cannot be trusted — defaulting either would make the missing
     * information invisible instead of impossible.
     */
    public static AccountLabel byReviewer(UUID accountId, Verdict verdict, String reviewer,
                                          Stratum stratum, boolean scoresVisible,
                                          Instant labelledAsOf, Instant observedAt, String notes) {
        if (reviewer == null || reviewer.isBlank()) {
            throw new IllegalArgumentException("a human label must say who made it");
        }
        Objects.requireNonNull(stratum, "a human label must say which stratum it was drawn from");

        return new AccountLabel(accountId, verdict, LabelSource.HUMAN_REVIEW, stratum, reviewer,
                scoresVisible, labelledAsOf, observedAt, null, notes, observedAt);
    }

    /**
     * A label earned by an external dispute.
     *
     * <p>Never blind-or-not: no reviewer was involved, so {@code scoresVisible}
     * is false as a statement of fact rather than a configuration. A card scheme
     * has never seen this system's scores.
     */
    public static AccountLabel fromDispute(UUID accountId, Verdict verdict, UUID disputeId,
                                           Instant labelledAsOf, Instant observedAt, String notes) {
        return new AccountLabel(accountId, verdict, LabelSource.DISPUTE_FEED, null, null,
                false, labelledAsOf, observedAt, disputeId, notes, observedAt);
    }

    /** Generated with known ground truth. Reported separately, always. */
    public static AccountLabel synthetic(UUID accountId, Verdict verdict,
                                         Instant labelledAsOf, String notes) {
        return new AccountLabel(accountId, verdict, LabelSource.SYNTHETIC, null, null,
                false, labelledAsOf, labelledAsOf, null, notes, labelledAsOf);
    }

    public UUID getId() {
        return id;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public Verdict getVerdict() {
        return verdict;
    }

    public LabelSource getSource() {
        return source;
    }

    public Stratum getStratum() {
        return stratum;
    }

    public String getReviewer() {
        return reviewer;
    }

    public boolean isScoresVisible() {
        return scoresVisible;
    }

    public Instant getLabelledAsOf() {
        return labelledAsOf;
    }

    public Instant getObservedAt() {
        return observedAt;
    }

    public UUID getEvidenceId() {
        return evidenceId;
    }

    public String getNotes() {
        return notes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** How long after the behaviour the truth arrived. Zero for synthetic labels. */
    public java.time.Duration latency() {
        return java.time.Duration.between(labelledAsOf, observedAt);
    }
}
