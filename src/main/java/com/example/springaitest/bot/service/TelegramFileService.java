package com.example.springaitest.bot.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.objects.File;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.io.InputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramFileService {

    private final TelegramClient telegramClient;

    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024;

    public InputStream downloadFile(String fileId) {
        try {
            log.info("Requesting file info for fileId: {}", fileId);
            

            GetFile getFileRequest = new GetFile(fileId);
            File telegramFile = telegramClient.execute(getFileRequest);

            if(telegramFile.getFileSize() != null && telegramFile.getFileSize()>MAX_FILE_SIZE){
                return null;
            }

            log.info("Downloading file from path: {}", telegramFile.getFilePath());
            return telegramClient.downloadFileAsStream(telegramFile);
            
        } catch (TelegramApiException e) {
            log.error("Failed to download file from Telegram", e);
            throw new RuntimeException("Could not download file from Telegram", e);
        }
    }
}
