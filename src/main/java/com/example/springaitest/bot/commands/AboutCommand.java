package com.example.springaitest.bot.commands;

import com.example.springaitest.bot.events.MessageEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;

@Component
@RequiredArgsConstructor
public class AboutCommand implements Command {

    private final ApplicationEventPublisher eventPublisher;

    @Override
    public boolean canHandle(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText()) return false;
        String text = update.getMessage().getText();
        return text.equals("/about") || text.equalsIgnoreCase("About");
    }

    @Override
    public void handle(Update update) {
        long chatId = update.getMessage().getChatId();

        String text = """
                🤖 *AI Accountant*

                Send me a bank statement (PDF, Excel or CSV) and I'll extract the transactions, \
                categorize them automatically, and ask you to review anything I'm not sure about.

                *Commands*
                /report — view your monthly income/expense report
                /cancel — stop whatever you're doing and go back to the main menu
                /about — show this message
                """;

        eventPublisher.publishEvent(new MessageEvent(this, SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .parseMode("Markdown")
                .build()));
    }

    @Override
    public String getCommand() {
        return CommandName.ABOUT.getName();
    }
}
