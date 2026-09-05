package com.example.springaitest.bot.model;

import com.example.springaitest.api.config.dto.CategorizedTransaction;
import com.example.springaitest.api.config.dto.ParsedTransaction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.*;

import java.util.List;


@AllArgsConstructor
@NoArgsConstructor
@Builder
@Getter
@Setter
public class UserSession {


    private String id;

    private Long chatId;

    private UserState userState;

    private List<CategorizedTransaction> pendingReview;
    private int currentReviewIndex;

    private List<ParsedTransaction> originalTransactions;

    private String uploadBatch;

}
