package com.example.springaitest.api.config.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name="merchant_mappings")
@Builder
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class MerchantMapping {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "merchant_keyword", nullable = false)

    private String merchantKeyword;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;
    @Column(name = "telegram_user_id", nullable = false)
    private Long telegramUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MappingSource source;
}

