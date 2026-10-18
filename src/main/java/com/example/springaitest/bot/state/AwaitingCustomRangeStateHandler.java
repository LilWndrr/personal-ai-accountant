package com.example.springaitest.bot.state;

import com.example.springaitest.bot.model.UserState;
import com.example.springaitest.bot.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;

@Component
@RequiredArgsConstructor
public class AwaitingCustomRangeStateHandler implements StateHandler {
    private final ReportService reportService;

    @Override
    public UserState getSupportedState() { return UserState.AWAITING_CUSTOM_RANGE; }

    @Override
    public void handle(Update update, Long chatId) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            reportService.handleCustomRangeInput(chatId, update.getMessage().getText());
        }
    }
}
