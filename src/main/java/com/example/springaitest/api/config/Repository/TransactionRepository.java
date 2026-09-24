package com.example.springaitest.api.config.Repository;

import com.example.springaitest.api.config.domain.Transaction;
import com.example.springaitest.api.config.domain.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
