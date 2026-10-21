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
import com.example.springaitest.bot.model.UserState;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction;
import org.telegram.telegrambots.meta.api.objects.Document;
import org.springframework.scheduling.annotation.Async;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

        try {
            session.setLastCategorizationDate(today);

            session.setUserState(UserState.PROCESSING);

            eventPublisher.publishEvent(new MessageEvent(this,
                    SendMessage.builder().chatId(chatId).text("📄 Processing " + telegramDoc.getFileName() + "...").build()
            ));

            eventPublisher.publishEvent(new MessageEvent(this,
                    SendChatAction.builder().chatId(chatId).action("typing").build()
            ));

            InputStream file = fileService.downloadFile(telegramDoc.getFileId());

            if (file == null) {
                eventPublisher.publishEvent(new MessageEvent(this, SendMessage.builder().chatId(chatId).text("File size should be less than 5 Mb").build()));
                session.setUserState(UserState.IDLE);

                return;
            } else {
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
                session.setUserState(UserState.IDLE);
                return;
            } catch (Exception e) {
                log.error("Failed to parse document: {}", telegramDoc.getFileName(), e);
                eventPublisher.publishEvent(new MessageEvent(this,
                        SendMessage.builder().chatId(chatId).text("❌ Failed to process document. Please try again.").build()
                ));
                session.setUserState(UserState.IDLE);
                return;
            }
            log.info("Finished parsing file: {}", telegramDoc.getFileName());

            if (parsedResult == null || parsedResult.transactions() == null || parsedResult.transactions().isEmpty()) {
                String text = (parsedResult != null && parsedResult.failedPageCount() > 0)
                        ? "❌ Failed to parse this file — " + parsedResult.failedPageCount() + " of " + parsedResult.totalPageCount()
                                + " page(s) couldn't be read. Try re-uploading, or a different file."
                        : "❌ I couldn't find any transactions in this file! Are you sure it's a valid bank statement?";
                eventPublisher.publishEvent(new MessageEvent(this,
                        SendMessage.builder().chatId(chatId).text(text).build()
                ));
                session.setUserState(UserState.IDLE);
                return;
            }

            if (parsedResult.failedPageCount() > 0) {
                eventPublisher.publishEvent(new MessageEvent(this,
                        SendMessage.builder().chatId(chatId)
                                .text("⚠️ " + parsedResult.failedPageCount() + " of " + parsedResult.totalPageCount()
                                        + " page(s) failed to parse — you may be missing some transactions. Try re-uploading if the numbers look off.")
                                .build()
                ));
            }

            String bankName = parsedResult.bankName();
            // Keys are assigned over the whole statement, before any filtering, so the
            // same statement always produces the same keys (see TransactionDeduplicationService).
            List<ParsedTransaction> parsedTransactions = deduplicationService.assignKeys(parsedResult.transactions());

            // Captured before dedup filtering (which re-indexes the list) so we can
            // still tell which surviving transactions were flagged, by identity rather than index.
            java.util.Set<ParsedTransaction> flaggedForBalanceMismatch = new java.util.HashSet<>();
            for (int invalidIndex : parsedResult.validation().invalidIndices()) {
                flaggedForBalanceMismatch.add(parsedTransactions.get(invalidIndex));
            }

            // Drop transactions already saved; ones that only look like a saved one
            // (same transaction from another file format) are kept for the user to confirm.
            TransactionDeduplicationService.DedupResult dedup = deduplicationService.check(parsedTransactions, chatId);
            parsedTransactions = dedup.newTransactions();
            int duplicateCount = dedup.exactDuplicates();
            Map<String, Transaction> likelyDuplicates = dedup.likelyDuplicates();

            if (duplicateCount > 0) {
                eventPublisher.publishEvent(new MessageEvent(this,
                        SendMessage.builder().chatId(chatId)
                                .text("⚠️ Skipped " + duplicateCount + " duplicate transaction(s) already in your records.")
                                .build()
                ));
            }
            if (!likelyDuplicates.isEmpty()) {
                eventPublisher.publishEvent(new MessageEvent(this,
                        SendMessage.builder().chatId(chatId)
                                .text("🔎 " + likelyDuplicates.size() + " transaction(s) look like ones you already have"
                                        + " — I'll ask you before saving them.")
                                .build()
                ));
            }

            if (parsedTransactions.isEmpty()) {
                eventPublisher.publishEvent(new MessageEvent(this,
                        SendMessage.builder().chatId(chatId)
                                .text("ℹ️ All transactions in this file are already in your records. Nothing new to save!")
                                .build()
                ));
                session.setUserState(UserState.IDLE);
                return;
            }

            log.info("Started categorization for {} transactions", parsedTransactions.size());
            CategorizationResult categorizationResult = categorizationService.categorize(parsedTransactions, chatId);

            String batchId = UUID.randomUUID().toString();
            List<Transaction> entitiesToSave = new ArrayList<>();
            List<CategorizedTransaction> needsReview = new ArrayList<>();
            for (CategorizedTransaction ct : categorizationResult.needsReview()) {
                Transaction saved = likelyDuplicates.get(parsedTransactions.get(ct.transactionIndex()).dedupKey());
                needsReview.add(saved != null ? ReviewMessages.flagLikelyDuplicate(ct, saved) : ct);
            }

            for (CategorizedTransaction ct : categorizationResult.autoCategorized()) {

                ParsedTransaction original = parsedTransactions.get(ct.transactionIndex());

                CategorizedTransaction toReview = null;
                if (flaggedForBalanceMismatch.contains(original)) {
                    toReview = new CategorizedTransaction(
                            ct.transactionIndex(),
                            ct.suggestedCategory(),
                            ct.suggestedEmoji(),
                            ct.confidence(),
                            ct.merchantName(),
                            "⚠️ Balance mismatch detected — please double-check this amount. " + ct.reasoning()
                    );
                }
                Transaction saved = likelyDuplicates.get(original.dedupKey());
                if (saved != null) {
                    toReview = ReviewMessages.flagLikelyDuplicate(toReview != null ? toReview : ct, saved);
                }
                if (toReview != null) {
                    needsReview.add(toReview);
                    continue;
                }

                Category category = categoryRepository.findOrCreateCategory(
                        ct.suggestedCategory(), chatId, ct.suggestedEmoji(), true);
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
                        .dedupKey(deduplicationService.keyOf(original))
                        .build();
                entitiesToSave.add(entity);

            }
            // Row by row so a transaction saved concurrently (e.g. the same file sent
            // twice at once) is skipped instead of failing the whole batch.
            long alreadySaved = entitiesToSave.stream().filter(e -> !transactionRepository.saveIfNew(e)).count();
            if (alreadySaved > 0) {
                log.info("Skipped {} transactions saved concurrently for user {}", alreadySaved, chatId);
            }
            if (!needsReview.isEmpty()) {

                session.setPendingReview(needsReview);
                session.setUserState(UserState.REVIEWING);
                session.setOriginalTransactions(parsedTransactions);
                session.setCurrentReviewIndex(0);
                session.setUploadBatch(batchId);
                session.setBankName(bankName);
                eventPublisher.publishEvent(new MessageEvent(this, buildReviewMessage(chatId, session)));
            } else {

                eventPublisher.publishEvent(new MessageEvent(this, SendMessage.builder()
                        .chatId(chatId).text("🎉 All done! No reviews needed.\n\n" + quotaFooter(session)).build()));
                session.setUserState(UserState.IDLE);

            }

        }catch ( Exception ex){
            session.setUserState(UserState.IDLE);
            log.error("Smth went wrong", ex);
        } finally {
            userSessionService.saveSession(session);
        }
    }
        private String quotaFooter(UserSession session) {
            int remaining = session.getRemainingCategorizations();
            if (remaining <= 0) {
                return "ℹ️ That was your last upload for today — resets tomorrow.";
            }
            return "ℹ️ " + remaining + " upload" + (remaining == 1 ? "" : "s") + " left today.";
        }

        private SendMessage buildReviewMessage(Long chatId, UserSession session) {
            return ReviewMessages.build(chatId, session, quotaFooter(session));
        }
    }

