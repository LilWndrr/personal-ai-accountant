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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.objects.Update;

@Component
@RequiredArgsConstructor
public class MainBot implements LongPollingSingleThreadUpdateConsumer {

    private final CommandHandler commandHandler;
    private final UserSessionService userSessionService;
    private final UploadWorkFlowService uploadWorkFlowService;
    private final CallbackHandlerService callbackHandlerService;
    private final CustomCategoryService customCategoryService;
    private final ReportService reportService;

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

        if(session.getUserState() == UserState.IDLE){
            if(update.hasMessage() && update.getMessage().hasDocument()){
                uploadWorkFlowService.processUpload(chatId, update.getMessage().getDocument());
            } else if (update.hasMessage() && update.getMessage().hasText()) {
                commandHandler.handle(update);
            } else if (update.hasCallbackQuery()) {
                String callbackData = update.getCallbackQuery().getData();
                if (callbackData.startsWith("report:")) {
                    reportService.handleReportCallback(chatId, callbackData);
                }
            }

        } else if (session.getUserState() == UserState.REVIEWING) {
            if(update.hasCallbackQuery()){
                String callbackData = update.getCallbackQuery().getData();
                callbackHandlerService.handleCallback(chatId, callbackData);
            }
        } else if (session.getUserState() == UserState.CREATING_CATEGORY) {
            if(update.hasMessage() && update.getMessage().hasText()){
                customCategoryService.handleCategoryCreating(chatId,update.getMessage().getText());
            }
        }
    }
}

