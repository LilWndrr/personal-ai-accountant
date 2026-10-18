package com.example.springaitest.bot.commands;

public enum CommandName {
    ABOUT("ABOUT_COMMAND"),

    START("START_COMMAND"),

    REPORT("REPORT_COMMAND"),

    TRANSACTIONS("TRANSACTIONS_COMMAND");

    private final String name;

    CommandName(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
