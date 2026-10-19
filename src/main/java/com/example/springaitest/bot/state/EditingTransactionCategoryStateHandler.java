package com.example.springaitest.bot.state;

import com.example.springaitest.bot.model.UserState;
import com.example.springaitest.bot.service.TransactionEditService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;

@Component
@RequiredArgsConstructor
public class EditingTransactionCategoryStateHandler implements StateHandler {
    private final TransactionEditService transactionEditService;

    @Override
    public UserState getSupportedState() { return UserState.EDITING_TRANSACTION_CATEGORY; }

    @Override
    public void handle(Update update, Long chatId) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            transactionEditService.handleNewCategoryNameInput(chatId, update.getMessage().getText());
        }
    }
}
