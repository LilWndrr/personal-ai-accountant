package com.example.springaitest.bot.state;

import com.example.springaitest.bot.model.UserState;
import org.telegram.telegrambots.meta.api.objects.Update;

public interface StateHandler {
    UserState getSupportedState();
    void handle(Update update, Long chatId);
}
