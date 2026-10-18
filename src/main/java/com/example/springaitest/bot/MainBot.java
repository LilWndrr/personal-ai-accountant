package com.example.springaitest.bot;

import com.example.springaitest.bot.events.MessageEvent;
import com.example.springaitest.bot.model.UserSession;
import com.example.springaitest.bot.service.KeyboardService;
import com.example.springaitest.bot.service.UserSessionService;
import com.example.springaitest.bot.model.UserState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
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
    private final ApplicationEventPublisher eventPublisher;
    private final KeyboardService keyboardService;

    public MainBot(UserSessionService userSessionService, List<StateHandler> handlers,
                    ApplicationEventPublisher eventPublisher, KeyboardService keyboardService) {
        this.userSessionService = userSessionService;
        this.stateHandlers = handlers.stream()
                .collect(Collectors.toMap(StateHandler::getSupportedState, h -> h));
        this.eventPublisher = eventPublisher;
        this.keyboardService = keyboardService;
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

        // Reachable from every state, including REVIEWING and CREATING_CATEGORY, which
        // otherwise have no way back to the main menu.
        if (update.hasMessage() && update.getMessage().hasText()
                && update.getMessage().getText().equalsIgnoreCase("/cancel")) {
            handleCancel(chatId, session);
            return;
        }

        StateHandler handler = stateHandlers.get(session.getUserState());
        if (handler != null) {
            handler.handle(update, chatId);
        }
        } catch (Exception e) {
            log.error("Failed to handle Telegram update", e);
        }
    }

    private void handleCancel(Long chatId, UserSession session) {
        boolean wasIdle = session.getUserState() == UserState.IDLE;

        session.setUserState(UserState.IDLE);
        session.setPendingReview(null);
        session.setOriginalTransactions(null);
        session.setCurrentReviewIndex(0);
        session.setUploadBatch(null);
        session.setBankName(null);
        userSessionService.saveSession(session);

        String text = wasIdle
                ? "Nothing to cancel — you're already at the main menu."
                : "🚫 Cancelled. Back to the main menu.";

        eventPublisher.publishEvent(new MessageEvent(this, SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .replyMarkup(keyboardService.mainMenu(chatId))
                .build()));
    }
}

