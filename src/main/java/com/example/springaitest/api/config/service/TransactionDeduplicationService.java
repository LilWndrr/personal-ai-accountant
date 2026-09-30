package com.example.springaitest.api.config.service;

import com.example.springaitest.api.config.Repository.TransactionRepository;
import com.example.springaitest.api.config.domain.Transaction;
import com.example.springaitest.api.config.dto.ParsedTransaction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Filters out duplicate transactions before saving to the database.
 * Two transactions are considered duplicates if they match on
 * (telegramUserId, bankName, date, amount, description).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TransactionDeduplicationService {

    private final TransactionRepository transactionRepository;

    /**
     * Returns only the transactions that do NOT already exist in the database.
     *
     * @param transactions the parsed transactions from the uploaded file
     * @param bankName     the bank these transactions belong to (may be null)
     * @param userId       the Telegram user ID
     * @return a filtered list with duplicates removed
     */
    public List<ParsedTransaction> filterDuplicates(
            List<ParsedTransaction> transactions, String bankName, Long userId) {

        if (transactions == null || transactions.isEmpty()) {
            return transactions;
        }

        // Determine the date range of incoming transactions for an efficient query
        LocalDate minDate = null;
        LocalDate maxDate = null;
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd[.][/][-]MM[.][/][-]yyyy");

        for (ParsedTransaction tx : transactions) {
            try {
                LocalDate date = LocalDate.parse(tx.date(), formatter);
                if (minDate == null || date.isBefore(minDate)) minDate = date;
                if (maxDate == null || date.isAfter(maxDate)) maxDate = date;
            } catch (Exception e) {
                log.warn("Could not parse date '{}' for dedup range calculation", tx.date());
            }
        }

        if (minDate == null || maxDate == null) {
            log.warn("Could not determine date range for dedup — skipping dedup check");
            return transactions;
        }

        // Load all existing transactions for this user+bank in the date range
        List<Transaction> existing = transactionRepository
                .findByUserAndBankAndDateRange(userId, bankName, minDate, maxDate);

        // Build a set of dedup keys from existing transactions for O(1) lookups
        Set<String> existingKeys = existing.stream()
                .map(this::dedupKey)
                .collect(Collectors.toSet());

        List<ParsedTransaction> unique = new ArrayList<>();
        int duplicateCount = 0;

        for (ParsedTransaction tx : transactions) {
            String key = dedupKey(tx, bankName);
            if (existingKeys.contains(key)) {
                duplicateCount++;
            } else {
                unique.add(tx);
                // Also add to the set so we don't add duplicates within the same file
                existingKeys.add(key);
            }
        }

        if (duplicateCount > 0) {
            log.info("Filtered out {} duplicate transactions (user={}, bank={})",
                    duplicateCount, userId, bankName);
        }

        return unique;
    }

    /**
     * Returns the number of duplicates that would be filtered.
     */
    public int countDuplicates(List<ParsedTransaction> transactions, String bankName, Long userId) {
        int original = transactions == null ? 0 : transactions.size();
        int filtered = filterDuplicates(transactions, bankName, userId).size();
        return original - filtered;
    }

    private String dedupKey(Transaction tx) {
        String desc = tx.getDescription() == null ? "" : tx.getDescription().trim().toLowerCase();
        String bank = tx.getBankName() == null ? "" : tx.getBankName().trim().toLowerCase();
        return bank + "|" + tx.getDate() + "|" + tx.getAmount().toPlainString() + "|" + desc;
    }

    private String dedupKey(ParsedTransaction tx, String bankName) {
        String desc = tx.description() == null ? "" : tx.description().trim().toLowerCase();
        String bank = bankName == null ? "" : bankName.trim().toLowerCase();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd[.][/][-]MM[.][/][-]yyyy");
        try {
            LocalDate date = LocalDate.parse(tx.date(), formatter);
            BigDecimal amount = BigDecimal.valueOf(tx.amount());
            // Match the scale — DB stores with scale 2
            amount = amount.setScale(2, java.math.RoundingMode.HALF_UP);
            return bank + "|" + date + "|" + amount.toPlainString() + "|" + desc;
        } catch (Exception e) {
            // Fallback: use raw values
            return bank + "|" + tx.date() + "|" + tx.amount() + "|" + desc;
        }
    }
}
