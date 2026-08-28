package com.example.springaitest.config;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@RequiredArgsConstructor
@Configuration
public class OllamaConfig {
    @Bean
    ChatClient chatClient(ChatClient.Builder builder){
        return builder.defaultSystem("You are Micheline stared chef cook").build();
    }
}
