package com.example.springaitest.bot.state;

import com.example.springaitest.bot.model.UserState;
import com.example.springaitest.bot.service.CategoryManagementService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;

@Component
@RequiredArgsConstructor
public class AwaitingCategoryRenameStateHandler implements StateHandler {
    private final CategoryManagementService categoryManagementService;

    @Override
    public UserState getSupportedState() { return UserState.AWAITING_CATEGORY_RENAME; }

    @Override
    public void handle(Update update, Long chatId) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            categoryManagementService.handleRenameInput(chatId, update.getMessage().getText());
        }
    }
}
