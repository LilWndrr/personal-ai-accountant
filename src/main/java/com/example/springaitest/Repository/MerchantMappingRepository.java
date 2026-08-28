package com.example.springaitest.Repository;

import com.example.springaitest.domain.MerchantMapping;
import org.hibernate.boot.models.JpaAnnotations;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MerchantMappingRepository extends JpaRepository<MerchantMapping,Long> {
    MerchantMapping findByTelegramUserIdAndMerchantKeyword(Long telegramUserId, String merchantKeyword);
}
