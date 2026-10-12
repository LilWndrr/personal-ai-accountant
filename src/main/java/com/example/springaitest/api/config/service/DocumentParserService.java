package com.example.springaitest.api.config.service;

import com.example.springaitest.api.config.dto.ParsedResult;
import com.example.springaitest.api.config.dto.ParsedTransaction;
import com.example.springaitest.api.config.dto.ValidationResult;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import org.apache.poi.ss.usermodel.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.springframework.core.io.ByteArrayResource;


@Slf4j
@Service
public class DocumentParserService {

    private final ChatClient chat;
    private static final int MAX_PDF_PAGES = 20;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .findAndRegisterModules();

    private final Executor parserExecutor;

    public DocumentParserService(@Qualifier("financeChatClient") ChatClient chat, @Qualifier("documentParserExecutor") Executor executor) {
        this.chat = chat;
        this.parserExecutor=executor;
    }

    private static final String PARSER_SYSTEM_PROMPT = """
        You are an expert bank statement parser. Extract ALL transactions from the statement table image.
        
        TABLE STRUCTURE RULES:
        - Each horizontal row in the table represents EXACTLY ONE transaction.
        - The 'İşlem Tutarı' (or 'Tutar') column is the transaction 'amount'.
        - Negative numbers are expenses (e.g. -7.700,00 TL), positive numbers are income (e.g. 11.632,50 TL).
        - The 'Bakiye' column is ONLY the account balance after the transaction. Put it in the 'balance' field.
          CRITICAL: NEVER create a separate transaction for the 'Bakiye' (Balance) column!
        - IGNORE placeholder, header, or pending rows where amount is 0,00 TL, or date is '-' (such as 'Diğer Bekleyen İşlemler').
        - Extract merchant/store name from 'Açıklama' if present, otherwise set to null.
        - Extract the bank name from the statement header or logo (e.g. 'Yapı Kredi', 'Ziraat', 'Garanti BBVA', etc.).
        
        OUTPUT FORMAT:
        - Output ONLY a raw JSON array of objects starting with [ and ending with ].
        - Absolutely NO conversational text, explanations, or markdown blocks before or after.
        Example format:
        [
          {"date": "31/08/2026", "amount": 11632.50, "balance": 31706.29, "description": "MAAŞ ÖDEMESİ", "bankName": "Yapı Kredi"}
        ]
        """;

    public Optional<ParsedResult> parse(InputStream document, String fileName) {

        List<ParsedTransaction> transactions;
        String rawText;
        String bankName = detectBankFromFileName(fileName);


        byte[] fileBytes;
        try {
            fileBytes = document.readAllBytes();
        } catch (IOException e) {
            log.error("Failed to read file", e);
            return Optional.empty();
        }

        if (fileName.toLowerCase().endsWith(".xlsx") || fileName.toLowerCase().endsWith(".xls")) {
            // DIRECT PARSING — No LLM needed!
            log.info("Parsing xlsx/xls file directly: {}", fileName);
            transactions = parseXlsxDirectly(new ByteArrayInputStream(fileBytes));
            rawText = "Direct xlsx parse: " + transactions.size() + " transactions";

        } else if (fileName.toLowerCase().endsWith(".csv")) {
            // DIRECT PARSING — No LLM needed!
            log.info("Parsing csv file directly: {}", fileName);
            transactions = parseCsvDirectly(new ByteArrayInputStream(fileBytes));
            rawText = "Direct csv parse: " + transactions.size() + " transactions";

        } else if (fileName.toLowerCase().endsWith(".pdf")) {
            log.info("Processing PDF file with Vision LLM: {}", fileName);
            try (PDDocument pdf = Loader.loadPDF(fileBytes)) {
                int pageCount = pdf.getNumberOfPages();
                log.info("PDF '{}' has {} page(s)", fileName, pageCount);

                if (pageCount > MAX_PDF_PAGES) {
                    log.warn("PDF page count ({}) exceeds maximum allowed ({})", pageCount, MAX_PDF_PAGES);
                    throw new IllegalArgumentException(
                            String.format("PDF has %d pages. Maximum allowed is %d pages.", pageCount, MAX_PDF_PAGES)
                    );
                }

                transactions = extractWithVisionLLM(pdf, pageCount);
                rawText = "Parsed via Vision API. " + transactions.size() + " transactions found.";
            } catch (IOException e) {
                log.error("Failed to load PDF file", e);
                return Optional.empty();
            }

            log.info("Vision LLM returned {} transactions", transactions.size());
            for (int idx = 0; idx < transactions.size(); idx++) {
                ParsedTransaction pt = transactions.get(idx);
                log.info("  -> [{}] Date: {}, Amount: {}, Balance: {}, Desc: '{}'",
                        idx + 1, pt.date(), pt.amount(), pt.balance(), pt.description());
            }

            // Try to pick up bank name from LLM-parsed transactions if not detected from filename
            if (bankName == null && transactions != null) {
                bankName = transactions.stream()
                        .map(ParsedTransaction::bankName)
                        .filter(b -> b != null && !b.isBlank())
                        .findFirst()
                        .orElse(null);
            }

        } else {
            return Optional.empty();
        }

        log.info("Total parsed transactions: {} (bank: {})", transactions.size(), bankName);

        ValidationResult validationResult = validateTransactions(transactions);
        return Optional.of(new ParsedResult(transactions, validationResult, rawText, bankName));
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

                transactions.add(new ParsedTransaction(date, desc, amount, balance, null, null));
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
            List<String> lines = reader.lines().toList();
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

                transactions.add(new ParsedTransaction(date, desc, amount, balance, null, null));
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
        if (value == null || value.isBlank()) return 0;
        // Strip out currency letters/symbols (e.g. TL, TRY, $, €) and whitespace
        value = value.replaceAll("[^0-9.,\\-]", "").trim();
        if (value.isEmpty() || value.equals("-")) return 0;

        // If both period and comma exist, and comma comes last → Turkish format: 1.234,56
        if (value.contains(".") && value.contains(",")) {
            value = value.replace(".", "");  // Remove thousands separator
            value = value.replace(",", "."); // Convert decimal separator
        } else if (value.contains(",")) {
            // Only comma → it's the decimal separator: 77,00
            value = value.replace(",", ".");
        }
        // If only period → it's already standard decimal format: 1234.56

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

    // ==================== VISION LLM PARSER (CONCURRENT PDF) ====================

    private record PageImage(int pageIndex, byte[] bytes) {}
    private record PageResult(int pageIndex, List<ParsedTransaction> transactions) {}

    private List<ParsedTransaction> extractWithVisionLLM(PDDocument pdf, int maxPages) {
        log.info("Starting Vision LLM parsing for {} page(s)...", maxPages);
        PDFRenderer renderer = new PDFRenderer(pdf);

        // 1. Render all pages sequentially on current thread (PDFBox is not thread-safe)
        List<PageImage> pageImages = new ArrayList<>();
        for (int i = 0; i < maxPages; i++) {
            try {
                BufferedImage image = renderer.renderImageWithDPI(i, 150, ImageType.RGB);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(image, "png", baos);
                pageImages.add(new PageImage(i + 1, baos.toByteArray()));
            } catch (IOException e) {
                log.error("Failed to render page {} to image", i + 1, e);
            }
        }

        if (pageImages.isEmpty()) {
            return Collections.emptyList();
        }

        // 2. Dispatch LLM calls concurrently on the injected parserExecutor
        List<CompletableFuture<PageResult>> futures = pageImages.stream()
                .map(page -> CompletableFuture.supplyAsync(
                        () -> processPageWithRetry(page, maxPages),
                        this.parserExecutor
                ))
                .toList();

        // 3. Wait for all concurrent tasks to finish
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // 4. Collect results and sort strictly by pageIndex to preserve statement chronology
        List<ParsedTransaction> allTransactions = futures.stream()
                .map(CompletableFuture::join)
                .sorted(Comparator.comparingInt(PageResult::pageIndex))
                .flatMap(res -> res.transactions().stream())
                .toList();

        // 5. Deduplicate overlapping transactions across pages
        List<ParsedTransaction> deduped = deduplicate(allTransactions);
        if (deduped.size() != allTransactions.size()) {
            log.info("Removed {} duplicate transactions from image overlaps", allTransactions.size() - deduped.size());
        }
        return deduped;
    }

    /**
     * Isolated worker for an individual page with retry backoff.
     */
    private PageResult processPageWithRetry(PageImage page, int totalPages) {
        int attempts = 0;
        while (attempts < 3) {
            attempts++;
            try {
                log.info("Sending page {}/{} to Vision LLM (Attempt {})...", page.pageIndex(), totalPages, attempts);

                String rawResponse = chat.prompt()
                        .system(PARSER_SYSTEM_PROMPT)
                        .options(OpenAiChatOptions.builder().temperature(0.1))
                        .user(u -> u.text("Extract all transactions from this bank statement page image into a JSON array. Return ONLY the JSON array starting with [ and ending with ].")
                                    .media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(page.bytes())))
                        .call()
                        .content();

                log.info("Page {} Vision Raw Response:\n{}", page.pageIndex(), rawResponse);

                List<ParsedTransaction> chunkResult = parseJsonArray(rawResponse);
                log.info("Vision LLM returned {} transactions for page {}", 
                         chunkResult == null ? 0 : chunkResult.size(), page.pageIndex());

                return new PageResult(page.pageIndex(), chunkResult != null ? chunkResult : Collections.emptyList());

            } catch (Exception e) {
                log.error("Vision LLM failed for page {} (Attempt {})", page.pageIndex(), attempts, e);
                if (attempts < 3) {
                    try {
                        Thread.sleep(2000L * attempts); // Backoff: 2s, 4s
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        log.error("All retry attempts failed for page {}", page.pageIndex());
        return new PageResult(page.pageIndex(), Collections.emptyList());
    }

    private List<ParsedTransaction> parseJsonArray(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            return Collections.emptyList();
        }
        try {
            int start = rawResponse.indexOf('[');
            int end = rawResponse.lastIndexOf(']');
            if (start != -1 && end != -1 && end > start) {
                String json = rawResponse.substring(start, end + 1);
                com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(json);
                if (root.isArray()) {
                    List<ParsedTransaction> result = new ArrayList<>();
                    for (com.fasterxml.jackson.databind.JsonNode node : root) {
                        String date = node.path("date").asText("").trim();
                        String desc = node.path("description").asText("").trim();
                        double amount = parseTurkishNumber(node.path("amount").asText("0"));
                        double balance = parseTurkishNumber(node.path("balance").asText("0"));
                        String merchant = node.hasNonNull("merchantName") ? node.path("merchantName").asText() : null;
                        String bank = node.hasNonNull("bankName") ? node.path("bankName").asText() : null;

                        // Filter out pending, placeholder, or zero-amount non-transactions
                        if (amount == 0.0 && balance == 0.0) {
                            log.info("Skipping zero-amount/balance placeholder row: '{}'", desc);
                            continue;
                        }
                        if (date.isBlank() || date.equals("-") || desc.toLowerCase().contains("bekleyen işlem")) {
                            log.info("Skipping pending/placeholder row: date='{}', desc='{}'", date, desc);
                            continue;
                        }

                        result.add(new ParsedTransaction(date, desc, amount, balance, merchant, bank));
                    }
                    return result;
                }
            } else {
                log.warn("No JSON array bracket found in LLM vision response: {}", rawResponse);
            }
        } catch (Exception e) {
            log.error("Failed to parse JSON array from Vision LLM response: {}", rawResponse, e);
        }
        return Collections.emptyList();
    }

    /**
     * Removes duplicate transactions across pages.
     * Two transactions are considered the same if date, amount, balance and description match.
     */
    private List<ParsedTransaction> deduplicate(List<ParsedTransaction> transactions) {
        List<ParsedTransaction> unique = new ArrayList<>();
        for (ParsedTransaction t : transactions) {
            String key = dedupKey(t);
            boolean duplicate = unique.stream().anyMatch(u -> dedupKey(u).equals(key));
            if (!duplicate) unique.add(t);
        }
        return unique;
    }

    private String dedupKey(ParsedTransaction t) {
        String desc = t.description() == null ? "" : t.description().trim().toLowerCase();
        return t.date() + "|" + t.amount() + "|" + t.balance() + "|" + desc;
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

    // ==================== BANK NAME DETECTION ====================

    /**
     * Tries to detect the bank name from the uploaded file name.
     * Many Turkish banks include their name in exported file names.
     */
    private String detectBankFromFileName(String fileName) {
        if (fileName == null) return null;
        String lower = fileName.toLowerCase(java.util.Locale.of("tr", "TR"));

        if (lower.contains("garanti")) return "Garanti BBVA";
        if (lower.contains("yapikredi") || lower.contains("yapı kredi") || lower.contains("ykb")) return "Yapı Kredi";
        if (lower.contains("ziraat")) return "Ziraat Bankası";
        if (lower.contains("isbank") || lower.contains("İş bank") || lower.contains("isbankasi")) return "İş Bankası";
        if (lower.contains("akbank")) return "Akbank";
        if (lower.contains("halkbank") || lower.contains("halk bank")) return "Halkbank";
        if (lower.contains("vakifbank") || lower.contains("vakıfbank")) return "VakıfBank";
        if (lower.contains("denizbank")) return "DenizBank";
        if (lower.contains("qnb") || lower.contains("finansbank")) return "QNB Finansbank";
        if (lower.contains("teb")) return "TEB";
        if (lower.contains("enpara")) return "Enpara";
        if (lower.contains("papara")) return "Papara";
        if (lower.contains("tosla")) return "Tosla";
        if (lower.contains("ininal") || lower.contains("İninal")) return "İninal";

        return null;
    }
}

