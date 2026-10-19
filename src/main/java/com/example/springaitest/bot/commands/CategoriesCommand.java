package com.example.springaitest.bot.commands;

import com.example.springaitest.bot.service.CategoryManagementService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;

@Component
@RequiredArgsConstructor
public class CategoriesCommand implements Command {

    private final CategoryManagementService categoryManagementService;

    @Override
    public boolean canHandle(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText()) return false;
        String text = update.getMessage().getText();
        return text.equals("/categories") || text.equalsIgnoreCase("Categories");
    }

    @Override
    public void handle(Update update) {
        categoryManagementService.sendCategoryList(update.getMessage().getChatId());
    }

    @Override
    public String getCommand() {
        return CommandName.CATEGORIES.getName();
    }
}
