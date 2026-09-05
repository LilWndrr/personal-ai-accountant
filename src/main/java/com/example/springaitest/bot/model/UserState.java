package com.example.springaitest.bot.model;

public enum UserState {
    IDLE("IDLE"),
    PROCESSING("PROCESSING"),
    REVIEWING("REVIEWING"),
    CREATING_CATEGORY("CREATING_CATEGORY");

    private final String name;

    UserState(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
