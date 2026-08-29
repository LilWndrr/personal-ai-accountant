package com.example.springaitest.dto;

import java.util.List;

public record ParsedResult(List<ParsedTransaction> transactions,
        ValidationResult validation,
        String rawText) {

}
