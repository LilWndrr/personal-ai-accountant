package com.example.springaitest.bot.service;


import com.example.springaitest.bot.events.MessageEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
@RequiredArgsConstructor
public class MessageTrackerService {
    private final Map<Long,Integer>  lastBotMessage= new ConcurrentHashMap<>();
    private final ApplicationEventPublisher eventPublisher;

    public void saveLastMessage(Long chatId, Integer messageId){
        lastBotMessage.put(chatId,messageId);
    }

    public void deleteLastMessage(Long chatId){
        Integer lastMessage = lastBotMessage.get(chatId);
            if(lastMessage != null){
                DeleteMessage deleteMessage = DeleteMessage.builder()
                        .chatId(chatId)
                        .messageId(lastMessage)
                        .build();
                eventPublisher.publishEvent(new MessageEvent(this,deleteMessage));
            }
    }
}
