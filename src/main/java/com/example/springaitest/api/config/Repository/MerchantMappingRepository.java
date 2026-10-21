package com.example.springaitest.api.config.Repository;

import com.example.springaitest.api.config.domain.Category;
import com.example.springaitest.api.config.domain.MerchantMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MerchantMappingRepository extends JpaRepository<MerchantMapping,Long> {

    /**
     * JOIN FETCH so `category` (a LAZY @ManyToOne) is already loaded by the
     * time this returns - callers like CategorizationService.categorize()
     * read mapping.getCategory().getName() outside of any wrapping
     * transaction, where a lazy proxy would throw LazyInitializationException.
     */
    @Query("SELECT m FROM MerchantMapping m JOIN FETCH m.category WHERE m.telegramUserId = :telegramUserId")
    List<MerchantMapping> findByTelegramUserId(@Param("telegramUserId") Long telegramUserId);

    List<MerchantMapping> findByTelegramUserIdOrTelegramUserId(Long userId, Long systemId);

    boolean existsByMerchantKeywordAndTelegramUserId(String merchantKeyword, Long telegramUserId);

    /** Points a category's learned merchant rules at another category (merge / move-then-delete). */
    @Modifying
    @Query("UPDATE MerchantMapping m SET m.category = :newCategory WHERE m.category.id = :oldCategoryId")
    int reassignCategory(@Param("oldCategoryId") Long oldCategoryId, @Param("newCategory") Category newCategory);

    /** Removes a category's learned merchant rules; they must go before the category itself can be deleted. */
    @Modifying
    @Query("DELETE FROM MerchantMapping m WHERE m.category.id = :categoryId")
    int deleteByCategoryId(@Param("categoryId") Long categoryId);
}
