package com.example.springaitest.api.config.dto;

import java.util.List;

public record CategorizationResult(
        List<CategorizedTransaction> autoCategorized,
        List<CategorizedTransaction> needsReview

) {
}
