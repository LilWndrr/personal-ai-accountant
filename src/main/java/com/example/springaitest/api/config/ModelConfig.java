package com.example.springaitest.api.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ModelConfig {



    @Bean("financeChatClient")
    ChatClient financeChatClient(ChatClient.Builder builder) {
        return builder.defaultSystem("""
            You are a precise financial data analyst. You parse bank
            statements and categorize transactions. You understand Turkish
            banking terminology. Always return structured JSON. Never guess
            amounts — if unclear, mark as null.
            """).build();
    }


    @Bean("categorizationChatClient")
    ChatClient categorizationChatClient(ChatClient.Builder builder) {
        return builder.defaultSystem("""
            You are a financial categorization assistant.
            You understand Turkish banking terminology and merchants.
            Always return structured JSON. Be consistent in your categorizations.
            """).build();
    }
}
