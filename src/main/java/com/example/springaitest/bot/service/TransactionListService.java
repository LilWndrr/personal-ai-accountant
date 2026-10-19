package com.example.springaitest.bot.service;

import com.example.springaitest.api.config.Repository.TransactionRepository;
import com.example.springaitest.api.config.domain.Transaction;
import com.example.springaitest.api.config.domain.TransactionType;
import com.example.springaitest.bot.events.MessageEvent;
import com.example.springaitest.bot.model.UserSession;
import com.example.springaitest.bot.model.UserState;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.SignStyle;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TransactionListService {

    private static final int PAGE_SIZE = 15;
    private static final DateTimeFormatter DISPLAY_DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /** Accepts both "1.7.2026" and "01.07.2026" - users don't reliably zero-pad when typing. */
    private static final DateTimeFormatter INPUT_DATE_FORMAT = new DateTimeFormatterBuilder()
            .appendValue(ChronoField.DAY_OF_MONTH, 1, 2, SignStyle.NOT_NEGATIVE)
            .appendLiteral('.')
            .appendValue(ChronoField.MONTH_OF_YEAR, 1, 2, SignStyle.NOT_NEGATIVE)
            .appendLiteral('.')
            .appendValue(ChronoField.YEAR, 4)
            .toFormatter();

    private final TransactionRepository transactionRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final MessageTrackerService messageTrackerService;
    private final UserSessionService userSessionService;

    @Transactional(readOnly = true)
    public void handleTransactionsCallback(Long chatId, String data) {
        messageTrackerService.deleteLastMessage(chatId);

        // data format: "transactions:2026-09", "transactions:ytd", "transactions:all", or "transactions:custom"
        String[] parts = data.split(":");
        if (parts.length < 2) return;

        if (parts[1].equals("custom")) {
            promptForCustomRange(chatId);
            return;
        }

        LocalDate startDate;
        LocalDate endDate;

        switch (parts[1]) {
            case "ytd" -> {
                startDate = LocalDate.of(Year.now().getValue(), 1, 1);
                endDate = LocalDate.now();
            }
            case "all" -> {
                startDate = LocalDate.of(2000, 1, 1);
                endDate = LocalDate.now();
            }
            default -> {
                YearMonth yearMonth = YearMonth.parse(parts[1]);
                startDate = yearMonth.atDay(1);
                endDate = yearMonth.atEndOfMonth();
            }
        }

        sendPage(chatId, startDate, endDate, 0);
    }

    /** Pagination callbacks: "txlist:2026-01-01:2026-01-31:1" */
    @Transactional(readOnly = true)
    public void handlePageCallback(Long chatId, String data) {
        messageTrackerService.deleteLastMessage(chatId);

        String[] parts = data.split(":");
        if (parts.length < 4) return;

        LocalDate startDate = LocalDate.parse(parts[1]);
        LocalDate endDate = LocalDate.parse(parts[2]);
        int page = Integer.parseInt(parts[3]);

        sendPage(chatId, startDate, endDate, page);
    }

    @Transactional(readOnly = true)
    public void handleCustomRangeInput(Long chatId, String text) {
        String[] parts = text.split("(?i)\\s+to\\s+");

        if (parts.length != 2) {
            sendCustomRangeFormatError(chatId);
            return;
        }

        LocalDate startDate;
        LocalDate endDate;
        try {
            startDate = LocalDate.parse(parts[0].trim(), INPUT_DATE_FORMAT);
            endDate = LocalDate.parse(parts[1].trim(), INPUT_DATE_FORMAT);
        } catch (DateTimeParseException e) {
            sendCustomRangeFormatError(chatId);
            return;
        }

        if (startDate.isAfter(endDate)) {
            eventPublisher.publishEvent(new MessageEvent(this, SendMessage.builder()
                    .chatId(chatId)
                    .text("⚠️ The start date has to be before the end date. Try again, or /cancel.")
                    .build()));
            return;
        }

        UserSession session = userSessionService.getSession(chatId);
        session.setUserState(UserState.IDLE);
        userSessionService.saveSession(session);

        sendPage(chatId, startDate, endDate, 0);
    }

    private void promptForCustomRange(Long chatId) {
        UserSession session = userSessionService.getSession(chatId);
        session.setUserState(UserState.AWAITING_CUSTOM_RANGE_TRANSACTIONS);
        userSessionService.saveSession(session);
        eventPublisher.publishEvent(new MessageEvent(this, SendMessage.builder()
                .chatId(chatId)
                .text("🗓 Send me a date range like: `15.01.2026 to 10.02.2026`\n\nOr /cancel to go back.")
                .parseMode("Markdown")
                .build()));
    }

    private void sendCustomRangeFormatError(Long chatId) {
        eventPublisher.publishEvent(new MessageEvent(this, SendMessage.builder()
                .chatId(chatId)
                .text("⚠️ I couldn't read that. Send it like: `15.01.2026 to 10.02.2026`\n\nOr /cancel to go back.")
                .parseMode("Markdown")
                .build()));
    }

    private void sendPage(Long chatId, LocalDate startDate, LocalDate endDate, int page) {
        List<Transaction> transactions = transactionRepository.findByUserAndDateRange(chatId, startDate, endDate);
        transactions.sort(Comparator.comparing(Transaction::getDate).reversed());

        if (transactions.isEmpty()) {
            eventPublisher.publishEvent(new MessageEvent(this, SendMessage.builder()
                    .chatId(chatId)
                    .text("📭 No transactions found for that range.")
                    .build()));
            return;
        }

        int totalPages = (int) Math.ceil(transactions.size() / (double) PAGE_SIZE);
        page = Math.max(0, Math.min(page, totalPages - 1));
        int fromIndex = page * PAGE_SIZE;
        int toIndex = Math.min(fromIndex + PAGE_SIZE, transactions.size());
        List<Transaction> pageItems = transactions.subList(fromIndex, toIndex);

        StringBuilder sb = new StringBuilder();
        sb.append("📋 *Transactions* (page ").append(page + 1).append("/").append(totalPages).append(")\n\n");

        List<InlineKeyboardRow> actionRows = new ArrayList<>();
        int rowNumber = 1;
        for (Transaction t : pageItems) {
            String emoji = t.getCategory() != null && t.getCategory().getEmoji() != null
                    ? t.getCategory().getEmoji() + " " : "";
            String categoryName = t.getCategory() != null ? t.getCategory().getName() : "Uncategorized";
            String sign = t.getType() == TransactionType.EXPENSE ? "-" : "+";
            String description = t.getDescription() != null && t.getDescription().length() > 40
                    ? t.getDescription().substring(0, 40) + "…"
                    : t.getDescription();

            sb.append(String.format("%d. %s %s%s | %s%.2f ₺\n%s\n\n",
                    rowNumber,
                    t.getDate().format(DISPLAY_DATE_FORMAT),
                    emoji, categoryName,
                    sign, t.getAmount().abs(),
                    description));

            actionRows.add(new InlineKeyboardRow(InlineKeyboardButton.builder()
                    .text("⚙️ " + rowNumber)
                    .callbackData("tx:open:" + t.getId())
                    .build()));
            rowNumber++;
        }

        List<InlineKeyboardButton> navButtons = new ArrayList<>();
        if (page > 0) {
            navButtons.add(InlineKeyboardButton.builder()
                    .text("◀ Prev")
                    .callbackData("txlist:" + startDate + ":" + endDate + ":" + (page - 1))
                    .build());
        }
        if (page < totalPages - 1) {
            navButtons.add(InlineKeyboardButton.builder()
                    .text("Next ▶")
                    .callbackData("txlist:" + startDate + ":" + endDate + ":" + (page + 1))
                    .build());
        }
        if (!navButtons.isEmpty()) {
            actionRows.add(new InlineKeyboardRow(navButtons));
        }

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder().keyboard(actionRows).build();

        eventPublisher.publishEvent(new MessageEvent(this, SendMessage.builder()
                .chatId(chatId)
                .text(sb.toString())
                .parseMode("Markdown")
                .replyMarkup(keyboard)
                .build()));
    }
}
