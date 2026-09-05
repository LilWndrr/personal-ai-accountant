package com.example.springaitest.api.config.Repository;

import com.example.springaitest.api.config.domain.MerchantMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MerchantMappingRepository extends JpaRepository<MerchantMapping,Long> {
    List<MerchantMapping> findByTelegramUserId(Long telegramUserId);

    List<MerchantMapping> findByTelegramUserIdOrTelegramUserId(Long userId, Long systemId);

    boolean existsByMerchantKeywordAndTelegramUserId(String merchantKeyword, Long telegramUserId);
}
