package com.example.springaitest.api.config.service;

import com.example.springaitest.api.config.dto.ParsedResult;
import com.example.springaitest.api.config.dto.ParsedTransaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

public class DocumentParserServiceTest {

    private final DocumentParserService parser = new DocumentParserService(null, null, null);
    private ParsedResult parseCsv(String csv) {
        return parser.parse(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), "statement.csv")
                .orElseThrow();
    }


    @Test
    void parsesTurkishFormattedAmountAndBalance() {
        // Arrange
        String csv = """
                Tarih;Açıklama;Tutar;Bakiye
                05.10.2026;MIGROS;-1.234,56;10.000,00
                """;

        // Act
        ParsedTransaction tx = parseCsv(csv).transactions().get(0);

        // Assert
        assertThat(tx.amount()).isEqualTo(-1234.56);
        assertThat(tx.balance()).isEqualTo(10000.00);
    }

    @ParameterizedTest(name = "\"{0}\" → {1}")
    @CsvSource(delimiter = '|', value = {
            "-45,00      | -45.00",
            "45,00 TL    | 45.00",
            "1.234,56    | 1234.56",
            "1234.56     | 1234.56",
            "1,234.56    | 1234.56",
            "1234.56     | 1234.56",
            "1234.56     | 1234.56",
    })
    void parsesAmountFormats(String raw, double expected) {
        String csv = "Tarih;Açıklama;Tutar\n05.10.2026;TEST;" + raw + "\n";

        assertThat(parseCsv(csv).transactions().get(0).amount()).isEqualTo(expected);
    }





}
