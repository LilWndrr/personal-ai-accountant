package com.example.springaitest.bot.commands;

import com.example.springaitest.bot.events.MessageEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
@RequiredArgsConstructor
public class ReportCommand implements Command {

    private final ApplicationEventPublisher eventPublisher;

    @Override
    public boolean canHandle(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText()) return false;
        String text = update.getMessage().getText();
        return text.equals("/report") || text.equalsIgnoreCase("REPORT");
    }

    @Override
    public void handle(Update update) {
        long chatId = update.getMessage().getChatId();

        YearMonth current = YearMonth.now();
        List<InlineKeyboardRow> rows = new ArrayList<>();

        for (int i = 0; i < 6; i++) {
            YearMonth month = current.minusMonths(i);
            String label = month.getMonth().getDisplayName(TextStyle.FULL, Locale.getDefault())
                    + " " + month.getYear();
            String callbackData = "report:" + month;

            InlineKeyboardButton button = InlineKeyboardButton.builder()
                    .text("📅 " + label)
                    .callbackData(callbackData)
                    .build();
            rows.add(new InlineKeyboardRow(button));
        }

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboard(rows)
                .build();

        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text("📊 Select a month to view your expense report:")
                .replyMarkup(keyboard)
                .build();

        eventPublisher.publishEvent(new MessageEvent(this, message));
    }

    @Override
    public String getCommand() {
        return CommandName.REPORT.getName();
    }
}
