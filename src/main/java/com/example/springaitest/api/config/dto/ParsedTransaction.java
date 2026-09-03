package com.example.springaitest.api.config.dto;

public record ParsedTransaction(
        String date,
        String description,
        double amount,
        double balance,
        String merchantName
) {
}
