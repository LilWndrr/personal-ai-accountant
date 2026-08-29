package com.example.springaitest.service;

import com.example.springaitest.domain.Transaction;
import com.example.springaitest.dto.ParsedResult;
import com.example.springaitest.dto.ParsedTransaction;
import com.example.springaitest.dto.ValidationResult;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.exceptions.CsvException;
import com.opencsv.exceptions.CsvValidationException;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.apache.pdfbox.Loader.loadPDF;

@Service
@RequiredArgsConstructor
public class DocumentParserService {

    private final ChatClient chat;

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

    public Optional<ParsedResult> parse(InputStream document, String fileName){

        String rawText;
        List<ParsedTransaction> transactions;
        ValidationResult validationResult;
        if(fileName.endsWith(".pdf")){
            rawText = extractFromPdf(document);
        }else if(fileName.endsWith(".csv")){
            rawText = extractFromCsv(document);
        }else {
            return Optional.empty();
        }
        
        transactions = extractWithLLM(rawText);

        validationResult =  validateTransactions(transactions);

        return Optional.of(new ParsedResult(transactions, validationResult, rawText));


    }

    private ValidationResult validateTransactions(List<ParsedTransaction> transactions) {

    }

    private List<ParsedTransaction> extractWithLLM(String rawText) {
        return null;
    }

    private String extractFromCsv(InputStream document) {

        try( Reader reader = new InputStreamReader(document, StandardCharsets.UTF_8);
             CSVReader csvReader = new CSVReaderBuilder(reader).withSkipLines(1).build();) {

            return csvReader.readAll().toString();


            } catch (IOException | CsvException ex) {
            throw new RuntimeException(ex);
        }
    }


    }

    private String extractFromPdf(InputStream document) {
        try {
          PDDocument pdf =  Loader.loadPDF(document.readAllBytes());
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(pdf);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }


}
