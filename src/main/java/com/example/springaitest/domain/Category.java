package com.example.springaitest.domain;

import jakarta.persistence.*;
import lombok.*;
import org.glassfish.jersey.client.ClientAsyncExecutor;

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
    private String name;
    private String emoji;
    @Column(name = "telegram_user_id", nullable = false)
    private Long telegramUserId;
    @Column(nullable = false)
    private boolean custom;
}
