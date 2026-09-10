package com.ledgerguard.transactions;

import com.ledgerguard.postings.Posting;

import java.util.List;

/** A persisted transaction together with the postings that were written for it. */
public record PostedTransaction(Transaction transaction, List<Posting> postings) {

    public PostedTransaction {
        postings = List.copyOf(postings);
    }
}
