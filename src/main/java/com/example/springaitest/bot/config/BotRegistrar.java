package com.example.springaitest.bot.config;

import com.example.springaitest.bot.MainBot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;

@Slf4j
@Component
public class BotRegistrar implements ApplicationRunner {

    private final MainBot mainBot;

    @Value("${telegram.bot.token:}")
    private String botToken;

    public BotRegistrar(MainBot mainBot) {
        this.mainBot = mainBot;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (botToken == null || botToken.isEmpty()) {
            log.warn("Telegram bot token is not configured. Bot will not start.");
            return;
        }
        TelegramBotsLongPollingApplication botsApp = new TelegramBotsLongPollingApplication();
        botsApp.registerBot(botToken, mainBot);
        log.info("Telegram bot registered and long polling started.");
    }
}
