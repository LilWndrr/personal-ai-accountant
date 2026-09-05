package com.example.springaitest.api.config.service;


import com.example.springaitest.api.config.Repository.CategoryRepository;
import com.example.springaitest.api.config.Repository.MerchantMappingRepository;
import com.example.springaitest.api.config.domain.Category;
import com.example.springaitest.api.config.domain.MerchantMapping;

import com.example.springaitest.api.config.dto.CategorizationResult;
import com.example.springaitest.api.config.dto.CategorizedTransaction;
import com.example.springaitest.api.config.dto.ParsedTransaction;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

@Service
public class CategorizationService {

    private final ChatClient chat;
    private final MerchantMappingRepository merchantMappingRepository;
    private final CategoryRepository categoryRepository;

    public CategorizationService(@Qualifier("financeChatClient") ChatClient chat,
                                 MerchantMappingRepository merchantMappingRepository,
                                 CategoryRepository categoryRepository) {
        this.chat = chat;
        this.merchantMappingRepository = merchantMappingRepository;
        this.categoryRepository = categoryRepository;
    }

    private static final String CATEGORIZATION_SYSTEM_PROMPT = """
        You are a financial categorization assistant.
        Categorize each bank transaction into the most appropriate category.
        
        Rules:
        - Use an existing category if it fits.
        - If no existing category fits, suggest a new one.
        - Extract the merchant/store name from the description.
        - Rate your confidence from 0.0 to 1.0.
        - For Turkish transaction descriptions, common patterns:
          POS ALIŞVERİŞ = card purchase
          HAVALE = money transfer
          EFT = electronic transfer
          MAAŞ = salary
    """;

    @Value("${app.categorization.confidence-threshold:0.7}")
    private double confidenceThreshold;


    @Transactional(readOnly = true)
    public CategorizationResult categorize(List<ParsedTransaction> transactions, Long userId) {

        List<CategorizedTransaction> autoCategorized = new ArrayList<>();
        List<Integer> needsAiIndices = new ArrayList<>();

        // Load ALL mappings ONCE before the loop (fixes N+1 query problem)
        List<MerchantMapping> userMappings = merchantMappingRepository.findByTelegramUserId(userId);
        List<MerchantMapping> systemMappings = merchantMappingRepository.findByTelegramUserId(0L);

        for (int i = 0; i < transactions.size(); i++) {
            ParsedTransaction tx = transactions.get(i);
            Optional<Category> match = findMatchedCategoryFromCache(tx.description(), userMappings, systemMappings);

            if (match.isPresent()) {
                autoCategorized.add(new CategorizedTransaction(
                        i,
                        match.get().getName(),
                        match.get().getEmoji(),
                        1.0,
                        tx.merchantName(),
                        "Matched from merchant cache"
                ));
            } else {
                needsAiIndices.add(i);
            }
        }


        List<CategorizedTransaction> needsReview = new ArrayList<>();

        if (!needsAiIndices.isEmpty()) {
            List<ParsedTransaction> forAi = needsAiIndices.stream()
                    .map(transactions::get)
                    .toList();
            List<String> allCategoryNames = getAllCategoryNames(userId);
            List<CategorizedTransaction> aiResults = callLLMforCategorization(forAi, needsAiIndices, allCategoryNames);
            for (CategorizedTransaction result : aiResults) {
                if (result.confidence() >= confidenceThreshold) {
                    autoCategorized.add(result);
                } else {
                    needsReview.add(result);
                }
            }
        }

        return new CategorizationResult(autoCategorized, needsReview);
    }

    private List<CategorizedTransaction> callLLMforCategorization(
            List<ParsedTransaction> transactions,
            List<Integer> originalIndices,
            List<String> categoryNames) {


        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < transactions.size(); i++) {
            ParsedTransaction tx = transactions.get(i);
            sb.append("[%d] %s | %.2f | %s%n".formatted(
                    originalIndices.get(i),
                    tx.date(), tx.amount(), tx.description()
            ));
        }

        String formattedCategories = String.join(", ", categoryNames);
        String formattedTransactions = sb.toString();

        return chat.prompt()
                .system(CATEGORIZATION_SYSTEM_PROMPT)
                .user(u -> u.text("""
                        Categories: {categories}
                        
                        Transactions to categorize:
                        {transactions}
                        """)
                        .param("categories", formattedCategories)
                        .param("transactions", formattedTransactions))
                .call()
                .entity(new ParameterizedTypeReference<List<CategorizedTransaction>>() {});
    }

    private List<String> getAllCategoryNames(Long userId) {
        List<Category> userCats = categoryRepository.findByTelegramUserId(userId);
        List<Category> systemCats = categoryRepository.findByTelegramUserId(0L);
        return Stream.concat(userCats.stream(), systemCats.stream())
                .map(Category::getName)
                .distinct()
                .toList();
    }

    private Optional<Category> findMatchedCategoryFromCache(String description,
                                                             List<MerchantMapping> userMappings,
                                                             List<MerchantMapping> systemMappings) {

        Locale turkish = Locale.of("tr", "TR");
        String upperDesc = description.toUpperCase(turkish);

        for (MerchantMapping mapping : userMappings) {
            if (upperDesc.contains(mapping.getMerchantKeyword().toUpperCase(turkish))) {
                return Optional.of(mapping.getCategory());
            }
        }

        for (MerchantMapping mapping : systemMappings) {
            if (upperDesc.contains(mapping.getMerchantKeyword().toUpperCase(turkish))) {
                return Optional.of(mapping.getCategory());
            }
        }

        return Optional.empty();
    }
}
