package com.example.springaitest.bot;

import com.example.springaitest.bot.commands.Command;
import com.example.springaitest.bot.commands.CommandHandler;
import com.example.springaitest.bot.model.UserSession;
import com.example.springaitest.bot.model.UserSessionService;
import com.example.springaitest.bot.model.UserState;
import com.example.springaitest.bot.service.CallbackHandlerService;
import com.example.springaitest.bot.service.CustomCategoryService;
import com.example.springaitest.bot.service.ReportService;
import com.example.springaitest.bot.service.UploadWorkFlowService;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.objects.Update;

import com.example.springaitest.bot.state.StateHandler;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
    }
}

