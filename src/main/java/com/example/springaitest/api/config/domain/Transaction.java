package com.example.springaitest.api.config.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
@Table(name = "transactions")
public class Transaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;


    @Column(nullable = false)
    private LocalDate date;
    @Column(nullable = false, length = 500)
    private String description;
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;
    @Column(precision = 12, scale = 2)
    private BigDecimal balance;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionType type;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;
    private String merchantName;
    @Column(length = 1000)
    private String rawText;
    @Column(name = "telegram_user_id", nullable = false)
    private Long telegramUserId;
    @Column(name = "upload_batch_id")
    private String uploadBatchId;
    @Column(name = "uploaded_at")
    private LocalDateTime uploadedAt;
}
