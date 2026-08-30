package com.example.springaitest.dto;

public record CategorizedTransaction(
        int transactionIndex,
        String suggestedCategory,
        String suggestedEmoji,
        double confidence,
        String merchantName,
        String reasoning
) {
}
