package com.example.springaitest.api.config.Repository;

import com.example.springaitest.api.config.domain.Category;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CategoryRepository extends JpaRepository<Category,Long> {
   List<Category> findByTelegramUserId(Long telegramUserId);

   Optional<Category> findByNameAndTelegramUserId(String name, Long telegramUserId);

   boolean existsByNameAndTelegramUserId(String name, Long telegramUserId);

   Optional<Category> findByTelegramUserIdAndFallbackTrue(Long telegramUserId);

   /**
    * Finds an existing category (user-scoped, falling back to the shared system
    * default) or creates a new user-scoped one. Not atomic by itself, so a
    * concurrent call for the same (name, telegramUserId) can race on the insert;
    * the unique constraint on that pair turns the loser into a
    * DataIntegrityViolationException instead of a duplicate row, and that loser
    * just re-reads the row the winner committed.
    */
   default Category findOrCreateCategory(String name, Long telegramUserId, String emoji, boolean custom) {
      Optional<Category> existing = findByNameAndTelegramUserId(name, telegramUserId)
              .or(() -> findByNameAndTelegramUserId(name, 0L));
      if (existing.isPresent()) {
         return existing.get();
      }

      try {
         return save(Category.builder()
                 .name(name)
                 .emoji(emoji)
                 .custom(custom)
                 .telegramUserId(telegramUserId)
                 .build());
      } catch (DataIntegrityViolationException e) {
         return findByNameAndTelegramUserId(name, telegramUserId)
                 .or(() -> findByNameAndTelegramUserId(name, 0L))
                 .orElseThrow(() -> e);
      }
   }
}
