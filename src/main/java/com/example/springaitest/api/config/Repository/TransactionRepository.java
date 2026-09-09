package com.example.springaitest.api.config.Repository;

import com.example.springaitest.api.config.domain.Transaction;
import com.example.springaitest.api.config.domain.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction,Long> {

    TransactionType findByTelegramUserIdAndDateBetween(Long telegramUserId, LocalDate date, LocalDate date2);
    Transaction findByTelegramUserIdAndCategoryIsNull(Long telegramUserId);
    List<Transaction> findByTelegramUserIdOrderByDateDesc(Long telegramUserId);
    List<Transaction> findByUploadBatchId(String uploadBatchId);
    List<Transaction> findByTelegramUserIdAndTypeAndDateBetween(Long telegramUserId, TransactionType type, LocalDate startDate, LocalDate endDate);
}
