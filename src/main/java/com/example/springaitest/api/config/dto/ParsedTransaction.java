package com.example.springaitest.api.config.dto;

/**
 * @param dedupKey identity of this row within its statement, assigned by
 *                 TransactionDeduplicationService.assignKeys and persisted with the
 *                 Transaction; null until assigned (and for sessions saved before it existed).
 */
public record ParsedTransaction(
        String date,
        String description,
        double amount,
        double balance,
        String merchantName,
        String bankName,
        String dedupKey
) {
    public ParsedTransaction(String date, String description, double amount, double balance,
                             String merchantName, String bankName) {
        this(date, description, amount, balance, merchantName, bankName, null);
    }

    public ParsedTransaction withDedupKey(String dedupKey) {
        return new ParsedTransaction(date, description, amount, balance, merchantName, bankName, dedupKey);
    }
}
