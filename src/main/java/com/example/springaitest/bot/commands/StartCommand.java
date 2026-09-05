package com.example.springaitest.bot.commands;

import com.example.springaitest.bot.events.MessageEvent;
import com.example.springaitest.bot.model.UserSessionService;
import com.example.springaitest.bot.model.UserState;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;


@Component
@RequiredArgsConstructor
public class StartCommand implements Command{

    private final ApplicationEventPublisher applicationEventPublisher;
    private final UserSessionService sessionService;

    @Override
    public boolean canHandle(Update update) {

        if(!update.hasMessage()|| !update.getMessage().hasText() ) return false;

        Long chatId = update.getMessage().getChatId();

        return update.getMessage().getText().equals("/start");
    }

    @Override
    public void handle(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            long chatId = update.getMessage().getChatId();


            SendMessage message = SendMessage // Create a message object
                    .builder()
                    .chatId(chatId)
                    .text("Hello")

                    .build();
            applicationEventPublisher.publishEvent(new MessageEvent(this, message));
        }
    }

    @Override
    public String getCommand() {
        return CommandName.START.getName();
    }
}
