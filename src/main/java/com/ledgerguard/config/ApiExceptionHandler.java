package com.ledgerguard.config;

import com.ledgerguard.accounts.AccountNotFoundException;
import com.ledgerguard.idempotency.IdempotencyKeyConflictException;
import com.ledgerguard.idempotency.IdempotencyKeyRequiredException;
import com.ledgerguard.detection.ml.MlDetectionService;
import com.ledgerguard.payments.PaymentNotFoundException;
import com.ledgerguard.refunds.RefundAmountExceededException;
import com.ledgerguard.reversals.RefundedPaymentCannotBeReversedException;
import com.ledgerguard.reversals.TransactionAlreadyReversedException;
import com.ledgerguard.transactions.TransactionNotFoundException;
import com.ledgerguard.transactions.UnbalancedTransactionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<ApiError> handleAccountNotFound(AccountNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("account_not_found", e.getMessage()));
    }

    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<ApiError> handlePaymentNotFound(PaymentNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("payment_not_found", e.getMessage()));
    }

    @ExceptionHandler(TransactionNotFoundException.class)
    public ResponseEntity<ApiError> handleTransactionNotFound(TransactionNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("transaction_not_found", e.getMessage()));
    }

    /**
     * 422 rather than 400: the request was well formed, but committing it would
     * have broken the ledger invariant.
     */
    @ExceptionHandler(UnbalancedTransactionException.class)
    public ResponseEntity<ApiError> handleUnbalanced(UnbalancedTransactionException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiError.of("unbalanced_transaction", e.getMessage()));
    }

    /**
     * 422 for the same reason: a syntactically fine request that the ledger
     * refuses because it would refund more than was ever paid.
     */
    @ExceptionHandler(RefundAmountExceededException.class)
    public ResponseEntity<ApiError> handleRefundExceeded(RefundAmountExceededException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiError.of("refund_amount_exceeded", e.getMessage()));
    }

    @ExceptionHandler(TransactionAlreadyReversedException.class)
    public ResponseEntity<ApiError> handleAlreadyReversed(TransactionAlreadyReversedException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiError.of("transaction_already_reversed", e.getMessage()));
    }

    /**
     * Same family as the two above: the request was well formed, but committing
     * it would have broken a ledger rule — here, returning more money than was
     * paid.
     */
    @ExceptionHandler(RefundedPaymentCannotBeReversedException.class)
    public ResponseEntity<ApiError> handleRefundedPaymentReversal(
            RefundedPaymentCannotBeReversedException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiError.of("refunded_payment_cannot_be_reversed", e.getMessage()));
    }

    /**
     * Refusing to train a model that would only describe the size of the
     * dataset. Same family as the ledger 422s: the request was well formed, but
     * the current state does not support it.
     */
    @ExceptionHandler(MlDetectionService.InsufficientTrainingDataException.class)
    public ResponseEntity<ApiError> handleInsufficientTrainingData(
            MlDetectionService.InsufficientTrainingDataException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiError.of("insufficient_training_data", e.getMessage()));
    }

    @ExceptionHandler(IdempotencyKeyRequiredException.class)
    public ResponseEntity<ApiError> handleIdempotencyKeyRequired(IdempotencyKeyRequiredException e) {
        return ResponseEntity.badRequest()
                .body(ApiError.of("idempotency_key_required", e.getMessage()));
    }

    /**
     * 409, not 422. The 422s here all mean "the ledger refused this". Reusing a
     * key for a different request is not a ledger rule but a conflict with
     * state that already exists, which is what 409 is for.
     */
    @ExceptionHandler(IdempotencyKeyConflictException.class)
    public ResponseEntity<ApiError> handleIdempotencyConflict(IdempotencyKeyConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of("idempotency_key_conflict", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(ApiError.of("invalid_request", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException e) {
        List<String> details = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .toList();
        return ResponseEntity.badRequest()
                .body(ApiError.of("validation_failed", "request body failed validation", details));
    }

    /**
     * The body never parsed, so no controller method was reached and no bean
     * validation ran. Without this, such a request falls through to the default
     * error controller and comes back in a different shape from every other
     * error this API returns, which makes it needlessly hard to debug.
     *
     * <p>Most often this is a shell quoting mistake: Windows {@code cmd.exe}
     * does not treat single quotes as grouping, so a single-quoted JSON body
     * arrives split across several arguments.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(
                        "malformed_request_body",
                        "request body could not be parsed as JSON",
                        List.of(firstLineOf(e.getMostSpecificCause().getMessage()))));
    }

    /** Jackson explains the parse failure across many lines; the first one carries the point. */
    private static String firstLineOf(String message) {
        if (message == null || message.isBlank()) {
            return "no further detail available";
        }
        int newline = message.indexOf('\n');
        return (newline < 0 ? message : message.substring(0, newline)).trim();
    }
}
