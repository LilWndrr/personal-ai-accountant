package com.example.springaitest.bot.service;

import com.example.springaitest.api.config.Repository.CategoryRepository;
import com.example.springaitest.api.config.Repository.TransactionRepository;
import com.example.springaitest.api.config.domain.Category;
import com.example.springaitest.bot.events.MessageEvent;
import com.example.springaitest.bot.model.UserSession;
import com.example.springaitest.bot.model.UserState;
import jakarta.validation.ConstraintViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Lets a user see, rename, and delete their own custom categories.
 * Shared system-default categories (telegramUserId 0) are never listed
 * here, since they belong to everyone, not one user.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CategoryManagementService {

    private static final int MAX_REASSIGN_TARGETS = 20;

    private final CategoryRepository categoryRepository;
    private final TransactionRepository transactionRepository;
    private final UserSessionService userSessionService;
    private final ApplicationEventPublisher eventPublisher;
    private final MessageTrackerService messageTrackerService;

    @Transactional(readOnly = true)
    public void sendCategoryList(Long chatId) {
        List<Category> categories = categoryRepository.findByTelegramUserId(chatId);

        if (categories.isEmpty()) {
            eventPublisher.publishEvent(new MessageEvent(this, SendMessage.builder()
                    .chatId(chatId)
                    .text("You haven't created any custom categories yet — they're created automatically when you confirm or type one during review.")
                    .build()));
            return;
        }

        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (Category category : categories) {
            rows.add(new InlineKeyboardRow(InlineKeyboardButton.builder()
                    .text(displayName(category))
                    .callbackData("catmgmt:open:" + category.getId())
                    .build()));
        }

        eventPublisher.publishEvent(new MessageEvent(this, SendMessage.builder()
                .chatId(chatId)
                .text("🏷 Your categories:")
                .replyMarkup(InlineKeyboardMarkup.builder().keyboard(rows).build())
                .build()));
    }

    /** Callback data: "catmgmt:<action>:<categoryId>[:<targetCategoryId>]" */
    @Transactional
    public void handleCallback(Long chatId, String data) {
        messageTrackerService.deleteLastMessage(chatId);

        String[] parts = data.split(":");
        if (parts.length < 3) return;

        String action = parts[1];
        Long categoryId;
        try {
            categoryId = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            return;
        }

        Optional<Category> maybeCategory = findOwned(chatId, categoryId);
        if (maybeCategory.isEmpty()) {
            eventPublisher.publishEvent(new MessageEvent(this, SendMessage.builder()
                    .chatId(chatId)
                    .text("⚠️ That category is no longer available.")
                    .build()));
            return;
        }
        Category category = maybeCategory.get();

        switch (action) {
            case "open" -> sendDetail(chatId, category);
            case "rename" -> promptForRename(chatId, category);
            case "del" -> sendDeletePrompt(chatId, category);
            case "delyes" -> {
                categoryRepository.delete(category);
                eventPublisher.publishEvent(new MessageEvent(this, SendMessage.builder()
                        .chatId(chatId)
                        .text("🗑 Deleted " + displayName(category) + ".")
                        .build()));
            }
            case "delno" -> sendDetail(chatId, category);
            case "reassign" -> {
                if (parts.length < 4) return;
                try {
                    Long targetId = Long.parseLong(parts[3]);
                    Optional<Category> target = categoryRepository.findById(targetId);
                    if (target.isEmpty()) return;

                    int moved = transactionRepository.reassignCategory(category.getId(), target.get());
                    categoryRepository.delete(category);
                    eventPublisher.publishEvent(new MessageEvent(this, SendMessage.builder()
                            .chatId(chatId)
                            .text("✅ Moved " + moved + " transaction(s) to " + displayName(target.get())
                                    + " and deleted " + displayName(category) + ".")
                            .build()));
                } catch (NumberFormatException e) {
                    log.warn("Malformed target category id in callback: {}", data);
                }
            }
            default -> log.warn("Unknown category action '{}' in callback: {}", action, data);
        }
    }

    public void handleRenameInput(Long chatId, String newName) {
        UserSession session = userSessionService.getSession(chatId);
        Long categoryId = session.getEditingCategoryId();

        session.setUserState(UserState.IDLE);
        session.setEditingCategoryId(null);
        userSessionService.saveSession(session);

        if (categoryId == null) return;

            Optional<Category> maybeCategory = findOwned(chatId, categoryId);
        if (maybeCategory.isEmpty()) {
            eventPublisher.publishEvent(new MessageEvent(this, SendMessage.builder()
                    .chatId(chatId)
                    .text("⚠️ That category is no longer available.")
                    .build()));
            return;
        }

        Category category = maybeCategory.get();
        category.setName(newName.trim());
        try {
            categoryRepository.save(category);
        } catch (ConstraintViolationException e) {
            eventPublisher.publishEvent(new MessageEvent(this, SendMessage.builder()
                    .chatId(chatId)
                    .text("⚠️ Category names must be 2–20 characters. Try again, or /cancel.")
                    .build()));
            return;
        }

        eventPublisher.publishEvent(new MessageEvent(this, SendMessage.builder()
                .chatId(chatId)
                .text("✅ Renamed to " + displayName(category) + ".")
                .build()));
    }

    private Optional<Category> findOwned(Long chatId, Long categoryId) {
        return categoryRepository.findById(categoryId)
                .filter(c -> c.getTelegramUserId().equals(chatId));
    }

    private void sendDetail(Long chatId, Category category) {
        long usageCount = transactionRepository.countByCategory_Id(category.getId());

        String text = displayName(category) + "\nUsed by " + usageCount + " transaction(s).";

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder()
                        .text("✏️ Rename")
                        .callbackData("catmgmt:rename:" + category.getId())
                        .build()))
                .keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder()
                        .text("🗑 Delete")
                        .callbackData("catmgmt:del:" + category.getId())
                        .build()))
                .build();

        eventPublisher.publishEvent(new MessageEvent(this, SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .replyMarkup(keyboard)
                .build()));
    }

    private void promptForRename(Long chatId, Category category) {
        UserSession session = userSessionService.getSession(chatId);
        session.setUserState(UserState.AWAITING_CATEGORY_RENAME);
        session.setEditingCategoryId(category.getId());
        userSessionService.saveSession(session);

        eventPublisher.publishEvent(new MessageEvent(this, SendMessage.builder()
                .chatId(chatId)
                .text("✏️ Send me the new name for " + displayName(category) + ", or /cancel to go back.")
                .build()));
    }

    private void sendDeletePrompt(Long chatId, Category category) {
        long usageCount = transactionRepository.countByCategory_Id(category.getId());

        if (usageCount == 0) {
            InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                    .keyboardRow(new InlineKeyboardRow(
                            InlineKeyboardButton.builder().text("✅ Yes, delete").callbackData("catmgmt:delyes:" + category.getId()).build(),
                            InlineKeyboardButton.builder().text("❌ No").callbackData("catmgmt:delno:" + category.getId()).build()
                    ))
                    .build();

            eventPublisher.publishEvent(new MessageEvent(this, SendMessage.builder()
                    .chatId(chatId)
                    .text("🗑 Delete " + displayName(category) + "? No transactions use it.")
                    .replyMarkup(keyboard)
                    .build()));
            return;
        }

        List<Category> targets = Stream.concat(
                        categoryRepository.findByTelegramUserId(chatId).stream(),
                        categoryRepository.findByTelegramUserId(0L).stream())
                .filter(c -> !c.getId().equals(category.getId()))
                .limit(MAX_REASSIGN_TARGETS)
                .toList();

        if (targets.isEmpty()) {
            eventPublisher.publishEvent(new MessageEvent(this, SendMessage.builder()
                    .chatId(chatId)
                    .text("⚠️ " + usageCount + " transaction(s) use this category, and there's no other category to move them to. "
                            + "Create another category first, then try deleting this one again.")
                    .build()));
            return;
        }

        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (Category target : targets) {
            rows.add(new InlineKeyboardRow(InlineKeyboardButton.builder()
                    .text(displayName(target))
                    .callbackData("catmgmt:reassign:" + category.getId() + ":" + target.getId())
                    .build()));
        }

        eventPublisher.publishEvent(new MessageEvent(this, SendMessage.builder()
                .chatId(chatId)
                .text("🗑 " + usageCount + " transaction(s) use " + displayName(category) + ". Move them to which category before deleting?")
                .replyMarkup(InlineKeyboardMarkup.builder().keyboard(rows).build())
                .build()));
    }

    private String displayName(Category category) {
        String emoji = category.getEmoji() != null ? category.getEmoji() + " " : "";
        return emoji + category.getName();
    }
}
