package com.example.springaitest.bot.service;


import com.example.springaitest.api.config.Repository.CategoryRepository;
import com.example.springaitest.api.config.Repository.TransactionRepository;
import com.example.springaitest.api.config.domain.Category;
import com.example.springaitest.api.config.domain.Transaction;
import com.example.springaitest.api.config.domain.TransactionType;
import com.example.springaitest.api.config.dto.CategorizationResult;
import com.example.springaitest.api.config.dto.CategorizedTransaction;
import com.example.springaitest.api.config.dto.ParsedResult;
import com.example.springaitest.api.config.dto.ParsedTransaction;
import com.example.springaitest.api.config.service.CategorizationService;
import com.example.springaitest.api.config.service.DocumentParserService;
import com.example.springaitest.bot.events.MessageEvent;
import com.example.springaitest.bot.model.UserSession;
import com.example.springaitest.bot.model.UserSessionService;
import com.example.springaitest.bot.model.UserState;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Document;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static reactor.netty.http.HttpConnectionLiveness.log;

@Service
@Slf4j
@AllArgsConstructor
public class UploadWorkFlowService {

    private final TelegramFileService fileService;
    private final DocumentParserService parserService;
    private final CategorizationService categorizationService;
    private final UserSessionService userSessionService;
    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;
    private final ApplicationEventPublisher eventPublisher;


    public void processUpload(Long chatId, Document telegramDoc) {
        userSessionService.getSession(chatId).setUserState(UserState.PROCESSING);

        eventPublisher.publishEvent(new MessageEvent(this,
                SendMessage.builder().chatId(chatId).text("📄 Processing " + telegramDoc.getFileName() + "...").build()
        ));

        InputStream file = fileService.downloadFile(telegramDoc.getFileId());

        ParsedResult parsedResult = parserService.parse(file, telegramDoc.getFileName()).orElse(null);

        if (parsedResult == null || parsedResult.transactions() == null || parsedResult.transactions().isEmpty()) {
            eventPublisher.publishEvent(new MessageEvent(this,
                    SendMessage.builder().chatId(chatId).text("❌ I couldn't find any transactions in this file! Are you sure it's a valid bank statement?").build()
            ));
            userSessionService.getSession(chatId).setUserState(UserState.IDLE);
            return;
        }

        List<ParsedTransaction> parsedTransactions = parsedResult.transactions();

        CategorizationResult categorizationResult = categorizationService.categorize(parsedTransactions, chatId);

        String batchId = UUID.randomUUID().toString();
        List<Transaction> entitiesToSave = new ArrayList<>();

        for (CategorizedTransaction ct : categorizationResult.autoCategorized()) {

            ParsedTransaction original = parsedTransactions.get(ct.transactionIndex());
            Category category = categoryRepository.findByNameAndTelegramUserId(ct.suggestedCategory(), chatId)
                    .or(() -> categoryRepository.findByNameAndTelegramUserId(ct.suggestedCategory(), 0L))
                    .orElse(null);
            Transaction entity = Transaction.builder()
                    .date(LocalDate.parse(original.date(), DateTimeFormatter.ofPattern("dd.MM.yyyy")))
                    .balance(BigDecimal.valueOf(original.balance()))
                    .amount(BigDecimal.valueOf(original.amount()))
                    .type(original.amount() < 0 ? TransactionType.EXPENSE : TransactionType.INCOME)
                    .description(original.description())
                    .category(category)
                    .uploadBatchId(batchId)
                    .uploadedAt(LocalDateTime.now())
                    .telegramUserId(chatId)
                    .build();
            entitiesToSave.add(entity);

        }
        transactionRepository.saveAll(entitiesToSave);
        UserSession session = userSessionService.getSession(chatId);
        if (categorizationResult.needsReview() != null && !categorizationResult.needsReview().isEmpty()) {

            session.setPendingReview(categorizationResult.needsReview());
            session.setUserState(UserState.REVIEWING);
            session.setOriginalTransactions(parsedTransactions);
            session.setCurrentReviewIndex(0);
            session.setUploadBatch(batchId);
            eventPublisher.publishEvent(new MessageEvent(this,buildReviewMessage(chatId, session)));
        } else {

            eventPublisher.publishEvent(new MessageEvent(this, SendMessage.builder()
                    .chatId(chatId).text("🎉 All done! No reviews needed.").build()));
            session.setUserState(UserState.IDLE);

        }
    }
        private SendMessage buildReviewMessage(Long chatId, UserSession session) {
            CategorizedTransaction review = session.getPendingReview().get(session.getCurrentReviewIndex());
            ParsedTransaction original = session.getOriginalTransactions().get(review.transactionIndex());
            String text = String.format("""
            📍 Transaction %d/%d
            
            📅 Date: %s
            📝 %s
            💰 Amount: %.2f
            
            🤖 AI suggests: %s
            """,
                    session.getCurrentReviewIndex() + 1,
                    session.getPendingReview().size(),
                    original.date(),
                    original.description(),
                    original.amount(),
                    review.suggestedCategory()
            );
            // Build Keyboard buttons
            InlineKeyboardButton btn1 = InlineKeyboardButton.builder()
                    .text("✅ " + review.suggestedCategory())
                    .callbackData("cat:" + review.transactionIndex() + ":" + review.suggestedCategory())
                    .build();

            InlineKeyboardButton btnSkip = InlineKeyboardButton.builder()
                    .text("⏭️ Skip")
                    .callbackData("skip:" + review.transactionIndex())
                    .build();

            InlineKeyboardButton btnNew = InlineKeyboardButton.builder()
                    .text("➕ New Category")
                    .callbackData("new:" + review.transactionIndex())
                    .build();

            InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                    .keyboardRow(new InlineKeyboardRow(btn1))
                    .keyboardRow(new InlineKeyboardRow(btnSkip))
                    .keyboardRow(new InlineKeyboardRow(btnNew))
                    .build();
            return SendMessage.builder()
                    .chatId(chatId)
                    .text(text)
                    .replyMarkup(keyboard)
                    .build();
        }
    }

