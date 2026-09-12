package com.ledgerguard.validation.dto;

import com.ledgerguard.validation.Stratum;
import com.ledgerguard.validation.Verdict;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

/**
 * A reviewer's verdict.
 *
 * <p>Four of these fields are required where a friendlier API would default
 * them, and each refusal is deliberate. A label with no reviewer cannot be
 * checked against a second opinion; one with no stratum cannot be weighted and
 * silently corrupts recall; one that does not say whether the scores were
 * visible cannot be separated from an anchored verdict. Defaulting any of them
 * would make missing information invisible rather than impossible, and the whole
 * point of this phase is that the shape of the evidence stays legible.
 *
 * @param scoresVisible whether the reviewer could see the detector's scores when
 *                      they judged. Asserted by the caller; the review endpoint's
 *                      blind mode is what makes {@code false} truthful
 * @param labelledAsOf  the moment in ledger time this verdict is about. Defaults
 *                      to now, which is right for a reviewer looking at an
 *                      account today and wrong for a backfill
 */
public record RecordLabelRequest(
        @NotNull UUID accountId,
        @NotNull Verdict verdict,
        @NotBlank String reviewer,
        @NotNull Stratum stratum,
        @NotNull Boolean scoresVisible,
        Instant labelledAsOf,
        String notes) {
}
