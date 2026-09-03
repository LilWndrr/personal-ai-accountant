package com.example.springaitest.bot;

import com.example.springaitest.bot.commands.Command;
import com.example.springaitest.bot.commands.CommandHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.objects.Update;

@Component
@RequiredArgsConstructor
public class MainBot implements LongPollingSingleThreadUpdateConsumer {

    private final CommandHandler commandHandler;
    @Override
    public void consume(Update update) {
        commandHandler.handle(update);
    }
}
