package com.example.springaitest.dto;

public record ParsedTransaction(
        String date,
        String description,
        double amount,
        double balance,
        String merchantName
) {
}
