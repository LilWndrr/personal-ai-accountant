package com.example.springaitest.bot.service;


import com.example.springaitest.bot.model.UserSession;
import com.example.springaitest.bot.model.UserState;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

@Service
public class UserSessionService {

    private static final Duration SESSION_TTL = Duration.ofHours(24);

    private final RedisTemplate<String, UserSession> redisTemplate;

    public UserSessionService(@Qualifier("UserSessionTemplate") RedisTemplate<String, UserSession> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public UserSession getSession(Long chatId) {

        String key = key(chatId);
        UserSession session = redisTemplate.opsForValue().get(key);

        if (session == null) {
            session = UserSession.builder()
                    .id(UUID.randomUUID().toString())
                    .chatId(chatId)
                    .userState(UserState.IDLE)
                    .remainingCategorizations(5)
                    .lastCategorizationDate(LocalDate.now())
                    .build();
            saveSession(session);
        }

        return session;
    }

    public void saveSession(UserSession session) {
        redisTemplate.opsForValue().set(key(session.getChatId()), session, SESSION_TTL);
    }

    public void clearSession(Long chatId) {
        redisTemplate.delete(key(chatId));
    }

    private String key(Long chatId) {
        return "session:" + chatId;
    }

}
