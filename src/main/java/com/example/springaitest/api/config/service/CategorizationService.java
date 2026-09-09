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

    private final org.springframework.ai.chat.model.ChatModel chatModel;
    private final MerchantMappingRepository merchantMappingRepository;
    private final CategoryRepository categoryRepository;

    public CategorizationService(org.springframework.ai.chat.model.ChatModel chatModel,
                                 MerchantMappingRepository merchantMappingRepository,
                                 CategoryRepository categoryRepository) {
        this.chatModel = chatModel;
        this.merchantMappingRepository = merchantMappingRepository;
        this.categoryRepository = categoryRepository;
    }

    private static final String CATEGORIZATION_SYSTEM_PROMPT = """
            You are a financial categorization assistant.
            You will be given a list of transactions and a list of available categories.
            Your task is to assign the best matching category to each transaction based on its merchant name and description.
            If the list of available categories is empty, or NO existing category matches well, YOU MUST INVENT a sensible, common personal finance category (e.g., 'Groceries', 'Transport', 'Utilities', 'Shopping', 'Dining', 'Income', etc.).
            Do NOT use 'Uncategorized' unless the transaction is completely incomprehensible.
            Return a JSON array of CategorizedTransaction objects.
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
            Optional<Category> matched = findMatchedCategoryFromCache(tx.description(), userMappings, systemMappings);

            if (matched.isPresent()) {
                autoCategorized.add(new CategorizedTransaction(
                        i,
                        matched.get().getName(),
                        matched.get().getEmoji(),
                        1.0,
                        tx.merchantName(),
                        "Matched from existing user rules"
                ));
            } else {
                needsAiIndices.add(i);
            }
        }


        List<CategorizedTransaction> needsReview = new ArrayList<>();

        if (!needsAiIndices.isEmpty()) {
            List<String> allCategoryNames = getAllCategoryNames(userId);

            // Process in small batches to avoid API timeouts
            int batchSize = 5;
            for (int batchStart = 0; batchStart < needsAiIndices.size(); batchStart += batchSize) {
                int batchEnd = Math.min(batchStart + batchSize, needsAiIndices.size());
                List<Integer> batchIndices = needsAiIndices.subList(batchStart, batchEnd);
                List<ParsedTransaction> batchTransactions = batchIndices.stream()
                        .map(transactions::get)
                        .toList();

                try {
                    List<CategorizedTransaction> aiResults = callLLMforCategorization(
                            batchTransactions, batchIndices, allCategoryNames);
                    for (CategorizedTransaction result : aiResults) {
                        if (result.confidence() >= confidenceThreshold) {
                            autoCategorized.add(result);
                        } else {
                            needsReview.add(result);
                        }
                    }
                } catch (Exception e) {
                    System.err.println("LLM Categorization failed for batch: " + e.getMessage());
                    // Fallback: Send this batch to manual review if AI fails
                    for (int i = 0; i < batchTransactions.size(); i++) {
                        ParsedTransaction tx = batchTransactions.get(i);
                        needsReview.add(new CategorizedTransaction(
                                batchIndices.get(i),
                                "Uncategorized",
                                "❓",
                                0.0,
                                tx.merchantName(),
                                "AI processing failed — needs manual review"
                        ));
                    }
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

        String formatInstructions = """
            You MUST return ONLY a JSON array. Do not include markdown formatting or backticks.
            Example format:
            [
              {
                "transactionIndex": 0,
                "suggestedCategory": "Groceries",
                "suggestedEmoji": "🛒",
                "confidence": 0.95,
                "merchantName": "Migros",
                "reasoning": "Clear grocery store purchase"
              }
            ]
            """;

        String responseText = "";
        try {
            org.springframework.ai.chat.prompt.Prompt prompt = new org.springframework.ai.chat.prompt.Prompt(
                    List.of(
                            new org.springframework.ai.chat.messages.SystemMessage(CATEGORIZATION_SYSTEM_PROMPT + "\n\n" + formatInstructions),
                            new org.springframework.ai.chat.messages.UserMessage("Categories: " + formattedCategories + "\n\nTransactions to categorize:\n" + formattedTransactions)
                    )
            );
            responseText = chatModel.call(prompt).getResult().getOutput().getText();
            System.out.println("========== AI RESPONSE SUCCESS ==========");
            System.out.println(responseText);
            System.out.println("=========================================");

            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            mapper.findAndRegisterModules(); // Needed for Java Records
            
            // Extract the JSON array using regex to handle conversational text
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\[.*\\]", java.util.regex.Pattern.DOTALL).matcher(responseText);
            if (matcher.find()) {
                responseText = matcher.group();
            } else {
                throw new RuntimeException("No JSON array found in response");
            }
            
            List<CategorizedTransaction> parsedList = mapper.readValue(responseText, new com.fasterxml.jackson.core.type.TypeReference<List<CategorizedTransaction>>() {});
            System.out.println("Parsed " + parsedList.size() + " transactions from AI JSON.");
            return parsedList;
        } catch (Exception e) {
            System.err.println("============= AI PARSING FAILED =============");
            System.err.println("Error: " + e.getMessage());
            System.err.println("Raw Response Text: \n" + responseText);
            e.printStackTrace();
            System.err.println("=============================================");
            
            try {
                java.nio.file.Files.writeString(
                    java.nio.file.Paths.get("ai-response-debug.txt"), 
                    "Error: " + e.getMessage() + "\n\nRaw Response:\n" + responseText
                );
            } catch (Exception ex) {
                // Ignore file write errors
            }
            
            throw new RuntimeException("JSON parsing failed", e);
        }
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
