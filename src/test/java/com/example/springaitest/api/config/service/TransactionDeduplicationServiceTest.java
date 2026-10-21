package com.example.springaitest.api.config.service;

import com.example.springaitest.api.config.Repository.TransactionRepository;
import com.example.springaitest.api.config.domain.Transaction;
import com.example.springaitest.api.config.dto.ParsedTransaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TransactionDeduplicationServiceTest {

    private final TransactionRepository repository = mock(TransactionRepository.class);
    private final TransactionDeduplicationService service = new TransactionDeduplicationService(repository);

    private static ParsedTransaction tx(String date, String desc, double amount, double balance) {
        return new ParsedTransaction(date, desc, amount, balance, null, null);
    }

    private static Transaction saved(LocalDate date, String desc, String amount, String balance, String key) {
        return Transaction.builder()
                .id((long) (Math.random() * 1_000_000))
                .date(date)
                .description(desc)
                .amount(new BigDecimal(amount))
                .balance(balance == null ? null : new BigDecimal(balance))
                .dedupKey(key)
                .build();
    }

    private void alreadySaved(List<Transaction> rows) {
        when(repository.findByUserAndDateRange(anyLong(), any(), any())).thenReturn(new ArrayList<>(rows));
    }

    /** What saving these parsed rows would have stored. */
    private List<Transaction> savedFrom(List<ParsedTransaction> statement) {
        return service.assignKeys(statement).stream()
                .map(p -> saved(LocalDate.parse(p.date(), java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")),
                        p.description(), String.valueOf(p.amount()), String.valueOf(p.balance()), p.dedupKey()))
                .toList();
    }

    // ---- Tier 1: exact keys ----

    @Test
    void identicalPurchasesWithDifferentBalancesAreBothKept() {
        List<ParsedTransaction> keyed = service.assignKeys(List.of(
                tx("05.10.2026", "STARBUCKS KADIKOY", -45.0, 955.0),
                tx("05.10.2026", "STARBUCKS KADIKOY", -45.0, 1000.0)));

        assertThat(keyed.get(0).dedupKey()).isNotEqualTo(keyed.get(1).dedupKey());

        alreadySaved(List.of());
        assertThat(service.check(keyed, 1L).newTransactions()).hasSize(2);
    }

    @Test
    void identicalRowsWithoutBalanceAreNumberedNotDropped() {
        List<ParsedTransaction> keyed = service.assignKeys(List.of(
                tx("05.10.2026", "Coffee", -45.0, 0),
                tx("05.10.2026", "Coffee", -45.0, 0)));

        assertThat(keyed).extracting(ParsedTransaction::dedupKey)
                .containsExactly("v2|2026-10-05|-45.00||coffee#1", "v2|2026-10-05|-45.00||coffee#2");
    }

    @Test
    void reUploadingTheSameStatementFindsEveryRowAlreadySaved() {
        List<ParsedTransaction> statement = List.of(
                tx("05.10.2026", "Coffee", -45.0, 0),
                tx("05.10.2026", "Coffee", -45.0, 0),
                tx("06.10.2026", "MAAS", 30000.0, 30910.0));
        alreadySaved(savedFrom(statement));

        TransactionDeduplicationService.DedupResult result = service.check(statement, 1L);

        assertThat(result.newTransactions()).isEmpty();
        assertThat(result.exactDuplicates()).isEqualTo(3);
        assertThat(result.likelyDuplicates()).isEmpty();
    }

    @Test
    void keyMatchesTheSavedEntity() {
        ParsedTransaction parsed = service.assignKeys(List.of(tx("2026-10-05", "  Migros   ATASEHIR ", -120.5, 880.25))).get(0);
        Transaction entity = saved(LocalDate.of(2026, 10, 5), "migros atasehir", "-120.50", "880.25", null);

        assertThat(parsed.dedupKey()).isEqualTo(service.baseKey(entity) + "#1");
    }

    @Test
    void sessionsStoredBeforeKeysExistedFallBackToFirstOccurrence() {
        ParsedTransaction legacy = tx("05.10.2026", "Coffee", -45.0, 955.0);
        assertThat(service.keyOf(legacy)).isEqualTo(service.assignKeys(List.of(legacy)).get(0).dedupKey());
    }

    // ---- Normalization: same transaction written differently by different formats ----

    @Test
    void turkishLettersCaseCardAndReferenceNumbersAreFolded() {
        assertThat(service.normalizeDescription("POS ALIŞVERİŞ MİGROS ATAŞEHİR 4521****1234 REF:20261005123456"))
                .isEqualTo(service.normalizeDescription("pos alisveris migros atasehir"))
                .isEqualTo("pos alisveris migros atasehir");
    }

    @Test
    void shortNumbersLikeStoreNumbersAreKept() {
        assertThat(service.normalizeDescription("A101 MAGAZA 4512")).isEqualTo("a101 magaza 4512");
    }

    @Test
    void sameTransactionFromExcelAndLlmPdfGetsTheSameKeyAfterNormalization() {
        String excel = service.assignKeys(List.of(tx("05.10.2026", "POS ALIŞVERİŞ MİGROS ATAŞEHİR 4521****1234", -45.0, 955.0))).get(0).dedupKey();
        String pdf = service.assignKeys(List.of(tx("05.10.2026", "POS ALISVERIS MIGROS ATASEHIR", -45.0, 955.0))).get(0).dedupKey();
        assertThat(excel).isEqualTo(pdf);
    }

    // ---- Tier 2: likely duplicates are reported, never dropped ----

    @Test
    void sameTransactionWithBalanceOnlyInOneFormatIsALikelyDuplicate() {
        // Saved from the PDF (has balance); the CSV export has no balance column.
        alreadySaved(savedFrom(List.of(tx("05.10.2026", "MIGROS ATASEHIR", -45.0, 955.0))));

        TransactionDeduplicationService.DedupResult result =
                service.check(List.of(tx("05.10.2026", "POS MIGROS ATASEHIR 4521****1234", -45.0, 0)), 1L);

        assertThat(result.newTransactions()).hasSize(1);
        assertThat(result.likelyDuplicates()).containsOnlyKeys(result.newTransactions().get(0).dedupKey());
    }

    @Test
    void llmShortenedDescriptionIsALikelyDuplicate() {
        alreadySaved(savedFrom(List.of(tx("05.10.2026", "POS ALISVERIS MIGROS SANAL MARKET ATASEHIR ISTANBUL", -45.0, 0))));

        assertThat(service.check(List.of(tx("05.10.2026", "MIGROS SANAL", -45.0, 0)), 1L).likelyDuplicates()).hasSize(1);
    }

    @Test
    void differentKnownBalancesAreNeverALikelyDuplicate() {
        alreadySaved(savedFrom(List.of(tx("05.10.2026", "Coffee", -45.0, 955.0))));

        assertThat(service.check(List.of(tx("05.10.2026", "Coffee", -45.0, 910.0)), 1L).likelyDuplicates()).isEmpty();
    }

    @Test
    void differentMerchantSameDayAndAmountIsNotALikelyDuplicate() {
        alreadySaved(savedFrom(List.of(tx("05.10.2026", "POS ALISVERIS MIGROS", -45.0, 0))));

        assertThat(service.check(List.of(tx("05.10.2026", "POS ALISVERIS A101", -45.0, 0)), 1L).likelyDuplicates()).isEmpty();
    }

    @Test
    void eachSavedTransactionIsMatchedAtMostOnce() {
        alreadySaved(savedFrom(List.of(tx("05.10.2026", "MIGROS", -45.0, 0))));

        TransactionDeduplicationService.DedupResult result = service.check(List.of(
                tx("05.10.2026", "POS MIGROS 4521****1234", -45.0, 0),
                tx("05.10.2026", "POS MIGROS 4521****1234", -45.0, 0)), 1L);

        assertThat(result.likelyDuplicates()).hasSize(1);
    }

    @Test
    void savedRowMatchedExactlyIsNotReusedAsALikelyMatch() {
        // Re-upload where one coffee is already saved and a second, new one appears.
        alreadySaved(savedFrom(List.of(tx("05.10.2026", "Coffee", -45.0, 0))));

        TransactionDeduplicationService.DedupResult result = service.check(List.of(
                tx("05.10.2026", "Coffee", -45.0, 0),
                tx("05.10.2026", "Coffee", -45.0, 0)), 1L);

        assertThat(result.exactDuplicates()).isEqualTo(1);
        assertThat(result.newTransactions()).hasSize(1);
        assertThat(result.likelyDuplicates()).isEmpty();
    }
}
