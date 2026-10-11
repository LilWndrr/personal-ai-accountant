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

        for (ParsedTransaction tx : transactions) {
            LocalDate date = parseDate(tx.date());
            if (date != null) {
                if (minDate == null || date.isBefore(minDate)) minDate = date;
                if (maxDate == null || date.isAfter(maxDate)) maxDate = date;
            } else {
                log.warn("Could not parse date '{}' for dedup range calculation", tx.date());
            }
        }

        // Load existing transactions: query by user and date range (without bankName coupling)
        List<Transaction> existing;
        if (minDate != null && maxDate != null) {
            LocalDate queryStart = minDate.minusDays(2);
            LocalDate queryEnd = maxDate.plusDays(2);
            existing = transactionRepository.findByUserAndDateRange(userId, queryStart, queryEnd);
            log.info("Deduplication: fetched {} existing transactions from DB for user {} in date range [{} to {}]",
                    existing.size(), userId, queryStart, queryEnd);
        } else {
            log.warn("Could not determine date range — falling back to user's existing transactions for dedup check");
            existing = transactionRepository.findByTelegramUserIdOrderByDateDesc(userId);
            log.info("Deduplication fallback: fetched {} existing transactions from DB for user {}",
                    existing.size(), userId);
        }

        // Build a set of dedup keys from existing transactions for O(1) lookups
        Set<String> existingKeys = existing.stream()
                .map(this::dedupKey)
                .collect(Collectors.toSet());

        List<ParsedTransaction> unique = new ArrayList<>();
        int duplicateCount = 0;

        for (ParsedTransaction tx : transactions) {
            String key = dedupKey(tx);
            if (existingKeys.contains(key)) {
                log.info("Duplicate detected: date={}, amount={}, desc='{}' (key='{}')",
                        tx.date(), tx.amount(), tx.description(), key);
                duplicateCount++;
            } else {
                unique.add(tx);
                // Also add to the set so we don't add duplicates within the same file
                existingKeys.add(key);
            }
        }

        if (duplicateCount > 0) {
            log.info("Filtered out {} duplicate transactions for user {} (original: {}, unique remaining: {})",
                    duplicateCount, userId, transactions.size(), unique.size());
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
        String desc = normalizeDescription(tx.getDescription());
        String amountStr = normalizeAmount(tx.getAmount());
        return tx.getDate() + "|" + amountStr + "|" + desc;
    }

    private String dedupKey(ParsedTransaction tx) {
        String desc = normalizeDescription(tx.description());
        LocalDate parsed = parseDate(tx.date());
        String dateStr = parsed != null ? parsed.toString() : (tx.date() == null ? "" : tx.date().trim());
        String amountStr = normalizeAmount(BigDecimal.valueOf(tx.amount()));
        return dateStr + "|" + amountStr + "|" + desc;
    }

    private String normalizeAmount(BigDecimal amount) {
        if (amount == null) return "0.00";
        return amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private String normalizeDescription(String desc) {
        if (desc == null || desc.isBlank()) return "";
        // Normalize whitespace, punctuation spacing, and convert to lowercase
        return desc.replaceAll("\\s+", " ").trim().toLowerCase(java.util.Locale.ROOT);
    }

    private LocalDate parseDate(String rawDate) {
        if (rawDate == null || rawDate.isBlank()) return null;
        String trimmed = rawDate.trim();
        List<DateTimeFormatter> formatters = List.of(
                DateTimeFormatter.ofPattern("dd[.][/][-]MM[.][/][-]yyyy"),
                DateTimeFormatter.ISO_LOCAL_DATE
        );
        for (DateTimeFormatter fmt : formatters) {
            try {
                return LocalDate.parse(trimmed, fmt);
            } catch (Exception ignored) {
            }
        }
        return null;
    }
}
