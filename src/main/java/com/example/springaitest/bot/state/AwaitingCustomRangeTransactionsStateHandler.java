package com.example.springaitest.bot.state;

import com.example.springaitest.bot.model.UserState;
import com.example.springaitest.bot.service.TransactionListService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;

@Component
@RequiredArgsConstructor
public class AwaitingCustomRangeTransactionsStateHandler implements StateHandler {
    private final TransactionListService transactionListService;

    @Override
    public UserState getSupportedState() { return UserState.AWAITING_CUSTOM_RANGE_TRANSACTIONS; }

    @Override
    public void handle(Update update, Long chatId) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            transactionListService.handleCustomRangeInput(chatId, update.getMessage().getText());
        }
    }
}
