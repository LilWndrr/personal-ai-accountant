package com.example.springaitest.dto;

import java.util.List;

public record CategorizationResult(
        List<CategorizedTransaction> autoCategorized,
        List<CategorizedTransaction> needsReview

) {
}
