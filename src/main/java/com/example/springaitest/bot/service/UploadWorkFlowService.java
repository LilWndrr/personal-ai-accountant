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
import com.example.springaitest.api.config.service.TransactionDeduplicationService;
import com.example.springaitest.bot.events.MessageEvent;
import com.example.springaitest.bot.model.UserSession;
import com.example.springaitest.bot.model.UserSessionService;
import com.example.springaitest.bot.model.UserState;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction;
import org.telegram.telegrambots.meta.api.objects.Document;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.springframework.scheduling.annotation.Async;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;



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
    private final TransactionDeduplicationService deduplicationService;


    @Async
    public void processUpload(Long chatId, Document telegramDoc) {
        UserSession session = userSessionService.getSession(chatId);
        java.time.LocalDate today = java.time.LocalDate.now();
        
        if (session.getLastCategorizationDate() == null || session.getLastCategorizationDate().isBefore(today)) {
            session.setRemainingCategorizations(5);
            session.setLastCategorizationDate(today);
        }

        if (session.getRemainingCategorizations() <= 0) {
            eventPublisher.publishEvent(new MessageEvent(this,
                    SendMessage.builder().chatId(chatId)
                            .text("⚠️ You've reached your daily limit of 5 file uploads. Please try again tomorrow!")
                            .build()
            ));
            return;
        }


        session.setLastCategorizationDate(today);

        session.setUserState(UserState.PROCESSING);

        eventPublisher.publishEvent(new MessageEvent(this,
                SendMessage.builder().chatId(chatId).text("📄 Processing " + telegramDoc.getFileName() + "...").build()
        ));
        
        eventPublisher.publishEvent(new MessageEvent(this,
                SendChatAction.builder().chatId(chatId).action("typing").build()
        ));

        InputStream file = fileService.downloadFile(telegramDoc.getFileId());

        if(file==null){
            eventPublisher.publishEvent(new MessageEvent(this,SendMessage.builder().chatId(chatId).text("File size should be less than 5 Mb").build()));
            userSessionService.getSession(chatId).setUserState(UserState.IDLE);

            return;
        }else{
            session.setRemainingCategorizations(session.getRemainingCategorizations() - 1);
        }

        ParsedResult parsedResult;
        try {
            parsedResult = parserService.parse(file, telegramDoc.getFileName()).orElse(null);
        } catch (IllegalArgumentException e) {
            log.warn("Document parsing validation failed: {}", e.getMessage());
            eventPublisher.publishEvent(new MessageEvent(this,
                    SendMessage.builder().chatId(chatId).text("⚠️ " + e.getMessage()).build()
            ));
            userSessionService.getSession(chatId).setUserState(UserState.IDLE);
            return;
        } catch (Exception e) {
            log.error("Failed to parse document: {}", telegramDoc.getFileName(), e);
            eventPublisher.publishEvent(new MessageEvent(this,
                    SendMessage.builder().chatId(chatId).text("❌ Failed to process document. Please try again.").build()
            ));
            userSessionService.getSession(chatId).setUserState(UserState.IDLE);
            return;
        }
        log.info("Finished parsing file: {}", telegramDoc.getFileName());

        if (parsedResult == null || parsedResult.transactions() == null || parsedResult.transactions().isEmpty()) {
            eventPublisher.publishEvent(new MessageEvent(this,
                    SendMessage.builder().chatId(chatId).text("❌ I couldn't find any transactions in this file! Are you sure it's a valid bank statement?").build()
            ));
            userSessionService.getSession(chatId).setUserState(UserState.IDLE);
            return;
        }

        String bankName = parsedResult.bankName();
        List<ParsedTransaction> parsedTransactions = parsedResult.transactions();

        // Filter out duplicate transactions that already exist in the database
        int totalParsed = parsedTransactions.size();
        parsedTransactions = deduplicationService.filterDuplicates(parsedTransactions, bankName, chatId);
        int duplicateCount = totalParsed - parsedTransactions.size();

        if (duplicateCount > 0) {
            eventPublisher.publishEvent(new MessageEvent(this,
                    SendMessage.builder().chatId(chatId)
                            .text("⚠️ Skipped " + duplicateCount + " duplicate transaction(s) already in your records.")
                            .build()
            ));
        }

        if (parsedTransactions.isEmpty()) {
            eventPublisher.publishEvent(new MessageEvent(this,
                    SendMessage.builder().chatId(chatId)
                            .text("ℹ️ All transactions in this file are already in your records. Nothing new to save!")
                            .build()
            ));
            userSessionService.getSession(chatId).setUserState(UserState.IDLE);
            return;
        }

        log.info("Started categorization for {} transactions", parsedTransactions.size());
        CategorizationResult categorizationResult = categorizationService.categorize(parsedTransactions, chatId);

        String batchId = UUID.randomUUID().toString();
        List<Transaction> entitiesToSave = new ArrayList<>();

        for (CategorizedTransaction ct : categorizationResult.autoCategorized()) {

            ParsedTransaction original = parsedTransactions.get(ct.transactionIndex());
            Category category = categoryRepository.findByNameAndTelegramUserId(ct.suggestedCategory(), chatId)
                    .or(() -> categoryRepository.findByNameAndTelegramUserId(ct.suggestedCategory(), 0L))
                    .orElseGet(() -> categoryRepository.save(
                            Category.builder()
                                    .name(ct.suggestedCategory())
                                    .emoji(ct.suggestedEmoji())
                                    .custom(true)
                                    .telegramUserId(chatId)
                                    .build()
                    ));
            Transaction entity = Transaction.builder()
                    .date(LocalDate.parse(original.date(), DateTimeFormatter.ofPattern("dd[.][/][-]MM[.][/][-]yyyy")))
                    .balance(BigDecimal.valueOf(original.balance()))
                    .amount(BigDecimal.valueOf(original.amount()))
                    .type(original.amount() < 0 ? TransactionType.EXPENSE : TransactionType.INCOME)
                    .description(original.description())
                    .merchantName(original.merchantName())
                    .bankName(bankName)
                    .category(category)
                    .uploadBatchId(batchId)
                    .uploadedAt(LocalDateTime.now())
                    .telegramUserId(chatId)
                    .build();
            entitiesToSave.add(entity);

        }
        transactionRepository.saveAll(entitiesToSave);
        if (categorizationResult.needsReview() != null && !categorizationResult.needsReview().isEmpty()) {

            session.setPendingReview(categorizationResult.needsReview());
            session.setUserState(UserState.REVIEWING);
            session.setOriginalTransactions(parsedTransactions);
            session.setCurrentReviewIndex(0);
            session.setUploadBatch(batchId);
            session.setBankName(bankName);
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

