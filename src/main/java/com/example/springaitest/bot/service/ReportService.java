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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReportService {

    private static final DateTimeFormatter CUSTOM_RANGE_DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final TransactionRepository transactionRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final MessageTrackerService messageTrackerService;
    private final UserSessionService userSessionService;

    @Transactional(readOnly = true)
    public void handleReportCallback(Long chatId, String data) {

        messageTrackerService.deleteLastMessage(chatId);

        // data format: "report:2026-09", "report:ytd", "report:all", or "report:custom"
        String[] parts = data.split(":");
        if (parts.length < 2) return;

        if (parts[1].equals("custom")) {
            UserSession session = userSessionService.getSession(chatId);
            session.setUserState(UserState.AWAITING_CUSTOM_RANGE);
            userSessionService.saveSession(session);
            eventPublisher.publishEvent(new MessageEvent(this, SendMessage.builder()
                    .chatId(chatId)
                    .text("🗓 Send me a date range like: `15.01.2026 to 10.02.2026`\n\nOr /cancel to go back.")
                    .parseMode("Markdown")
                    .build()));
            return;
        }

        LocalDate startDate;
        LocalDate endDate;
        String rangeLabel;

        switch (parts[1]) {
            case "ytd" -> {
                int year = Year.now().getValue();
                startDate = LocalDate.of(year, 1, 1);
                endDate = LocalDate.now();
                rangeLabel = "Year to Date " + year;
            }
            case "all" -> {
                startDate = LocalDate.of(2000, 1, 1);
                endDate = LocalDate.now();
                rangeLabel = "All Time";
            }
            default -> {
                YearMonth yearMonth = YearMonth.parse(parts[1]);
                startDate = yearMonth.atDay(1);
                endDate = yearMonth.atEndOfMonth();
                rangeLabel = yearMonth.getMonth().getDisplayName(TextStyle.FULL, Locale.getDefault())
                        + " " + yearMonth.getYear();
            }
        }

        buildAndSendReport(chatId, startDate, endDate, rangeLabel);
    }

    /**
     * Handles free-text input while the session is AWAITING_CUSTOM_RANGE.
     * On a valid range, resets the session back to IDLE and sends the report.
     * On invalid input, replies with the expected format and leaves the state
     * unchanged so the user can just try again (or /cancel out).
     */
    @Transactional(readOnly = true)
    public void handleCustomRangeInput(Long chatId, String text) {
        String[] parts = text.split("(?i)\\s+to\\s+");
        LocalDate startDate;
        LocalDate endDate;

        if (parts.length != 2) {
            sendCustomRangeFormatError(chatId);
            return;
        }

        try {
            startDate = LocalDate.parse(parts[0].trim(), CUSTOM_RANGE_DATE_FORMAT);
            endDate = LocalDate.parse(parts[1].trim(), CUSTOM_RANGE_DATE_FORMAT);
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

        String rangeLabel = startDate.format(CUSTOM_RANGE_DATE_FORMAT) + " – " + endDate.format(CUSTOM_RANGE_DATE_FORMAT);
        buildAndSendReport(chatId, startDate, endDate, rangeLabel);
    }

    private void sendCustomRangeFormatError(Long chatId) {
        eventPublisher.publishEvent(new MessageEvent(this, SendMessage.builder()
                .chatId(chatId)
                .text("⚠️ I couldn't read that. Send it like: `15.01.2026 to 10.02.2026`\n\nOr /cancel to go back.")
                .parseMode("Markdown")
                .build()));
    }

    private void buildAndSendReport(Long chatId, LocalDate startDate, LocalDate endDate, String rangeLabel) {

        List<Transaction> expenses = transactionRepository.findByTelegramUserIdAndTypeAndDateBetween(
                chatId, TransactionType.EXPENSE, startDate, endDate
        );
        List<Transaction> income = transactionRepository.findByTelegramUserIdAndTypeAndDateBetween(
                chatId, TransactionType.INCOME, startDate, endDate
        );

        if (expenses.isEmpty() && income.isEmpty()) {
            eventPublisher.publishEvent(new MessageEvent(this, SendMessage.builder()
                    .chatId(chatId)
                    .text("📭 No transactions found for " + rangeLabel + ".")
                    .build()));
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("📊 *Report — ").append(rangeLabel).append("*\n\n");

        BigDecimal totalExpense = BigDecimal.ZERO;
        if (!expenses.isEmpty()) {
            // Group by category name, sum amounts (amounts are negative for expenses, use abs)
            Map<String, BigDecimal> categoryTotals = expenses.stream()
                    .collect(Collectors.groupingBy(
                            t -> {
                                if (t.getCategory() != null) {
                                    String emoji = t.getCategory().getEmoji();
                                    String name = t.getCategory().getName();
                                    return (emoji != null ? emoji + " " : "") + name;
                                }
                                return "❓ Uncategorized";
                            },
                            LinkedHashMap::new,
                            Collectors.reducing(BigDecimal.ZERO, t -> t.getAmount().abs(), BigDecimal::add)
                    ));

            totalExpense = categoryTotals.values().stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            sb.append("💸 *Expenses*\n");
            // Sort by amount descending
            BigDecimal totalExpenseForPercentage = totalExpense;
            categoryTotals.entrySet().stream()
                    .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                    .forEach(entry -> {
                        BigDecimal percentage = entry.getValue()
                                .multiply(BigDecimal.valueOf(100))
                                .divide(totalExpenseForPercentage, 1, java.math.RoundingMode.HALF_UP);
                        sb.append(String.format("%s: *%.2f* (%.1f%%)\n",
                                entry.getKey(), entry.getValue(), percentage));
                    });
            sb.append("\n");
        }

        BigDecimal totalIncome = income.stream()
                .map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (!income.isEmpty()) {
            sb.append("💵 *Income:* ").append(String.format("%.2f", totalIncome)).append("\n\n");
        }

        BigDecimal net = totalIncome.subtract(totalExpense);
        String netEmoji = net.signum() < 0 ? "🔴" : "🟢";
        sb.append("Total Income: *").append(String.format("%.2f", totalIncome)).append("*\n");
        sb.append("Total Expenses: *").append(String.format("%.2f", totalExpense)).append("*\n");
        sb.append(netEmoji).append(" *Net: ").append(String.format("%.2f", net)).append("*");

        eventPublisher.publishEvent(new MessageEvent(this, SendMessage.builder()
                .chatId(chatId)
                .text(sb.toString())
                .parseMode("Markdown")
                .build()));
    }
}
