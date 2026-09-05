package com.example.springaitest.bot.service;


import com.example.springaitest.api.config.Repository.CategoryRepository;
import com.example.springaitest.api.config.Repository.MerchantMappingRepository;
import com.example.springaitest.api.config.Repository.TransactionRepository;
import com.example.springaitest.api.config.domain.Category;
import com.example.springaitest.api.config.domain.MappingSource;
import com.example.springaitest.api.config.domain.MerchantMapping;
import com.example.springaitest.api.config.domain.Transaction;
import com.example.springaitest.api.config.domain.TransactionType;
import com.example.springaitest.api.config.dto.CategorizedTransaction;
import com.example.springaitest.api.config.dto.ParsedTransaction;
import com.example.springaitest.bot.events.MessageEvent;
import com.example.springaitest.bot.model.UserSession;
import com.example.springaitest.bot.model.UserSessionService;
import com.example.springaitest.bot.model.UserState;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
public class CustomCategoryService {

    private final CategoryRepository categoryRepository;
    private final UserSessionService userSessionService;
    private final TransactionRepository transactionRepository;
    private final MerchantMappingRepository merchantMappingRepository;
    private final ApplicationEventPublisher eventPublisher;

    public void handleCategoryCreating(Long chatId, String categoryName) {
        UserSession session = userSessionService.getSession(chatId);

        Category category = categoryRepository.findByNameAndTelegramUserId(categoryName, chatId)
                .orElseGet(() -> categoryRepository.save(
                        Category.builder().name(categoryName).custom(true).telegramUserId(chatId).build()
                ));


        CategorizedTransaction review = session.getPendingReview().get(session.getCurrentReviewIndex());
        ParsedTransaction original = session.getOriginalTransactions().get(review.transactionIndex());
        // 3. Build and save the Transaction (Just like you did in CallbackHandlerService!)
        Transaction entity = Transaction.builder()
                .date(LocalDate.parse(original.date(), DateTimeFormatter.ofPattern("dd.MM.yyyy")))
                .balance(BigDecimal.valueOf(original.balance()))
                .amount(BigDecimal.valueOf(original.amount()))
                .type(original.amount() < 0 ? TransactionType.EXPENSE : TransactionType.INCOME)
                .description(original.description())
                .category(category) // <-- Map it to your shiny new category!
                .uploadBatchId(session.getUploadBatch())
                .uploadedAt(LocalDateTime.now())
                .telegramUserId(chatId)
                .build();
        transactionRepository.save(entity);

        // Learn: save merchant mapping so this merchant auto-categorizes next time
        saveMerchantMappingIfNew(original.merchantName(), category, chatId, MappingSource.USER_CREATED);

        session.setCurrentReviewIndex(session.getCurrentReviewIndex() + 1);
        session.setUserState(UserState.REVIEWING);

        if (session.getCurrentReviewIndex() < session.getPendingReview().size()) {
            eventPublisher.publishEvent(new MessageEvent(this, buildReviewMessage(chatId, session)));
        } else {
            session.setUserState(UserState.IDLE);
            session.setPendingReview(null);
            eventPublisher.publishEvent(new MessageEvent(this, SendMessage.builder()
                    .chatId(chatId).text("🎉 All done! Review complete.").build()));
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

    private void saveMerchantMappingIfNew(String merchantName, Category category, Long chatId, MappingSource source) {
        if (merchantName == null || merchantName.isBlank() || category == null) return;
        if (merchantMappingRepository.existsByMerchantKeywordAndTelegramUserId(merchantName, chatId)) return;

        merchantMappingRepository.save(MerchantMapping.builder()
                .merchantKeyword(merchantName)
                .category(category)
                .telegramUserId(chatId)
                .source(source)
                .build());
    }
}
