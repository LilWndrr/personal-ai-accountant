package com.example.springaitest.domain;

import jakarta.persistence.*;

@Entity
@Table(name="merchant_mappings")
public class MerchantMapping {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String merchantKeyword;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;
    private Long telegramUserId;

    @Enumerated(EnumType.STRING)
    private MappingSource source;
}

