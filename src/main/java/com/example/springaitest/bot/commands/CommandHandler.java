package com.example.springaitest.bot.commands;


import lombok.AllArgsConstructor;

import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.util.Collection;

@Service
@AllArgsConstructor
public class CommandHandler {

    private final Collection<Command> commands;

    public void handle(Update update){

        for(Command command :commands){
            if(command.canHandle(update)){
                command.handle(update);
                return;
            }
        }
    }
}
