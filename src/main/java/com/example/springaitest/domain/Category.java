package com.example.springaitest.domain;

import jakarta.persistence.*;
import lombok.*;
import org.glassfish.jersey.client.ClientAsyncExecutor;

@Entity
@Table(name = "categories", uniqueConstraints = @UniqueConstraint(columnNames = {"name", "telegramUserId"}))
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
public class Category {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    private String emoji;
    private Long telegramUserId;
    private boolean custom;
}
