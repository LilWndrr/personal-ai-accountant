package com.example.springaitest.api.config.service;

import com.example.springaitest.api.config.dto.ParsedResult;
import com.example.springaitest.api.config.dto.ParsedTransaction;
import com.example.springaitest.api.config.dto.ValidationResult;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
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

        List<ParsedTransaction> transactions;
        String rawText;

        if (fileName.toLowerCase().endsWith(".xlsx") || fileName.toLowerCase().endsWith(".xls")) {
            // DIRECT PARSING — No LLM needed!
            log.info("Parsing xlsx/xls file directly: {}", fileName);
            transactions = parseXlsxDirectly(document);
            rawText = "Direct xlsx parse: " + transactions.size() + " transactions";

        } else if (fileName.toLowerCase().endsWith(".csv")) {
            // DIRECT PARSING — No LLM needed!
            log.info("Parsing csv file directly: {}", fileName);
            transactions = parseCsvDirectly(document);
            rawText = "Direct csv parse: " + transactions.size() + " transactions";

        } else if (fileName.toLowerCase().endsWith(".pdf")) {
            // PDF still needs LLM because text extraction is messy
            rawText = extractFromPdf(document);
            log.info("Extracted raw text from PDF '{}'. Text length: {} characters", fileName, rawText.length());
            transactions = extractWithLLM(rawText);
            log.info("LLM returned {} transactions", transactions != null ? transactions.size() : 0);

        } else {
            return Optional.empty();
        }

        log.info("Total parsed transactions: {}", transactions.size());

        ValidationResult validationResult = validateTransactions(transactions);
        return Optional.of(new ParsedResult(transactions, validationResult, rawText));
    }

    // ==================== DIRECT XLSX PARSER ====================

    private List<ParsedTransaction> parseXlsxDirectly(InputStream document) {
        List<ParsedTransaction> transactions = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(document)) {
            Sheet sheet = workbook.getSheetAt(0);

            // Find the header row to detect column positions
            int dateCol = -1, descCol = -1, amountCol = -1, balanceCol = -1;
            Row headerRow = null;

            for (Row row : sheet) {
                for (Cell cell : row) {
                    String val = getCellString(cell).toLowerCase().trim();
                    if (val.contains("tarih") || val.contains("date")) {
                        dateCol = cell.getColumnIndex();
                        headerRow = row;
                    }
                    if (val.contains("açıklama") || val.contains("aciklama") || val.contains("description")) {
                        descCol = cell.getColumnIndex();
                    }
                    if (val.contains("tutar") || val.contains("amount") || val.contains("işlem tutarı")) {
                        amountCol = cell.getColumnIndex();
                    }
                    if (val.contains("bakiye") || val.contains("balance")) {
                        balanceCol = cell.getColumnIndex();
                    }
                }
                if (dateCol >= 0 && descCol >= 0 && amountCol >= 0) break;
            }

            if (dateCol < 0 || descCol < 0 || amountCol < 0) {
                log.warn("Could not detect column headers in xlsx. Found: date={}, desc={}, amount={}", dateCol, descCol, amountCol);
                return transactions;
            }

            log.info("Detected columns — date:{}, desc:{}, amount:{}, balance:{}", dateCol, descCol, amountCol, balanceCol);

            // Parse data rows (everything after the header)
            boolean pastHeader = false;
            for (Row row : sheet) {
                if (row.equals(headerRow)) {
                    pastHeader = true;
                    continue;
                }
                if (!pastHeader) continue;

                String date = getCellString(row.getCell(dateCol)).trim();
                String desc = getCellString(row.getCell(descCol)).trim();
                String amountStr = getCellString(row.getCell(amountCol)).trim();

                // Skip empty rows
                if (date.isEmpty() || amountStr.isEmpty()) continue;

                double amount = parseTurkishNumber(amountStr);
                double balance = 0;
                if (balanceCol >= 0 && row.getCell(balanceCol) != null) {
                    balance = parseTurkishNumber(getCellString(row.getCell(balanceCol)).trim());
                }

                transactions.add(new ParsedTransaction(date, desc, amount, balance, null));
            }

        } catch (Exception e) {
            log.error("Failed to parse xlsx directly", e);
            throw new RuntimeException("Failed to parse Excel file", e);
        }
        return transactions;
    }

    // ==================== DIRECT CSV PARSER ====================

    private List<ParsedTransaction> parseCsvDirectly(InputStream document) {
        List<ParsedTransaction> transactions = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(document, StandardCharsets.UTF_8))) {
            List<String> lines = reader.lines().collect(Collectors.toList());
            if (lines.isEmpty()) return transactions;

            // Detect delimiter (comma, semicolon, or tab)
            String headerLine = lines.get(0);
            String delimiter = headerLine.contains(";") ? ";" : headerLine.contains("\t") ? "\t" : ",";

            // Find column positions from header
            String[] headers = headerLine.split(delimiter);
            int dateCol = -1, descCol = -1, amountCol = -1, balanceCol = -1;
            for (int i = 0; i < headers.length; i++) {
                String h = headers[i].toLowerCase().trim().replace("\"", "");
                if (h.contains("tarih") || h.contains("date")) dateCol = i;
                if (h.contains("açıklama") || h.contains("aciklama") || h.contains("description")) descCol = i;
                if (h.contains("tutar") || h.contains("amount") || h.contains("işlem tutarı")) amountCol = i;
                if (h.contains("bakiye") || h.contains("balance")) balanceCol = i;
            }

            if (dateCol < 0 || descCol < 0 || amountCol < 0) {
                log.warn("Could not detect CSV column headers. Found: date={}, desc={}, amount={}", dateCol, descCol, amountCol);
                return transactions;
            }

            // Parse data rows
            for (int i = 1; i < lines.size(); i++) {
                String[] cols = lines.get(i).split(delimiter);
                if (cols.length <= Math.max(dateCol, Math.max(descCol, amountCol))) continue;

                String date = cols[dateCol].trim().replace("\"", "");
                String desc = cols[descCol].trim().replace("\"", "");
                String amountStr = cols[amountCol].trim().replace("\"", "");

                if (date.isEmpty() || amountStr.isEmpty()) continue;

                double amount = parseTurkishNumber(amountStr);
                double balance = 0;
                if (balanceCol >= 0 && cols.length > balanceCol) {
                    balance = parseTurkishNumber(cols[balanceCol].trim().replace("\"", ""));
                }

                transactions.add(new ParsedTransaction(date, desc, amount, balance, null));
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse CSV file", e);
        }
        return transactions;
    }

    // ==================== HELPERS ====================

    /**
     * Converts Turkish number format to a double.
     * Turkish: "1.234,56" → 1234.56
     * Also handles plain numbers like "1234.56" or "-77,00"
     */
    private double parseTurkishNumber(String value) {
        if (value == null || value.isEmpty()) return 0;
        value = value.trim().replace(" ", "");

        // If both period and comma exist, and comma comes last → Turkish format
        if (value.contains(".") && value.contains(",")) {
            value = value.replace(".", "");  // Remove thousands separator
            value = value.replace(",", "."); // Convert decimal separator
        } else if (value.contains(",")) {
            // Only comma → it's the decimal separator
            value = value.replace(",", ".");
        }
        // If only period → it's already standard format

        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            log.warn("Could not parse number: '{}'", value);
            return 0;
        }
    }

    /**
     * Safely extracts a string value from any cell type.
     */
    private String getCellString(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("dd.MM.yyyy");
                    yield sdf.format(cell.getDateCellValue());
                }
                // Use BigDecimal to avoid scientific notation (e.g., 1.23E4)
                yield new java.math.BigDecimal(cell.getNumericCellValue()).toPlainString();
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> cell.getCachedFormulaResultType() == CellType.NUMERIC
                    ? new java.math.BigDecimal(cell.getNumericCellValue()).toPlainString()
                    : cell.getStringCellValue();
            default -> "";
        };
    }

    // ==================== LLM PARSER (PDF only) ====================

    private List<ParsedTransaction> extractWithLLM(String rawText) {
        return chat.prompt().system(PARSER_SYSTEM_PROMPT)
                .user("Parse this bank statement:\n\n" + rawText)
                .call().entity(new ParameterizedTypeReference<List<ParsedTransaction>>() {
                });
    }

    private ValidationResult validateTransactions(List<ParsedTransaction> transactions) {
        List<Integer> invalidIndices = new ArrayList<>();
        for (int i = 1; i < transactions.size(); i++) {
            ParsedTransaction prev = transactions.get(i - 1);
            ParsedTransaction curr = transactions.get(i);
            if (prev.balance() == 0 || curr.balance() == 0) continue;
            double expected = prev.balance() + curr.amount();
            if (Math.abs(curr.balance() - expected) > 0.01) {
                invalidIndices.add(i);
            }
        }
        return new ValidationResult(invalidIndices.isEmpty(), invalidIndices);
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

