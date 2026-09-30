package com.example.springaitest.bot.state;

import com.example.springaitest.bot.model.UserState;
import com.example.springaitest.bot.service.CallbackHandlerService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;

@Component
@RequiredArgsConstructor
public class ReviewingStateHandler implements StateHandler {
    private final CallbackHandlerService callbackHandlerService;

    @Override
    public UserState getSupportedState() { return UserState.REVIEWING; }

    @Override
    public void handle(Update update, Long chatId) {
        if(update.hasCallbackQuery()){
            String callbackData = update.getCallbackQuery().getData();
            callbackHandlerService.handleCallback(chatId, callbackData);
        }
    }
}
