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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CallbackHandlerService {

    private final ApplicationEventPublisher eventPublisher;
    private final UserSessionService sessionService;
    private final CategoryRepository categoryRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionDeduplicationService deduplicationService;
    private final MerchantMappingRepository merchantMappingRepository;
    private final MessageTrackerService messageTrackerService;
    private final CustomCategoryService customCategoryService;

    public void handleCallback(Long chatId, String data){
        UserSession session = sessionService.getSession(chatId);
      String[] parts =  data.split(":");

        // A tap on a review item that is no longer current (double tap, or Telegram
        // redelivering the update after a restart) is ignored before anything happens:
        // otherwise it would delete the live review message and act on the wrong item.
        if (session.getUserState() == UserState.REVIEWING && !isCurrentReviewItem(session, parts)) return;

        messageTrackerService.deleteLastMessage(chatId);

        if (session.getUserState() != UserState.REVIEWING) return;

      if(parts[0].equals("new")){
          customCategoryService.showPicker(chatId, session);
          return;
      }else if(parts[0].equals("cat") ){
        int transactionIndex = Integer.parseInt(parts[1]);
        String categoryName = parts[2];
          ParsedTransaction original = session.getOriginalTransactions().get(transactionIndex);
          Category category = categoryRepository.findByNameAndTelegramUserId(categoryName, chatId)
                  .or(() -> categoryRepository.findByNameAndTelegramUserId(categoryName, 0L))
                  .orElse(null);
          // 3. Save to database
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

          // Learn: save merchant mapping so this merchant auto-categorizes next time
          saveMerchantMappingIfNew(original.merchantName(), category, chatId, MappingSource.USER_CONFIRMED);
          transactionRepository.saveIfNew(entity);
          
          session.setCurrentReviewIndex(session.getCurrentReviewIndex() + 1);

      } else if (parts[0].equals("dup")) {
          // The user confirmed this is a transaction they already have: don't save it.
          eventPublisher.publishEvent(new MessageEvent(this, SendMessage.builder()
                  .chatId(chatId)
                  .text("🚫 Not saved — it's already in your records.")
                  .build()));
          session.setCurrentReviewIndex(session.getCurrentReviewIndex() + 1);

      } else if( parts[0].equals("skip")){
          int transactionIndex = Integer.parseInt(parts[1]);
          ParsedTransaction original = session.getOriginalTransactions().get(transactionIndex);
          Category category = categoryRepository.findByTelegramUserIdAndFallbackTrue(0L).orElse(null);

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

          String filedUnder = category != null
                  ? (category.getEmoji() != null ? category.getEmoji() + " " : "") + category.getName()
                  : "no category";
          eventPublisher.publishEvent(new MessageEvent(this, SendMessage.builder()
                  .chatId(chatId)
                  .text("⏭️ Skipped — filed under " + filedUnder + ".")
                  .build()));

          session.setCurrentReviewIndex(session.getCurrentReviewIndex() + 1);
      }

        if (session.getCurrentReviewIndex() < session.getPendingReview().size()){

            eventPublisher.publishEvent(new MessageEvent(this, buildReviewMessage(chatId, session)));
        } else {
            session.setUserState(UserState.IDLE);
            session.setPendingReview(null);
            eventPublisher.publishEvent(new MessageEvent(this, SendMessage.builder()
                    .chatId(chatId).text("🎉 All done! Review complete.").build()));
        }

        sessionService.saveSession(session);
    }

    // Callback data is "<action>:<transactionIndex>[:...]"; it is current only if
    // that index is the transaction the review is waiting on right now.
    private boolean isCurrentReviewItem(UserSession session, String[] parts) {
        List<CategorizedTransaction> pending = session.getPendingReview();
        int current = session.getCurrentReviewIndex();
        if (parts.length < 2 || pending == null || current < 0 || current >= pending.size()) return false;
        return parts[1].equals(String.valueOf(pending.get(current).transactionIndex()));
    }

    private SendMessage buildReviewMessage(Long chatId, UserSession session) {
        return ReviewMessages.build(chatId, session, null);
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
