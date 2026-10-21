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
import com.example.springaitest.api.config.service.TransactionDeduplicationService;
import com.example.springaitest.bot.events.MessageEvent;
import com.example.springaitest.bot.model.UserSession;
import com.example.springaitest.bot.model.UserState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.math.BigDecimal;
import java.text.Collator;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The category picker behind "➕ New Category" during review. Instead of only
 * asking for a name (which created near-duplicates like "Yemek" next to
 * "Yemek / Restoran"), it shows the user's most-used categories, a paged list
 * of all of them, and turns typed text into a search; a new category is only
 * created through an explicit "Create" button.
 *
 * Buttons carry category ids, never names, to stay within Telegram's 64-byte
 * callback data: pick:<tx>:<categoryId>, pickpage:<tx>:<page>, picktop:<tx>,
 * pickcreate:<tx>, pickback:<tx>, where <tx> is the transaction being reviewed.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CustomCategoryService {

    private static final int TOP_COUNT = 8;
    private static final int PAGE_SIZE = 10;
    private static final int MAX_MATCHES = 8;
    private static final String PICK_PROMPT = "🏷 Pick a category for this transaction, or type a name to search or create one:";

    private final CategoryRepository categoryRepository;
    private final UserSessionService userSessionService;
    private final TransactionRepository transactionRepository;
    private final TransactionDeduplicationService deduplicationService;
    private final MerchantMappingRepository merchantMappingRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final MessageTrackerService messageTrackerService;

    /** First screen, shown when the user taps "➕ New Category" on a review item. */
    public void showPicker(Long chatId, UserSession session) {
        session.setUserState(UserState.CREATING_CATEGORY);
        session.setPendingCategoryName(null);
        userSessionService.saveSession(session);

        send(chatId, PICK_PROMPT, topScreen(chatId, currentTransactionIndex(session), availableCategories(chatId)));
    }

    /**
     * Text typed while picking. A name that already exists (see CategoryNames.key)
     * is used directly; anything else is a search, answered with the matching
     * categories and an explicit "Create" button.
     */
    public void handleCategoryCreating(Long chatId, String typed) {
        UserSession session = userSessionService.getSession(chatId);
        if (!isPicking(session)) return;

        String name = CategoryNames.clean(typed);
        String key = CategoryNames.key(name);
        List<Category> all = availableCategories(chatId);
        messageTrackerService.deleteLastMessage(chatId);

        Optional<Category> existing = all.stream().filter(c -> CategoryNames.key(c.getName()).equals(key)).findFirst();
        if (existing.isPresent()) {
            assign(chatId, session, existing.get(), MappingSource.USER_CONFIRMED);
            return;
        }

        int tx = currentTransactionIndex(session);
        List<Category> matches = all.stream()
                .filter(c -> CategoryNames.key(c.getName()).contains(key))
                .sorted(Comparator.<Category, Boolean>comparing(c -> !CategoryNames.key(c.getName()).startsWith(key))
                        .thenComparing(Category::getName, collator()))
                .limit(MAX_MATCHES)
                .toList();
        boolean creatable = name.length() >= 2 && name.length() <= 20;

        session.setPendingCategoryName(creatable ? name : null);
        userSessionService.saveSession(session);

        List<InlineKeyboardRow> rows = new ArrayList<>(categoryRows(tx, matches));
        if (creatable) {
            rows.add(row(button("➕ Create \"" + name + "\" as a new category", "pickcreate:" + tx)));
        }
        rows.add(row(button("📋 All categories (" + all.size() + ")", "pickpage:" + tx + ":0")));
        rows.add(backRow(tx));

        String text = matches.isEmpty()
                ? "No category matches \"" + name + "\"."
                : "Categories matching \"" + name + "\":";
        if (!creatable) {
            text += "\n(New category names must be 2–20 characters.)";
        }
        send(chatId, text, markup(rows));
    }

    /** Picker buttons; messageId is the picker message, edited in place when paging. */
    public void handleCallback(Long chatId, String data, Integer messageId) {
        UserSession session = userSessionService.getSession(chatId);
        String[] parts = data.split(":");
        // A tap on a picker for a transaction that is no longer the current one
        // (double tap, redelivered update) is ignored.
        if (parts.length < 2 || !isPicking(session)
                || !parts[1].equals(String.valueOf(currentTransactionIndex(session)))) return;
        int tx = currentTransactionIndex(session);

        switch (parts[0]) {
            case "pick" -> {
                if (parts.length < 3) return;
                Optional<Category> category = availableCategories(chatId).stream()
                        .filter(c -> c.getId().toString().equals(parts[2]))
                        .findFirst();
                if (category.isEmpty()) return;
                messageTrackerService.deleteLastMessage(chatId);
                assign(chatId, session, category.get(), MappingSource.USER_CONFIRMED);
            }
            case "pickpage" -> {
                int page = parts.length < 3 ? 0 : parsePage(parts[2]);
                List<Category> all = availableCategories(chatId);
                int pageCount = Math.max(1, (all.size() + PAGE_SIZE - 1) / PAGE_SIZE);
                page = Math.min(Math.max(page, 0), pageCount - 1);
                edit(chatId, messageId, "📋 All categories — page " + (page + 1) + "/" + pageCount
                        + "\nOr type a name to search or create one.", pageScreen(tx, all, page, pageCount));
            }
            case "picktop" -> edit(chatId, messageId, PICK_PROMPT, topScreen(chatId, tx, availableCategories(chatId)));
            case "pickcreate" -> {
                String name = session.getPendingCategoryName();
                if (name == null) return;
                Category category = categoryRepository.findOrCreateCategory(name, chatId, null, true);
                session.setPendingCategoryName(null);
                messageTrackerService.deleteLastMessage(chatId);
                assign(chatId, session, category, MappingSource.USER_CREATED);
            }
            case "pickback" -> {
                session.setUserState(UserState.REVIEWING);
                session.setPendingCategoryName(null);
                userSessionService.saveSession(session);
                messageTrackerService.deleteLastMessage(chatId);
                eventPublisher.publishEvent(new MessageEvent(this, ReviewMessages.build(chatId, session, null)));
            }
            default -> log.warn("Unknown category picker action in callback: {}", data);
        }
    }

    /** Saves the transaction under review with this category and moves on to the next one. */
    private void assign(Long chatId, UserSession session, Category category, MappingSource source) {
        CategorizedTransaction review = session.getPendingReview().get(session.getCurrentReviewIndex());
        ParsedTransaction original = session.getOriginalTransactions().get(review.transactionIndex());
        Transaction entity = Transaction.builder()
                .date(LocalDate.parse(original.date(), DateTimeFormatter.ofPattern("dd[.][/][-]MM[.][/][-]yyyy")))
                .balance(BigDecimal.valueOf(original.balance()))
                .amount(BigDecimal.valueOf(original.amount()))
                .type(original.amount() < 0 ? TransactionType.EXPENSE : TransactionType.INCOME)
                .description(original.description())
                .merchantName(original.merchantName())
                .bankName(session.getBankName())
                .category(category)
                .uploadBatchId(session.getUploadBatch())
                .uploadedAt(LocalDateTime.now())
                .telegramUserId(chatId)
                .dedupKey(deduplicationService.keyOf(original))
                .build();
        transactionRepository.saveIfNew(entity);

        // Learn: save merchant mapping so this merchant auto-categorizes next time
        saveMerchantMappingIfNew(original.merchantName(), category, chatId, source);

        session.setPendingCategoryName(null);
        session.setCurrentReviewIndex(session.getCurrentReviewIndex() + 1);
        session.setUserState(UserState.REVIEWING);

        if (session.getCurrentReviewIndex() < session.getPendingReview().size()) {
            eventPublisher.publishEvent(new MessageEvent(this, ReviewMessages.build(chatId, session, null)));
        } else {
            session.setUserState(UserState.IDLE);
            session.setPendingReview(null);
            eventPublisher.publishEvent(new MessageEvent(this, SendMessage.builder()
                    .chatId(chatId).text("🎉 All done! Review complete.").build()));
        }

        userSessionService.saveSession(session);
    }

    // ---- screens ----

    /** The user's most-used categories (topped up alphabetically), plus "All" when there are more. */
    private InlineKeyboardMarkup topScreen(Long chatId, int tx, List<Category> all) {
        Map<Long, Long> usage = new HashMap<>();
        for (Object[] row : transactionRepository.countByCategoryForUser(chatId)) {
            usage.put((Long) row[0], (Long) row[1]);
        }
        List<Category> top = all.stream()
                .sorted(Comparator.<Category, Long>comparing(c -> usage.getOrDefault(c.getId(), 0L)).reversed()
                        .thenComparing(Category::getName, collator()))
                .limit(TOP_COUNT)
                .toList();

        List<InlineKeyboardRow> rows = new ArrayList<>(categoryRows(tx, top));
        if (all.size() > TOP_COUNT) {
            rows.add(row(button("📋 All categories (" + all.size() + ") ▶️", "pickpage:" + tx + ":0")));
        }
        rows.add(backRow(tx));
        return markup(rows);
    }

    private InlineKeyboardMarkup pageScreen(int tx, List<Category> all, int page, int pageCount) {
        List<Category> sorted = all.stream().sorted(Comparator.comparing(Category::getName, collator())).toList();
        List<Category> onPage = sorted.subList(page * PAGE_SIZE, Math.min(sorted.size(), (page + 1) * PAGE_SIZE));

        List<InlineKeyboardRow> rows = new ArrayList<>(categoryRows(tx, onPage));
        InlineKeyboardRow nav = new InlineKeyboardRow();
        if (page > 0) nav.add(button("◀️", "pickpage:" + tx + ":" + (page - 1)));
        nav.add(button("⭐ Most used", "picktop:" + tx));
        if (page < pageCount - 1) nav.add(button("▶️", "pickpage:" + tx + ":" + (page + 1)));
        rows.add(nav);
        rows.add(backRow(tx));
        return markup(rows);
    }

    // Two categories per row: short enough for phone screens.
    private List<InlineKeyboardRow> categoryRows(int tx, List<Category> categories) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (int i = 0; i < categories.size(); i += 2) {
            InlineKeyboardRow row = new InlineKeyboardRow();
            for (Category c : categories.subList(i, Math.min(i + 2, categories.size()))) {
                row.add(button(displayName(c), "pick:" + tx + ":" + c.getId()));
            }
            rows.add(row);
        }
        return rows;
    }

    private InlineKeyboardRow backRow(int tx) {
        return row(button("⬅️ Back to review", "pickback:" + tx));
    }

    // ---- helpers ----

    /** The user's own categories plus the shared defaults; an own category hides a default with the same name. */
    private List<Category> availableCategories(Long chatId) {
        Map<String, Category> byName = new LinkedHashMap<>();
        for (Category c : categoryRepository.findByTelegramUserId(chatId)) byName.putIfAbsent(CategoryNames.key(c.getName()), c);
        for (Category c : categoryRepository.findByTelegramUserId(0L)) byName.putIfAbsent(CategoryNames.key(c.getName()), c);
        return new ArrayList<>(byName.values());
    }

    private boolean isPicking(UserSession session) {
        return session.getUserState() == UserState.CREATING_CATEGORY
                && session.getPendingReview() != null
                && session.getCurrentReviewIndex() >= 0
                && session.getCurrentReviewIndex() < session.getPendingReview().size();
    }

    private int currentTransactionIndex(UserSession session) {
        return session.getPendingReview().get(session.getCurrentReviewIndex()).transactionIndex();
    }

    private static int parsePage(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // Turkish alphabetical order (Ç after C, Ş after S, ...). Collators aren't thread-safe, so one per use.
    private static Collator collator() {
        return Collator.getInstance(Locale.forLanguageTag("tr"));
    }

    private static String displayName(Category category) {
        return (category.getEmoji() != null ? category.getEmoji() + " " : "") + category.getName();
    }

    private static InlineKeyboardButton button(String text, String data) {
        return InlineKeyboardButton.builder().text(text).callbackData(data).build();
    }

    private static InlineKeyboardRow row(InlineKeyboardButton button) {
        return new InlineKeyboardRow(button);
    }

    private static InlineKeyboardMarkup markup(List<InlineKeyboardRow> rows) {
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private void send(Long chatId, String text, InlineKeyboardMarkup keyboard) {
        eventPublisher.publishEvent(new MessageEvent(this, SendMessage.builder()
                .chatId(chatId).text(text).replyMarkup(keyboard).build()));
    }

    private void edit(Long chatId, Integer messageId, String text, InlineKeyboardMarkup keyboard) {
        if (messageId == null) {
            send(chatId, text, keyboard);
            return;
        }
        eventPublisher.publishEvent(new MessageEvent(this, EditMessageText.builder()
                .chatId(chatId).messageId(messageId).text(text).replyMarkup(keyboard).build()));
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
