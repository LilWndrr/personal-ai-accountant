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

    @Bean("financeChatClient")
    ChatClient financeChatClient(ChatClient.Builder builder) {
        return builder.defaultSystem("""
            You are a precise financial data analyst. You parse bank
            statements and categorize transactions. You understand Turkish
            banking terminology. Always return structured JSON. Never guess
            amounts — if unclear, mark as null.
            """).build();
    }
}
