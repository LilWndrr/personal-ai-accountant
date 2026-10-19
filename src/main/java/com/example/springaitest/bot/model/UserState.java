package com.example.springaitest.bot.model;

public enum UserState {
    IDLE("IDLE"),
    PROCESSING("PROCESSING"),
    REVIEWING("REVIEWING"),
    CREATING_CATEGORY("CREATING_CATEGORY"),
    AWAITING_CUSTOM_RANGE("AWAITING_CUSTOM_RANGE"),
    AWAITING_CUSTOM_RANGE_TRANSACTIONS("AWAITING_CUSTOM_RANGE_TRANSACTIONS"),
    EDITING_TRANSACTION_CATEGORY("EDITING_TRANSACTION_CATEGORY"),
    AWAITING_CATEGORY_RENAME("AWAITING_CATEGORY_RENAME");

    private final String name;

    UserState(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
