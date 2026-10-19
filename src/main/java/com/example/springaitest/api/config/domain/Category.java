package com.example.springaitest.api.config.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.Size;
import lombok.*;

@Entity
@Table(name = "categories", uniqueConstraints = @UniqueConstraint(columnNames = {"name", "telegram_user_id"}))
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
public class Category {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    @Size(min=2,max=20, message = "Category name should be in range 2 to 10")
    private String name;
    private String emoji;
    @Column(name = "telegram_user_id", nullable = false)
    private Long telegramUserId;
    @Column(nullable = false)
    private boolean custom;
    /**
     * Marks the one system category transactions fall into when a user taps
     * "Skip" during review. Looked up by this flag, never by name, so it
     * works no matter what language/text the category is displayed in.
     */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean fallback;
}
