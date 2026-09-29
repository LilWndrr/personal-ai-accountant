package com.example.springaitest.api.config.service;

import com.example.springaitest.api.config.dto.ParsedResult;
import com.example.springaitest.api.config.dto.ParsedTransaction;
import com.example.springaitest.api.config.dto.ValidationResult;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;

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
        - Extract the bank name from the statement header/logo if visible
          (e.g., "Garanti BBVA", "Yapı Kredi", "Ziraat", "İş Bankası", etc.).
          Set bankName on each transaction. If the bank cannot be determined, use null.
        - If a field cannot be determined, use null.
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
            // First try to extract digital text
            rawText = extractFromPdf(fileBytes);
            log.info("Extracted raw text from PDF '{}'. Text length: {} characters", fileName, rawText.length());

            if (rawText.isBlank() || rawText.length() < 50) {
                log.warn("PDF '{}' produced almost no text ({} chars) — it may be a scanned/image PDF. Switching to Vision model...",
                        fileName, rawText.length());
                
                // Fallback to Vision processing
                transactions = extractWithVisionLLM(fileBytes);
                rawText = "Parsed via Vision API. " + transactions.size() + " transactions found.";
            } else {
                // Send the text to the LLM in chunks
                transactions = extractWithLLM(rawText);
            }
            
            log.info("LLM returned {} transactions", transactions.size());

            // Try to pick up bank name from LLM-parsed transactions if not detected from filename
            if (bankName == null) {
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

    /** Max characters per LLM request — keeps output within the model's limits. */
    private static final int CHUNK_SIZE = 8_000;

    /**
     * Number of final lines carried over from a chunk into the next one, so a
     * transaction spanning a chunk boundary still appears complete somewhere.
     */
    private static final int CHUNK_OVERLAP_LINES = 10;

    private List<ParsedTransaction> extractWithLLM(String rawText) {
        List<String> chunks = splitIntoChunks(rawText, CHUNK_SIZE, CHUNK_OVERLAP_LINES);
        log.info("Sending {} chunk(s) to LLM (total {} chars)", chunks.size(), rawText.length());

        List<ParsedTransaction> all = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            try {
                List<ParsedTransaction> chunkResult = chat.prompt()
                        .system(PARSER_SYSTEM_PROMPT)
                        .user("Parse this bank statement (part " + (i + 1) + "/" + chunks.size()
                                + "). Note: this part may overlap with previous parts — extract every"
                                + " complete transaction you can see; duplicates will be removed later."
                                + " Ignore transactions whose row is cut off / incomplete:\n\n" + chunks.get(i))
                        .call()
                        .entity(new ParameterizedTypeReference<List<ParsedTransaction>>() {
                        });
                if (chunkResult != null) {
                    all.addAll(chunkResult);
                }
                log.info("Chunk {}/{}: {} transactions extracted", i + 1, chunks.size(),
                        chunkResult == null ? 0 : chunkResult.size());
            } catch (Exception e) {
                log.error("LLM failed to parse chunk {}/{}", i + 1, chunks.size(), e);
            }
        }

        List<ParsedTransaction> deduped = deduplicate(all);
        if (deduped.size() != all.size()) {
            log.info("Removed {} duplicate transactions from chunk overlaps", all.size() - deduped.size());
        }
        return deduped;
    }

    /**
     * Removes transactions appearing in more than one chunk due to chunk overlap.
     * Two transactions are considered the same if date, amount and description match.
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

    /**
     * Splits text into chunks at line boundaries so transaction rows are never
     * cut in half. Each chunk repeats the last {@code overlapLines} lines of the
     * previous chunk, guaranteeing that a multi-line transaction record crossing
     * a boundary is still seen completely in at least one chunk.
     */
    private List<String> splitIntoChunks(String text, int chunkSize, int overlapLines) {
        List<String> chunks = new ArrayList<>();
        String[] lines = text.split("\\R");

        StringBuilder current = new StringBuilder();
        List<String> currentLines = new ArrayList<>();

        for (String line : lines) {
            if (current.length() + line.length() + 1 > chunkSize && current.length() > 0) {
                chunks.add(current.toString());
                current.setLength(0);
                // Carry over the last lines as overlap into the next chunk
                currentLines = currentLines.subList(Math.max(0, currentLines.size() - overlapLines), currentLines.size());
                for (String overlap : currentLines) {
                    current.append(overlap).append('\n');
                }
            }
            current.append(line).append('\n');
            currentLines.add(line);
        }
        if (current.length() > 0) chunks.add(current.toString());
        return chunks;
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

    private String extractFromPdf(byte[] pdfBytes) {
        try (PDDocument pdf = Loader.loadPDF(pdfBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(pdf);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read PDF file", e);
        }
    }

    // ==================== VISION LLM PARSER (Scanned PDF) ====================

    private List<ParsedTransaction> extractWithVisionLLM(byte[] pdfBytes) {
        log.info("Starting Vision LLM parsing for scanned PDF...");
        List<ParsedTransaction> allTransactions = new ArrayList<>();
        
        try (PDDocument pdf = Loader.loadPDF(pdfBytes)) {
            PDFRenderer renderer = new PDFRenderer(pdf);
            
            // Process all pages in the PDF
            int maxPages = pdf.getNumberOfPages();
            log.info("Rendering {} pages to images for Vision API...", maxPages);
            
            for (int i = 0; i < maxPages; i++) {
                BufferedImage image = renderer.renderImageWithDPI(i, 150, ImageType.RGB);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(image, "png", baos);
                byte[] imageBytes = baos.toByteArray();
                
                boolean success = false;
                int attempts = 0;
                while (!success && attempts < 3) {
                    attempts++;
                    try {
                        log.info("Sending page {}/{} to Vision LLM (Attempt {})...", i + 1, maxPages, attempts);
                        List<ParsedTransaction> chunkResult = chat.prompt()
                                .system(PARSER_SYSTEM_PROMPT)
                                .user(u -> u.text("Parse this bank statement page image. Extract every complete transaction you can see. If the row is cut off, ignore it.")
                                            .media(MimeTypeUtils.IMAGE_PNG, new org.springframework.core.io.ByteArrayResource(imageBytes)))
                                .call()
                                .entity(new ParameterizedTypeReference<List<ParsedTransaction>>() {});
                                
                        if (chunkResult != null) {
                            allTransactions.addAll(chunkResult);
                        }
                        log.info("Vision LLM returned {} transactions for page {}", 
                                chunkResult == null ? 0 : chunkResult.size(), i + 1);
                        success = true;
                    } catch (Exception e) {
                        log.error("Vision LLM failed to parse page {} (Attempt {})", i + 1, attempts, e);
                        if (attempts < 3) {
                            log.info("Waiting 15 seconds before retrying...");
                            try {
                                Thread.sleep(15000);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }
                }
                
                // Add a delay between successful pages to prevent rate limiting (429) on free tier APIs
                if (success && i < maxPages - 1) {
                    try {
                        log.info("Sleeping 10 seconds to respect free API rate limits...");
                        Thread.sleep(10000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        } catch (IOException e) {
            log.error("Failed to render PDF to images", e);
        }
        
        List<ParsedTransaction> deduped = deduplicate(allTransactions);
        if (deduped.size() != allTransactions.size()) {
            log.info("Removed {} duplicate transactions from image overlaps", allTransactions.size() - deduped.size());
        }
        return deduped;
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

