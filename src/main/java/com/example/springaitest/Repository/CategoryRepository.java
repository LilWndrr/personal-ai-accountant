package com.example.springaitest.Repository;

import com.example.springaitest.domain.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CategoryRepository extends JpaRepository<Category,Long> {
    Category findByTelegramUserId(Long telegramUserId);
}
