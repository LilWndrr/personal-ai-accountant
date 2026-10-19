package com.example.springaitest.bot.state;

import com.example.springaitest.bot.commands.CommandHandler;
import com.example.springaitest.bot.model.UserState;
import com.example.springaitest.bot.service.CategoryManagementService;
import com.example.springaitest.bot.service.ReportService;
import com.example.springaitest.bot.service.TransactionEditService;
import com.example.springaitest.bot.service.TransactionListService;
import com.example.springaitest.bot.service.UploadWorkFlowService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;

@Component
@RequiredArgsConstructor
public class IdleStateHandler implements StateHandler {
    private final UploadWorkFlowService uploadWorkFlowService;
    private final CommandHandler commandHandler;
    private final ReportService reportService;
    private final TransactionListService transactionListService;
    private final TransactionEditService transactionEditService;
    private final CategoryManagementService categoryManagementService;

    @Override
    public UserState getSupportedState() { return UserState.IDLE; }

    @Override
    public void handle(Update update, Long chatId) {
        if(update.hasMessage() && update.getMessage().hasDocument()){
            uploadWorkFlowService.processUpload(chatId, update.getMessage().getDocument());
        } else if (update.hasMessage() && update.getMessage().hasText()) {
            commandHandler.handle(update);
        } else if (update.hasCallbackQuery()) {
            String callbackData = update.getCallbackQuery().getData();
            if (callbackData.startsWith("report:")) {
                reportService.handleReportCallback(chatId, callbackData);
            } else if (callbackData.startsWith("transactions:")) {
                transactionListService.handleTransactionsCallback(chatId, callbackData);
            } else if (callbackData.startsWith("txlist:")) {
                transactionListService.handlePageCallback(chatId, callbackData);
            } else if (callbackData.startsWith("tx:")) {
                transactionEditService.handleCallback(chatId, callbackData);
            } else if (callbackData.startsWith("catmgmt:")) {
                categoryManagementService.handleCallback(chatId, callbackData);
            }
        }
    }
}
