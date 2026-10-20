package com.example.springaitest.api.config;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class ModelConfig {

    /**
     * Shared across every call site that hits the LLM provider (vision parsing
     * and categorization both use the same API key/account), since the limit
     * is enforced by the provider per-account, not per-feature.
     * Defaults to 18/min, just below OpenRouter's published free-tier limit of
     * 20 requests/minute, to leave headroom for clock-skew between our refresh
     * window and theirs. Set AI_RATE_LIMIT_PER_MINUTE when changing provider/plan.
     */
    @Bean
    public RateLimiter llmRateLimiter(@Value("${app.ai.rate-limit-per-minute}") int requestsPerMinute) {
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(requestsPerMinute)
                .limitRefreshPeriod(Duration.ofMinutes(1))
                .timeoutDuration(Duration.ofSeconds(30))
                .build();
        return RateLimiter.of("llmRateLimiter", config);
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


    @Bean("categorizationChatClient")
    ChatClient categorizationChatClient(ChatClient.Builder builder) {
        return builder.defaultSystem("""
            You are a financial categorization assistant.
            You understand Turkish banking terminology and merchants.
            Always return structured JSON. Be consistent in your categorizations.
            """).build();
    }
}
