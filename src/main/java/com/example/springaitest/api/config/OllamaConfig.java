package com.example.springaitest.api.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OllamaConfig {

    @Bean("chefChatClient")
    ChatClient chefChatClient(ChatClient.Builder builder) {
        return builder.defaultSystem("You are Michelin starred chef cook").build();
    }

    /**
     * Finance ChatClient — used by DocumentParserService for PDF parsing.
     * Uses the default vision model (llama-3.2-11b-vision-instruct).
     */
    @Bean("financeChatClient")
    ChatClient financeChatClient(ChatClient.Builder builder) {
        return builder.defaultSystem("""
            You are a precise financial data analyst. You parse bank
            statements and categorize transactions. You understand Turkish
            banking terminology. Always return structured JSON. Never guess
            amounts — if unclear, mark as null.
            """).build();
    }

    /**
     * Categorization ChatClient — uses a text-focused model (configured
     * via app.categorization.model property) for better classification
     * accuracy than the vision model.
     */
    @Bean("categorizationChatClient")
    ChatClient categorizationChatClient(ChatClient.Builder builder) {
        return builder.defaultSystem("""
            You are a financial categorization assistant.
            You understand Turkish banking terminology and merchants.
            Always return structured JSON. Be consistent in your categorizations.
            """).build();
    }
}
