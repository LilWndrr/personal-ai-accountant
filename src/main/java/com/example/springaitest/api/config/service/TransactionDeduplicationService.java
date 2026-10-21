package com.example.springaitest.api.config.service;

import com.example.springaitest.api.config.Repository.TransactionRepository;
import com.example.springaitest.api.config.domain.Transaction;
import com.example.springaitest.api.config.dto.ParsedTransaction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Keeps uploads from saving a transaction twice, in two tiers:
 *
 * 1. Exact: every parsed row gets a dedup key, stored on the saved Transaction
 *    and enforced by a unique constraint on (telegram_user_id, dedup_key). A
 *    row whose key is already saved is skipped silently. This covers
 *    re-uploading the same file.
 * 2. Likely: a row with no exact match but the same date and amount as a saved
 *    transaction, a compatible balance and a similar description is probably
 *    the same transaction from a different format (e.g. PDF read by the LLM vs.
 *    the bank's Excel export). Those are never dropped silently: they are
 *    returned so the user can confirm, because two real identical purchases
 *    look exactly like this too.
 *
 * Key = "v2|" + date | amount | balance-after | normalized description, plus
 * "#n" = which occurrence of that combination this is within its statement.
 * The balance tells apart genuine identical transactions; the occurrence
 * counter covers statements without a balance column. Bump KEY_VERSION when the
 * key format changes; DedupKeyBackfill re-keys rows with an older version.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TransactionDeduplicationService {

    public static final String KEY_VERSION = "v2|";

    // Generic banking words that say nothing about which merchant or person a
    // transaction is with; left out when comparing descriptions.
    private static final Set<String> NOISE_WORDS = Set.of(
            "pos", "alisveris", "islem", "odeme", "eft", "havale", "fast", "virman", "kart", "kredi",
            "banka", "bankasi", "ref", "referans", "no", "tl", "try", "harcama", "isyeri", "tarih");

    private final TransactionRepository transactionRepository;

    /**
     * Outcome of checking an upload against what the user has already saved.
     *
     * @param newTransactions    rows with no exact match, in statement order
     * @param exactDuplicates    how many rows were already saved and were dropped
     * @param likelyDuplicates   dedup key of a row in newTransactions → the saved
     *                           transaction it probably is; ask before saving these
     */
    public record DedupResult(List<ParsedTransaction> newTransactions,
                              int exactDuplicates,
                              Map<String, Transaction> likelyDuplicates) {
    }

    /**
     * Returns the transactions with a dedup key assigned, in the same order and at
     * the same indices. Must run on the whole statement (before any filtering) so
     * occurrence numbers are the same every time that statement is uploaded.
     */
    public List<ParsedTransaction> assignKeys(List<ParsedTransaction> transactions) {
        if (transactions == null) return null;
        Map<String, Integer> occurrences = new HashMap<>();
        List<ParsedTransaction> keyed = new ArrayList<>(transactions.size());
        for (ParsedTransaction tx : transactions) {
            String base = baseKey(tx);
            int n = occurrences.merge(base, 1, Integer::sum);
            keyed.add(tx.withDedupKey(base + "#" + n));
        }
        return keyed;
    }

    /**
     * Checks an upload against the user's saved transactions: drops exact
     * duplicates and reports likely ones. Transactions without keys get them
     * assigned first.
     */
    public DedupResult check(List<ParsedTransaction> transactions, Long userId) {
        if (transactions == null || transactions.isEmpty()) {
            return new DedupResult(transactions == null ? List.of() : transactions, 0, Map.of());
        }
        List<ParsedTransaction> keyed = transactions.stream().allMatch(tx -> tx.dedupKey() != null)
                ? transactions
                : assignKeys(transactions);

        List<Transaction> existing = loadExisting(keyed, userId);
        Set<String> existingKeys = existing.stream()
                .map(Transaction::getDedupKey)
                .filter(k -> k != null)
                .collect(Collectors.toSet());

        List<ParsedTransaction> unique = new ArrayList<>();
        for (ParsedTransaction tx : keyed) {
            if (existingKeys.contains(tx.dedupKey())) {
                log.info("Duplicate detected: date={}, amount={}, desc='{}' (key='{}')",
                        tx.date(), tx.amount(), tx.description(), tx.dedupKey());
            } else {
                unique.add(tx);
            }
        }
        int exactDuplicates = keyed.size() - unique.size();
        if (exactDuplicates > 0) {
            log.info("Filtered out {} already-saved transactions for user {} (original: {}, remaining: {})",
                    exactDuplicates, userId, keyed.size(), unique.size());
        }

        // A saved row this upload already matched exactly is accounted for, so it
        // can't also be "probably" the same as some other row in the upload.
        Set<String> incomingKeys = keyed.stream().map(ParsedTransaction::dedupKey).collect(Collectors.toSet());
        List<Transaction> unclaimed = new ArrayList<>(existing.stream()
                .filter(e -> e.getDedupKey() == null || !incomingKeys.contains(e.getDedupKey()))
                .toList());

        Map<String, Transaction> likely = new LinkedHashMap<>();
        for (ParsedTransaction tx : unique) {
            Transaction match = findLikelyMatch(tx, unclaimed);
            if (match != null) {
                unclaimed.remove(match);
                likely.put(tx.dedupKey(), match);
                log.info("Likely duplicate: date={}, amount={}, desc='{}' resembles saved transaction {} ('{}')",
                        tx.date(), tx.amount(), tx.description(), match.getId(), match.getDescription());
            }
        }
        return new DedupResult(unique, exactDuplicates, likely);
    }

    /**
     * Returns only the transactions whose dedup key is not already saved for this
     * user (tier 1 only).
     */
    public List<ParsedTransaction> filterDuplicates(
            List<ParsedTransaction> transactions, String bankName, Long userId) {
        if (transactions == null || transactions.isEmpty()) {
            return transactions;
        }
        return check(transactions, userId).newTransactions();
    }

    /**
     * Returns the number of duplicates that would be filtered.
     */
    public int countDuplicates(List<ParsedTransaction> transactions, String bankName, Long userId) {
        int original = transactions == null ? 0 : transactions.size();
        int filtered = filterDuplicates(transactions, bankName, userId).size();
        return original - filtered;
    }

    /**
     * The key to persist for this transaction. Falls back to its first-occurrence
     * key for review sessions stored before keys existed.
     */
    public String keyOf(ParsedTransaction tx) {
        return tx.dedupKey() != null ? tx.dedupKey() : baseKey(tx) + "#1";
    }

    /** Key without the occurrence suffix, for a transaction already in the database. */
    public String baseKey(Transaction tx) {
        return baseKey(tx.getDate().toString(), tx.getAmount(), tx.getBalance(), tx.getDescription());
    }

    private List<Transaction> loadExisting(List<ParsedTransaction> transactions, Long userId) {
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
        if (minDate != null && maxDate != null) {
            return transactionRepository.findByUserAndDateRange(userId, minDate, maxDate);
        }
        log.warn("Could not determine date range — checking against all of user {}'s transactions", userId);
        return transactionRepository.findByTelegramUserIdOrderByDateDesc(userId);
    }

    private Transaction findLikelyMatch(ParsedTransaction tx, List<Transaction> candidates) {
        LocalDate date = parseDate(tx.date());
        if (date == null) return null;
        BigDecimal amount = scaled(BigDecimal.valueOf(tx.amount()));
        BigDecimal balance = BigDecimal.valueOf(tx.balance());

        for (Transaction saved : candidates) {
            if (!date.equals(saved.getDate())) continue;
            if (saved.getAmount() == null || amount.compareTo(scaled(saved.getAmount())) != 0) continue;
            // Two known, different balances mean two different real transactions.
            if (isKnown(balance) && isKnown(saved.getBalance())
                    && scaled(balance).compareTo(scaled(saved.getBalance())) != 0) continue;
            if (similarDescriptions(tx.description(), saved.getDescription())) return saved;
        }
        return null;
    }

    /**
     * True if the descriptions share at least half of the shorter one's
     * meaningful words. Formats disagree on accents, card/reference numbers and
     * how much text survives (the LLM shortens and merges), so an empty side
     * counts as similar: the same date and amount are then the only evidence,
     * and the user is asked anyway.
     */
    boolean similarDescriptions(String a, String b) {
        Set<String> wordsA = meaningfulWords(a);
        Set<String> wordsB = meaningfulWords(b);
        if (wordsA.isEmpty() || wordsB.isEmpty()) return true;

        int common = 0;
        for (String wa : wordsA) {
            for (String wb : wordsB) {
                if (sameWord(wa, wb)) {
                    common++;
                    break;
                }
            }
        }
        return common * 2 >= Math.min(wordsA.size(), wordsB.size());
    }

    // Equal, or one is a truncation of the other ("ATASEHIR" vs "ATAS").
    private boolean sameWord(String a, String b) {
        if (a.equals(b)) return true;
        String shorter = a.length() <= b.length() ? a : b;
        String longer = a.length() <= b.length() ? b : a;
        return shorter.length() >= 4 && longer.startsWith(shorter);
    }

    private Set<String> meaningfulWords(String description) {
        // Pure numbers (store/terminal numbers) are left out, but words that contain
        // digits are merchant names ("A101") and are kept.
        return Arrays.stream(normalizeDescription(description).split(" "))
                .filter(w -> w.length() >= 2 && !w.chars().allMatch(Character::isDigit) && !NOISE_WORDS.contains(w))
                .collect(Collectors.toCollection(HashSet::new));
    }

    private String baseKey(ParsedTransaction tx) {
        LocalDate parsed = parseDate(tx.date());
        String date = parsed != null ? parsed.toString() : (tx.date() == null ? "" : tx.date().trim());
        return baseKey(date, BigDecimal.valueOf(tx.amount()), BigDecimal.valueOf(tx.balance()), tx.description());
    }

    private String baseKey(String date, BigDecimal amount, BigDecimal balance, String description) {
        return KEY_VERSION + date + "|" + normalizeAmount(amount) + "|" + normalizeBalance(balance)
                + "|" + normalizeDescription(description);
    }

    private String normalizeAmount(BigDecimal amount) {
        if (amount == null) return "0.00";
        return scaled(amount).toPlainString();
    }

    // The parser reports a missing balance as 0, so 0 means "unknown" and is left
    // out rather than treated as a real balance.
    private String normalizeBalance(BigDecimal balance) {
        if (!isKnown(balance)) return "";
        return scaled(balance).toPlainString();
    }

    private static boolean isKnown(BigDecimal balance) {
        return balance != null && balance.signum() != 0;
    }

    private static BigDecimal scaled(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Folds the differences between how formats write the same description:
     * Turkish letters and accents (Ş→s, İ/ı→i), case, punctuation, masked card
     * numbers ("4521****1234") and long reference numbers (6+ digits, with their
     * "ref" label), which one export includes and another leaves out. Short
     * numbers such as store numbers are kept.
     */
    String normalizeDescription(String desc) {
        if (desc == null || desc.isBlank()) return "";
        String folded = Normalizer.normalize(desc, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replace('ı', 'i');
        return folded
                .replaceAll("\\d*\\*+\\d*", " ")
                .replaceAll("[^\\p{L}\\p{Nd}]+", " ")
                .replaceAll("\\b\\d{6,}\\b", " ")
                .replaceAll("\\b(ref|referans)\\b", " ")
                .replaceAll("\\s+", " ")
                .trim();
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
