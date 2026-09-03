package com.example.springaitest.api.config;

import com.example.springaitest.api.config.domain.Category;
import com.example.springaitest.api.config.domain.MappingSource;
import com.example.springaitest.api.config.domain.MerchantMapping;
import com.example.springaitest.api.config.Repository.CategoryRepository;
import com.example.springaitest.api.config.Repository.MerchantMappingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataSeeder implements CommandLineRunner {

    private final CategoryRepository categoryRepo;
    private final MerchantMappingRepository merchantMappingRepo;

    private static final Long SYSTEM_USER_ID = 0L;

    // Category name → emoji
    private static final Map<String, String> DEFAULT_CATEGORIES = Map.ofEntries(
            Map.entry("Market / Bakkal",    "🛒"),
            Map.entry("Kira",               "🏠"),
            Map.entry("Faturalar",          "💡"),
            Map.entry("Ulaşım",            "🚗"),
            Map.entry("Sağlık",            "🏥"),
            Map.entry("Yemek / Restoran",   "🍽️"),
            Map.entry("Giyim",             "👕"),
            Map.entry("Eğlence",           "🎮"),
            Map.entry("Eğitim",            "📚"),
            Map.entry("Kişisel Bakım",     "💈"),
            Map.entry("Online Alışveriş",   "📦"),
            Map.entry("Abonelikler",        "📱"),
            Map.entry("Havale / EFT",       "💸"),
            Map.entry("Maaş",              "💰"),
            Map.entry("Diğer",             "📌")
    );

    // Merchant keyword → category name
    private static final Map<String, String> DEFAULT_MERCHANTS = Map.ofEntries(
            // Market / Bakkal
            Map.entry("A101",           "Market / Bakkal"),
            Map.entry("BIM",            "Market / Bakkal"),
            Map.entry("SOK",            "Market / Bakkal"),
            Map.entry("MIGROS",         "Market / Bakkal"),
            Map.entry("CARREFOUR",      "Market / Bakkal"),
            Map.entry("MACRO",          "Market / Bakkal"),
            Map.entry("FILE",           "Market / Bakkal"),
            // Giyim
            Map.entry("LCWAIKIKI",      "Giyim"),
            Map.entry("LC WAIKIKI",     "Giyim"),
            Map.entry("DEFACTO",        "Giyim"),
            Map.entry("KOTON",          "Giyim"),
            Map.entry("MAVI",           "Giyim"),
            Map.entry("ZARA",           "Giyim"),
            Map.entry("BOYNER",         "Giyim"),
            // Yemek / Restoran
            Map.entry("YEMEKSEPET",     "Yemek / Restoran"),
            Map.entry("GETIR",          "Yemek / Restoran"),
            Map.entry("BURGER KING",    "Yemek / Restoran"),
            Map.entry("MCDONALDS",      "Yemek / Restoran"),
            Map.entry("STARBUCKS",      "Yemek / Restoran"),
            Map.entry("DOMINOS",        "Yemek / Restoran"),
            Map.entry("TAHA EKMEKCILIK","Yemek / Restoran"),
            Map.entry("TEKBIR GIDA",    "Yemek / Restoran"),
            // Online Alışveriş
            Map.entry("TRENDYOL",       "Online Alışveriş"),
            Map.entry("HEPSIBURADA",    "Online Alışveriş"),
            Map.entry("HEPSIPAY",       "Online Alışveriş"),
            Map.entry("AMAZON",         "Online Alışveriş"),
            Map.entry("N11",            "Online Alışveriş"),
            // Faturalar
            Map.entry("VODAFONE",       "Faturalar"),
            Map.entry("VODAFONEPAY",    "Faturalar"),
            Map.entry("TURKCELL",       "Faturalar"),
            Map.entry("TURK TELEKOM",   "Faturalar"),
            // Ulaşım
            Map.entry("OPET",           "Ulaşım"),
            Map.entry("SHELL",          "Ulaşım"),
            Map.entry("BP",             "Ulaşım"),
            Map.entry("UBER",           "Ulaşım"),
            // Abonelikler
            Map.entry("NETFLIX",        "Abonelikler"),
            Map.entry("SPOTIFY",        "Abonelikler"),
            Map.entry("YOUTUBE",        "Abonelikler"),
            Map.entry("APPLE",          "Abonelikler"),
            // Kişisel Bakım
            Map.entry("GRATIS",         "Kişisel Bakım"),
            Map.entry("WATSONS",        "Kişisel Bakım"),
            Map.entry("ROSSMANN",       "Kişisel Bakım")
    );

    @Override
    @Transactional
    public void run(String... args) {
        // Only seed if no system categories exist yet
        if (!categoryRepo.findByTelegramUserId(SYSTEM_USER_ID).isEmpty()) {
            log.info("Seed data already exists, skipping.");
            return;
        }

        log.info("Seeding default categories and merchant mappings...");

        // 1. Create categories
        DEFAULT_CATEGORIES.forEach((name, emoji) -> {
            Category cat = Category.builder()
                    .name(name)
                    .emoji(emoji)
                    .telegramUserId(SYSTEM_USER_ID)
                    .custom(false)
                    .build();
            categoryRepo.save(cat);
        });

        // 2. Create merchant mappings (linked to categories via FK)
        DEFAULT_MERCHANTS.forEach((keyword, categoryName) -> {
            Category cat = categoryRepo
                    .findByNameAndTelegramUserId(categoryName, SYSTEM_USER_ID)
                    .orElseThrow(() -> new RuntimeException(
                            "Category not found: " + categoryName));

            MerchantMapping mapping = MerchantMapping.builder()
                    .merchantKeyword(keyword)
                    .category(cat)
                    .telegramUserId(SYSTEM_USER_ID)
                    .source(MappingSource.SYSTEM)
                    .build();
            merchantMappingRepo.save(mapping);
        });

        log.info("Seeded {} categories and {} merchant mappings.",
                DEFAULT_CATEGORIES.size(), DEFAULT_MERCHANTS.size());
    }
}
