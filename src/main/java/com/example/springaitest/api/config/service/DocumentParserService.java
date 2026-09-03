package com.example.springaitest.api.config.service;

import com.example.springaitest.api.config.dto.ParsedResult;
import com.example.springaitest.api.config.dto.ParsedTransaction;
import com.example.springaitest.api.config.dto.ValidationResult;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class DocumentParserService {

    private final ChatClient chat;

    public DocumentParserService(@Qualifier("financeChatClient") ChatClient chat) {
        this.chat = chat;
    }

    private static final String PARSER_SYSTEM_PROMPT = """
        You are a bank statement parser. Extract ALL transactions from the raw
        text of a bank statement.
        
        Rules:
        - Parse every transaction row. Do NOT skip any.
        - Negative amounts are expenses, positive amounts are income.
        - Dates may be DD/MM/YYYY or DD.MM.YYYY — normalize to DD.MM.YYYY.
        - Amounts use Turkish format: period for thousands separator, comma
          for decimals (e.g., "1.234,56" = 1234.56). Convert to plain decimal.
        - Extract the merchant/store name from the description if present.
        - Include the balance after each transaction if available.
        - If a field cannot be determined, use null.
        """;

    public Optional<ParsedResult> parse(InputStream document, String fileName) {

        String rawText;
        if (fileName.toLowerCase().endsWith(".pdf")) {
            rawText = extractFromPdf(document);
        } else if (fileName.toLowerCase().endsWith(".csv")) {
            rawText = extractFromCsv(document);
        } else {
            return Optional.empty();
        }

        List<ParsedTransaction> transactions = extractWithLLM(rawText);

        ValidationResult validationResult = validateTransactions(transactions);

        return Optional.of(new ParsedResult(transactions, validationResult, rawText));
    }

    private ValidationResult validateTransactions(List<ParsedTransaction> transactions) {
        List<Integer> invalidIndices = new ArrayList<>();

        for (int i = 1; i < transactions.size(); i++) {
            ParsedTransaction prev = transactions.get(i - 1);
            ParsedTransaction curr = transactions.get(i);

            if (prev.balance() == 0 || curr.balance() == 0) {
                continue;
            }

            double expected = prev.balance() + curr.amount();

            if (Math.abs(curr.balance() - expected) > 0.01) {
                invalidIndices.add(i);
            }
        }

        return new ValidationResult(invalidIndices.isEmpty(), invalidIndices);
    }

    private List<ParsedTransaction> extractWithLLM(String rawText) {

        return chat.prompt().system(PARSER_SYSTEM_PROMPT)
                .user("Parse this bank statement:\n\n" + rawText)
                .call().entity(new ParameterizedTypeReference<List<ParsedTransaction>>() {
                });
    }

    private String extractFromCsv(InputStream document) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(document, StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        } catch (IOException ex) {
            throw new RuntimeException("Failed to read CSV file", ex);
        }
    }

    private String extractFromPdf(InputStream document) {
        try {
            PDDocument pdf = Loader.loadPDF(document.readAllBytes());
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(pdf);
            pdf.close();
            return text;
        } catch (IOException e) {
            throw new RuntimeException("Failed to read PDF file", e);
        }
    }
}
