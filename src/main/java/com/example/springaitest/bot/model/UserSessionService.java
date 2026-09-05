package com.example.springaitest.bot.model;


import lombok.RequiredArgsConstructor;
import org.apache.catalina.User;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class UserSessionService {

    public final ConcurrentHashMap<Long,UserSession> sessionHashMap = new ConcurrentHashMap<Long,UserSession>();

    public UserSession getSession(Long chatId) {

        return sessionHashMap.computeIfAbsent(chatId, id -> {


            return UserSession.builder()
                    .id(UUID.randomUUID().toString()) // or Long depending on what you chose
                    .chatId(id)
                    .userState(UserState.IDLE)
                    .build();
        });
    }

    public void clearSession(Long chatId) {
        sessionHashMap.remove(chatId);
    }

}
