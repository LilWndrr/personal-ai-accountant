package com.example.springaitest.api.config.dto;

import java.util.List;

public record ParsedResult(List<ParsedTransaction> transactions,
        ValidationResult validation,
        String rawText) {

}
