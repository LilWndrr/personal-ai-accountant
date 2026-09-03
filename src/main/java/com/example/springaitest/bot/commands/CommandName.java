package com.example.springaitest.bot.commands;

public enum CommandName {
    ABOUT("ABOUT_COMMAND"),
    LANGUAGE("LANGUAGE_COMMAND"),
    START("START_COMMAND"),
    EXPERTS("EXPERTS_COMMAND"),
    ASK_EXPERT("ASK_EXPERT_COMMAND"),
    QUESTION_HANDLER("QUESTION_HANDLER_COMMAND"),
    ASK("ASK_COMMAND"),
    FACT("FACT_COMMAND");

    private final String name;

    CommandName(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
