package com.example.springaitest.bot.service;

import java.text.Normalizer;
import java.util.Locale;

/**
 * How category names are compared everywhere a user types one (rename,
 * picker search), so "yemek /  restoran", "YEMEK / RESTORAN" and
 * "Alisveris" vs "Alışveriş" all count as the same name.
 */
final class CategoryNames {

    private CategoryNames() {
    }

    /** Trimmed, single-spaced form to store or display. */
    static String clean(String name) {
        return name == null ? "" : name.trim().replaceAll("\\s+", " ");
    }

    /** Comparison key: cleaned, Turkish letters and accents folded, lowercase. */
    static String key(String name) {
        return Normalizer.normalize(clean(name), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replace('ı', 'i');
    }
}
