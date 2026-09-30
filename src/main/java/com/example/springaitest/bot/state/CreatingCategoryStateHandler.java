package com.example.springaitest.bot.state;

import com.example.springaitest.bot.model.UserState;
import com.example.springaitest.bot.service.CustomCategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;

@Component
@RequiredArgsConstructor
public class CreatingCategoryStateHandler implements StateHandler {
    private final CustomCategoryService customCategoryService;

    @Override
    public UserState getSupportedState() { return UserState.CREATING_CATEGORY; }

    @Override
    public void handle(Update update, Long chatId) {
        if(update.hasMessage() && update.getMessage().hasText()){
            customCategoryService.handleCategoryCreating(chatId, update.getMessage().getText());
        }
    }
}
