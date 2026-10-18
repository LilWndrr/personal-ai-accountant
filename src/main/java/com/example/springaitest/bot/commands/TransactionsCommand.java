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
public class TransactionsCommand implements Command {

    private final ApplicationEventPublisher eventPublisher;

    @Override
    public boolean canHandle(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText()) return false;
        String text = update.getMessage().getText();
        return text.equals("/transactions") || text.equalsIgnoreCase("Transactions");
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

            InlineKeyboardButton button = InlineKeyboardButton.builder()
                    .text("📅 " + label)
                    .callbackData("transactions:" + month)
                    .build();
            rows.add(new InlineKeyboardRow(button));
        }

        rows.add(new InlineKeyboardRow(InlineKeyboardButton.builder()
                .text("📈 This Year")
                .callbackData("transactions:ytd")
                .build()));
        rows.add(new InlineKeyboardRow(InlineKeyboardButton.builder()
                .text("📊 All Time")
                .callbackData("transactions:all")
                .build()));
        rows.add(new InlineKeyboardRow(InlineKeyboardButton.builder()
                .text("🗓 Custom Range")
                .callbackData("transactions:custom")
                .build()));

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboard(rows)
                .build();

        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text("📋 Select a range to list your transactions:")
                .replyMarkup(keyboard)
                .build();

        eventPublisher.publishEvent(new MessageEvent(this, message));
    }

    @Override
    public String getCommand() {
        return CommandName.TRANSACTIONS.getName();
    }
}
