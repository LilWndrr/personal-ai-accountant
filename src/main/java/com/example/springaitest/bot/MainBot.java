package com.example.springaitest.bot;

import com.example.springaitest.bot.model.UserSession;
import com.example.springaitest.bot.service.UserSessionService;
import com.example.springaitest.bot.model.UserState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.objects.Update;

import com.example.springaitest.bot.state.StateHandler;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
public class MainBot implements LongPollingSingleThreadUpdateConsumer {

    private final UserSessionService userSessionService;
    private final Map<UserState, StateHandler> stateHandlers;

    public MainBot(UserSessionService userSessionService, List<StateHandler> handlers) {
        this.userSessionService = userSessionService;
        this.stateHandlers = handlers.stream()
                .collect(Collectors.toMap(StateHandler::getSupportedState, h -> h));
    }

    @Override
    public void consume(Update update) {
        try{
        Long chatId = null;
        if (update.hasMessage()) {
            chatId = update.getMessage().getChatId();
        } else if (update.hasCallbackQuery()) {
            chatId = update.getCallbackQuery().getMessage().getChatId();
        }

        if (chatId == null) return;

        UserSession session = userSessionService.getSession(chatId);

        StateHandler handler = stateHandlers.get(session.getUserState());
        if (handler != null) {
            handler.handle(update, chatId);
        }
        } catch (Exception e) {
            log.error("Failed to handle Telegram update", e);
        }
    }
}

