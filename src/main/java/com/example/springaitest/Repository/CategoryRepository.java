package com.example.springaitest.Repository;

import com.example.springaitest.domain.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CategoryRepository extends JpaRepository<Category,Long> {
   List<Category> findByTelegramUserId(Long telegramUserId);

   Optional<Category> findByNameAndTelegramUserId(String name, Long telegramUserId);

   boolean existsByNameAndTelegramUserId(String name, Long telegramUserId);
}
