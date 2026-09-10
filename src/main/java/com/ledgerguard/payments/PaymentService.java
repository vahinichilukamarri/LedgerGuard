package com.ledgerguard.payments;

import com.ledgerguard.accounts.Account;
import com.ledgerguard.accounts.AccountService;
import com.ledgerguard.config.Money;
import com.ledgerguard.payments.dto.PaymentResponse;
import com.ledgerguard.postings.NewPosting;
import com.ledgerguard.transactions.PostedTransaction;
import com.ledgerguard.transactions.TransactionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Orchestrates the Phase 1 flow:
 * payment -> transaction -> balanced debit/credit posting pair.
 *
 * <p>The whole method is one database transaction. If
 * {@code TransactionService.createBalanced} rejects the postings, the payment
 * row rolls back with it, so an unbalanced instruction leaves no trace at all.
 *
 * <p>Note there is no balance update step. Account balances are derived from
 * postings on read, so writing the postings <em>is</em> the balance update.
 */
@Service
public class PaymentService {

    private final PaymentRepository payments;
    private final AccountService accounts;
    private final TransactionService transactions;
    private final Clock clock;

    public PaymentService(PaymentRepository payments, AccountService accounts,
                          TransactionService transactions, Clock clock) {
        this.payments = payments;
        this.accounts = accounts;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Transactional
    public PaymentResponse create(UUID sourceAccountId, UUID destinationAccountId,
                                  long amountMinor, String currency, String description) {

        String normalizedCurrency = Money.normalize(currency);

        Account source = accounts.require(sourceAccountId);
        Account destination = accounts.require(destinationAccountId);
        requireMatchingCurrency(source, normalizedCurrency);
        requireMatchingCurrency(destination, normalizedCurrency);

        Payment payment = payments.save(Payment.pending(
                source.getId(), destination.getId(), amountMinor, normalizedCurrency, Instant.now(clock)));

        // Money leaves the source (CREDIT) and arrives at the destination (DEBIT).
        // These two legs are equal and opposite, so the transaction nets to zero.
        PostedTransaction posted = transactions.createBalanced(
                describe(description, payment),
                normalizedCurrency,
                List.of(
                        NewPosting.credit(source.getId(), amountMinor, normalizedCurrency),
                        NewPosting.debit(destination.getId(), amountMinor, normalizedCurrency)));

        payment.markPosted(posted.transaction().getId());

        return PaymentResponse.of(payment, posted);
    }

    private static void requireMatchingCurrency(Account account, String currency) {
        if (!account.getCurrency().equals(currency)) {
            throw new IllegalArgumentException(
                    "account %s is denominated in %s, cannot take a %s payment"
                            .formatted(account.getId(), account.getCurrency(), currency));
        }
    }

    private static String describe(String description, Payment payment) {
        if (description != null && !description.isBlank()) {
            return description.trim();
        }
        return "Payment " + payment.getId();
    }
}
