package com.example.springaitest.bot.service;

import com.example.springaitest.api.config.Repository.CategoryRepository;
import com.example.springaitest.api.config.Repository.TransactionRepository;
import com.example.springaitest.api.config.domain.Category;
import com.example.springaitest.api.config.domain.Transaction;
import com.example.springaitest.bot.events.MessageEvent;
import com.example.springaitest.bot.model.UserSession;
import com.example.springaitest.bot.model.UserState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Handles acting on a single already-saved transaction: viewing its detail,
 * changing its category, or deleting it. Reached from the "⚙️" buttons
 * TransactionListService attaches to each listed transaction.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionEditService {

    private static final DateTimeFormatter DISPLAY_DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final int MAX_CATEGORY_BUTTONS = 20;

    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;
    private final UserSessionService userSessionService;
    private final ApplicationEventPublisher eventPublisher;
    private final MessageTrackerService messageTrackerService;

    /** Callback data: "tx:<action>:<transactionId>[:<categoryId>]" */
    @Transactional
    public void handleCallback(Long chatId, String data) {
        messageTrackerService.deleteLastMessage(chatId);

        String[] parts = data.split(":");
        if (parts.length < 3) return;

        String action = parts[1];
        Long transactionId;
        try {
            transactionId = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            return;
        }

        Optional<Transaction> maybeTransaction = findOwned(chatId, transactionId);
        if (maybeTransaction.isEmpty()) {
            eventPublisher.publishEvent(new MessageEvent(this, SendMessage.builder()
                    .chatId(chatId)
                    .text("⚠️ That transaction is no longer available.")
                    .build()));
            return;
        }
        Transaction transaction = maybeTransaction.get();

        switch (action) {
            case "open" -> sendDetail(chatId, transaction);
            case "editcat" -> sendCategoryPicker(chatId, transaction);
            case "newcat" -> promptForNewCategoryName(chatId, transaction);
            case "del" -> sendDeleteConfirmation(chatId, transaction);
            case "delyes" -> {
                transactionRepository.delete(transaction);
                eventPublisher.publishEvent(new MessageEvent(this, SendMessage.builder()
                        .chatId(chatId)
                        .text("🗑 Deleted.")
                        .build()));
            }
            case "delno" -> sendDetail(chatId, transaction);
            case "setcat" -> {
                if (parts.length < 4) return;
                try {
                    Long categoryId = Long.parseLong(parts[3]);
                    categoryRepository.findById(categoryId).ifPresent(category -> {
                        transaction.setCategory(category);
                        transactionRepository.save(transaction);
                        eventPublisher.publishEvent(new MessageEvent(this, SendMessage.builder()
                                .chatId(chatId)
                                .text("✅ Category updated to " + displayName(category) + ".")
                                .build()));
                    });
                } catch (NumberFormatException e) {
                    log.warn("Malformed category id in callback: {}", data);
                }
            }
            default -> log.warn("Unknown transaction action '{}' in callback: {}", action, data);
        }
    }

    public void handleNewCategoryNameInput(Long chatId, String categoryName) {
        UserSession session = userSessionService.getSession(chatId);
        Long transactionId = session.getEditingTransactionId();

        session.setUserState(UserState.IDLE);
        session.setEditingTransactionId(null);
        userSessionService.saveSession(session);

        if (transactionId == null) return;

        findOwned(chatId, transactionId).ifPresentOrElse(transaction -> {
            Category category = categoryRepository.findOrCreateCategory(categoryName.trim(), chatId, null, true);
            transaction.setCategory(category);
            transactionRepository.save(transaction);
            eventPublisher.publishEvent(new MessageEvent(this, SendMessage.builder()
                    .chatId(chatId)
                    .text("✅ Category updated to " + displayName(category) + ".")
                    .build()));
        }, () -> eventPublisher.publishEvent(new MessageEvent(this, SendMessage.builder()
                .chatId(chatId)
                .text("⚠️ That transaction is no longer available.")
                .build())));
    }

    private Optional<Transaction> findOwned(Long chatId, Long transactionId) {
        return transactionRepository.findById(transactionId)
                .filter(t -> t.getTelegramUserId().equals(chatId));
    }

    private void sendDetail(Long chatId, Transaction transaction) {
        String emoji = transaction.getCategory() != null && transaction.getCategory().getEmoji() != null
                ? transaction.getCategory().getEmoji() + " " : "";
        String categoryName = transaction.getCategory() != null ? transaction.getCategory().getName() : "Uncategorized";
        String sign = transaction.getAmount().signum() < 0 ? "-" : "+";

        String text = String.format("""
                📄 %s
                %s
                💰 %s%.2f ₺
                🏷 %s%s
                """,
                transaction.getDate().format(DISPLAY_DATE_FORMAT),
                transaction.getDescription(),
                sign, transaction.getAmount().abs(),
                emoji, categoryName
        );

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder()
                        .text("✏️ Change Category")
                        .callbackData("tx:editcat:" + transaction.getId())
                        .build()))
                .keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder()
                        .text("🗑 Delete")
                        .callbackData("tx:del:" + transaction.getId())
                        .build()))
                .build();

        eventPublisher.publishEvent(new MessageEvent(this, SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .replyMarkup(keyboard)
                .build()));
    }

    private void sendCategoryPicker(Long chatId, Transaction transaction) {
        List<Category> categories = Stream.concat(
                        categoryRepository.findByTelegramUserId(chatId).stream(),
                        categoryRepository.findByTelegramUserId(0L).stream())
                .distinct()
                .limit(MAX_CATEGORY_BUTTONS)
                .toList();

        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (Category category : categories) {
            rows.add(new InlineKeyboardRow(InlineKeyboardButton.builder()
                    .text(displayName(category))
                    .callbackData("tx:setcat:" + transaction.getId() + ":" + category.getId())
                    .build()));
        }
        rows.add(new InlineKeyboardRow(InlineKeyboardButton.builder()
                .text("➕ Type a new category")
                .callbackData("tx:newcat:" + transaction.getId())
                .build()));

        eventPublisher.publishEvent(new MessageEvent(this, SendMessage.builder()
                .chatId(chatId)
                .text("Pick a new category:")
                .replyMarkup(InlineKeyboardMarkup.builder().keyboard(rows).build())
                .build()));
    }

    private void promptForNewCategoryName(Long chatId, Transaction transaction) {
        UserSession session = userSessionService.getSession(chatId);
        session.setUserState(UserState.EDITING_TRANSACTION_CATEGORY);
        session.setEditingTransactionId(transaction.getId());
        userSessionService.saveSession(session);

        eventPublisher.publishEvent(new MessageEvent(this, SendMessage.builder()
                .chatId(chatId)
                .text("✏️ Send me the new category name, or /cancel to go back.")
                .build()));
    }

    private void sendDeleteConfirmation(Long chatId, Transaction transaction) {
        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("✅ Yes, delete").callbackData("tx:delyes:" + transaction.getId()).build(),
                        InlineKeyboardButton.builder().text("❌ No").callbackData("tx:delno:" + transaction.getId()).build()
                ))
                .build();

        eventPublisher.publishEvent(new MessageEvent(this, SendMessage.builder()
                .chatId(chatId)
                .text("🗑 Delete this transaction? This can't be undone.")
                .replyMarkup(keyboard)
                .build()));
    }

    private String displayName(Category category) {
        String emoji = category.getEmoji() != null ? category.getEmoji() + " " : "";
        return emoji + category.getName();
    }
}
