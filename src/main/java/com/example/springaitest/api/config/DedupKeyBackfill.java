package com.example.springaitest.api.config;

import com.example.springaitest.api.config.Repository.TransactionRepository;
import com.example.springaitest.api.config.domain.Transaction;
import com.example.springaitest.api.config.service.TransactionDeduplicationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gives every saved transaction a dedup key in the current format: rows saved
 * before keys existed, and rows keyed by an older KEY_VERSION. Without this,
 * re-uploading an old statement would no longer be recognised. Identical rows
 * are numbered in insertion order (#1, #2, ...), matching how assignKeys
 * numbers a statement. Runs before the bot starts polling; a no-op once every
 * row has a current key.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DedupKeyBackfill implements ApplicationRunner {

    private final TransactionRepository transactionRepository;
    private final TransactionDeduplicationService deduplicationService;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<Transaction> outdated = transactionRepository.findWithOutdatedDedupKey(
                TransactionDeduplicationService.KEY_VERSION + "%");
        if (outdated.isEmpty()) return;

        // Versioned keys never equal unversioned/older ones, so re-keying row by row
        // can't collide with a row that still has its old key.
        Map<String, Integer> occurrences = new HashMap<>();
        for (Transaction tx : outdated) {
            String base = deduplicationService.baseKey(tx);
            int n = occurrences.merge(tx.getTelegramUserId() + "|" + base, 1, Integer::sum);
            tx.setDedupKey(base + "#" + n);
        }
        transactionRepository.saveAll(outdated);
        log.info("Re-keyed {} transactions to dedup key format {}", outdated.size(), TransactionDeduplicationService.KEY_VERSION);
    }
}
