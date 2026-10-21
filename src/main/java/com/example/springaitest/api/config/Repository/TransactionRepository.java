package com.example.springaitest.api.config.Repository;

import com.example.springaitest.api.config.domain.Category;
import com.example.springaitest.api.config.domain.Transaction;
import com.example.springaitest.api.config.domain.TransactionType;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction,Long> {

    TransactionType findByTelegramUserIdAndDateBetween(Long telegramUserId, LocalDate date, LocalDate date2);
    Transaction findByTelegramUserIdAndCategoryIsNull(Long telegramUserId);
    List<Transaction> findByTelegramUserIdOrderByDateDesc(Long telegramUserId);
    List<Transaction> findByUploadBatchId(String uploadBatchId);
    List<Transaction> findByTelegramUserIdAndTypeAndDateBetween(Long telegramUserId, TransactionType type, LocalDate startDate, LocalDate endDate);

    @Query("SELECT CASE WHEN COUNT(t) > 0 THEN true ELSE false END FROM Transaction t " +
           "WHERE t.telegramUserId = :userId " +
           "AND (t.bankName = :bankName OR (t.bankName IS NULL AND :bankName IS NULL)) " +
           "AND t.date = :date AND t.amount = :amount AND t.description = :description")
    boolean existsByUserAndBankAndDateAndAmountAndDescription(
            @Param("userId") Long telegramUserId,
            @Param("bankName") String bankName,
            @Param("date") LocalDate date,
            @Param("amount") BigDecimal amount,
            @Param("description") String description);

    @Query("SELECT t FROM Transaction t WHERE t.telegramUserId = :userId " +
           "AND (t.bankName = :bankName OR (t.bankName IS NULL AND :bankName IS NULL)) " +
           "AND t.date BETWEEN :startDate AND :endDate")
    List<Transaction> findByUserAndBankAndDateRange(
            @Param("userId") Long telegramUserId,
            @Param("bankName") String bankName,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    @Query("SELECT t FROM Transaction t WHERE t.telegramUserId = :userId " +
           "AND t.date BETWEEN :startDate AND :endDate")
    List<Transaction> findByUserAndDateRange(
            @Param("userId") Long telegramUserId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /** Rows with no dedup key, or one from an older key format (keyPrefix = current version + "%"). */
    @Query("SELECT t FROM Transaction t WHERE t.dedupKey IS NULL OR t.dedupKey NOT LIKE :keyPrefix ORDER BY t.id")
    List<Transaction> findWithOutdatedDedupKey(@Param("keyPrefix") String keyPrefix);

    /**
     * Inserts the transaction unless this user already has one with the same
     * dedup key, which is what a repeated upload, a double-tapped review button
     * or a redelivered Telegram update produces. Each call is its own transaction,
     * so a duplicate never rolls back other rows. Other integrity violations
     * are still thrown.
     *
     * @return true if it was inserted, false if it was already saved
     */
    default boolean saveIfNew(Transaction transaction) {
        try {
            save(transaction);
            return true;
        } catch (DataIntegrityViolationException e) {
            String cause = String.valueOf(e.getMostSpecificCause().getMessage());
            if (cause.contains(Transaction.DEDUP_CONSTRAINT)) {
                return false;
            }
            throw e;
        }
    }

    long countByCategory_Id(Long categoryId);

    /** Rows of [categoryId, transaction count] for one user, for "most used first" lists. */
    @Query("SELECT t.category.id, COUNT(t) FROM Transaction t WHERE t.telegramUserId = :userId " +
           "AND t.category IS NOT NULL GROUP BY t.category.id")
    List<Object[]> countByCategoryForUser(@Param("userId") Long telegramUserId);

    @Modifying
    @Query("UPDATE Transaction t SET t.category = :newCategory WHERE t.category.id = :oldCategoryId")
    int reassignCategory(@Param("oldCategoryId") Long oldCategoryId, @Param("newCategory") Category newCategory);
}
