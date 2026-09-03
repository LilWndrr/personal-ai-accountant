package com.example.springaitest.bot.commands;

import org.telegram.telegrambots.meta.api.objects.Update;

public interface Command {
    boolean canHandle(Update update);

    void handle(Update update);

    String getCommand();
}
