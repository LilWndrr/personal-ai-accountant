package com.example.springaitest.bot.service;

import com.example.springaitest.api.config.domain.Transaction;
import com.example.springaitest.api.config.dto.CategorizedTransaction;
import com.example.springaitest.api.config.dto.ParsedTransaction;
import com.example.springaitest.bot.model.UserSession;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.time.format.DateTimeFormatter;

/**
 * Builds the "review this transaction" message shared by the upload flow and
 * the review callbacks, so the text, warnings and buttons stay identical.
 */
final class ReviewMessages {

    // Reasoning prefix marking a transaction that probably duplicates a saved one;
    // such items get a "don't save" button.
    static final String DUPLICATE_WARNING = "⚠️ Possible duplicate";

    private ReviewMessages() {
    }

    /** Puts the duplicate warning in front of the item's existing reasoning. */
    static CategorizedTransaction flagLikelyDuplicate(CategorizedTransaction ct, Transaction saved) {
        String description = saved.getDescription() == null ? "" : saved.getDescription().trim();
        if (description.length() > 40) description = description.substring(0, 40) + "…";
        String warning = String.format("%s — you already have: %s · %s · %.2f ₺. Tap 🚫 if it's the same one.",
                DUPLICATE_WARNING,
                saved.getDate().format(DateTimeFormatter.ofPattern("dd.MM.yyyy")),
                description,
                saved.getAmount());
        String rest = ct.reasoning() == null || ct.reasoning().isBlank() ? "" : "\n" + ct.reasoning();
        return new CategorizedTransaction(ct.transactionIndex(), ct.suggestedCategory(), ct.suggestedEmoji(),
                ct.confidence(), ct.merchantName(), warning + rest);
    }

    static boolean isLikelyDuplicate(CategorizedTransaction review) {
        return review.reasoning() != null && review.reasoning().startsWith(DUPLICATE_WARNING);
    }

    /**
     * @param footer extra line appended to the text, or null for none
     */
    static SendMessage build(Long chatId, UserSession session, String footer) {
        CategorizedTransaction review = session.getPendingReview().get(session.getCurrentReviewIndex());
        ParsedTransaction original = session.getOriginalTransactions().get(review.transactionIndex());
        String warning = review.reasoning() != null && review.reasoning().startsWith("⚠️")
                ? "\n" + review.reasoning() + "\n"
                : "";
        String text = String.format("""
            📍 Transaction %d/%d

            📅 Date: %s
            📝 %s
            💰 Amount: %.2f ₺
            %s
            🤖 AI suggests: %s
            """,
                session.getCurrentReviewIndex() + 1,
                session.getPendingReview().size(),
                original.date(),
                original.description(),
                original.amount(),
                warning,
                review.suggestedCategory()
        );
        if (footer != null) {
            text += "\n" + footer;
        }

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

        InlineKeyboardMarkup.InlineKeyboardMarkupBuilder<?, ?> keyboard = InlineKeyboardMarkup.builder();
        if (isLikelyDuplicate(review)) {
            keyboard.keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder()
                    .text("🚫 Already have it — don't save")
                    .callbackData("dup:" + review.transactionIndex())
                    .build()));
        }
        keyboard.keyboardRow(new InlineKeyboardRow(btn1))
                .keyboardRow(new InlineKeyboardRow(btnSkip))
                .keyboardRow(new InlineKeyboardRow(btnNew));

        return SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .replyMarkup(keyboard.build())
                .build();
    }
}
