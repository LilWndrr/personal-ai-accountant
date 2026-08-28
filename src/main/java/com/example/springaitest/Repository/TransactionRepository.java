package com.example.springaitest.Repository;

import com.example.springaitest.domain.Transaction;
import com.example.springaitest.domain.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction,Long> {

    TransactionType findByTelegramUserIdAndDateBetween(Long telegramUserId, LocalDate date, LocalDate date2);
    Transaction findByTelegramUserIdAndCategoryIsNull(Long telegramUserId);
}
