package com.example.springaitest.bot.service;

import com.example.springaitest.api.config.Repository.TransactionRepository;
import com.example.springaitest.api.config.domain.Transaction;
import com.example.springaitest.api.config.domain.TransactionType;
import com.example.springaitest.bot.events.MessageEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReportService {

    private final TransactionRepository transactionRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final MessageTrackerService messageTrackerService;

    @Transactional(readOnly = true)
    public void handleReportCallback(Long chatId, String data) {

        messageTrackerService.deleteLastMessage(chatId);

        // data format: "report:2026-09"
        String[] parts = data.split(":");
        if (parts.length < 2) return;

        YearMonth yearMonth = YearMonth.parse(parts[1]);

        List<Transaction> expenses = transactionRepository.findByTelegramUserIdAndTypeAndDateBetween(
                chatId,
                TransactionType.EXPENSE,
                yearMonth.atDay(1),
                yearMonth.atEndOfMonth()
        );

        if (expenses.isEmpty()) {
            String monthName = yearMonth.getMonth().getDisplayName(TextStyle.FULL, Locale.getDefault())
                    + " " + yearMonth.getYear();
            eventPublisher.publishEvent(new MessageEvent(this, SendMessage.builder()
                    .chatId(chatId)
                    .text("📭 No expenses found for " + monthName + ".")
                    .build()));
            return;
        }

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

        BigDecimal totalExpense = categoryTotals.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        String monthName = yearMonth.getMonth().getDisplayName(TextStyle.FULL, Locale.getDefault())
                + " " + yearMonth.getYear();

        StringBuilder sb = new StringBuilder();
        sb.append("📊 *Expense Report — ").append(monthName).append("*\n\n");

        // Sort by amount descending
        categoryTotals.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .forEach(entry -> {
                    BigDecimal percentage = entry.getValue()
                            .multiply(BigDecimal.valueOf(100))
                            .divide(totalExpense, 1, java.math.RoundingMode.HALF_UP);
                    sb.append(String.format("%s: *%.2f* (%.1f%%)\n",
                            entry.getKey(), entry.getValue(), percentage));
                });

        sb.append("\n💰 *Total: ").append(String.format("%.2f", totalExpense)).append("*");

        eventPublisher.publishEvent(new MessageEvent(this, SendMessage.builder()
                .chatId(chatId)
                .text(sb.toString())
                .parseMode("Markdown")
                .build()));
    }
}
