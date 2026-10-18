package com.example.springaitest.bot.commands;


import com.example.springaitest.bot.events.MessageEvent;
import lombok.AllArgsConstructor;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.util.Collection;

@Service
@AllArgsConstructor
public class CommandHandler {

    private final Collection<Command> commands;
    private final ApplicationEventPublisher eventPublisher;

    public void handle(Update update){

        for(Command command :commands){
            if(command.canHandle(update)){
                command.handle(update);
                return;
            }
        }

        if (update.hasMessage() && update.getMessage().hasText()) {
            eventPublisher.publishEvent(new MessageEvent(this, SendMessage.builder()
                    .chatId(update.getMessage().getChatId())
                    .text("🤔 I didn't understand that. Try /report, /about, or send me a bank statement to get started.")
                    .build()));
        }
    }
}
