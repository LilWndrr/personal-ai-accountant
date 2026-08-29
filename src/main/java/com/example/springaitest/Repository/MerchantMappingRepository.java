package com.example.springaitest.Repository;

import com.example.springaitest.domain.MerchantMapping;
import org.hibernate.boot.models.JpaAnnotations;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MerchantMappingRepository extends JpaRepository<MerchantMapping,Long> {
    List<MerchantMapping> findByTelegramUserId(Long telegramUserId);

    List<MerchantMapping> findByTelegramUserIdOrTelegramUserId(Long userId, Long systemId);


}
