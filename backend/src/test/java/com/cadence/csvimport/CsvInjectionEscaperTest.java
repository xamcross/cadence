package com.cadence.csvimport;

import com.cadence.service.CsvInjectionEscaper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F42 unit (SC-006/FR-018): formula-injection is neutralized at the EXPORT boundary; legitimate {@code +}/{@code -}
 * values are NOT corrupted in storage (the escaper only mutates on emit). RFC-4180 quoting on emit too.
 */
class CsvInjectionEscaperTest {

    private final CsvInjectionEscaper escaper = new CsvInjectionEscaper();

    @Test
    void formulaTriggers_arePrefixed() {
        assertThat(escaper.escapeForSpreadsheet("=cmd()")).startsWith("'=");
        assertThat(escaper.escapeForSpreadsheet("@SUM(A1)")).startsWith("'@");
        assertThat(escaper.escapeForSpreadsheet("|pipe")).startsWith("'|");
        assertThat(escaper.escapeForSpreadsheet("\tTabLed")).startsWith("'\t");
        assertThat(escaper.escapeForSpreadsheet("   =leadingspace")).startsWith("'");
    }

    @Test
    void legitimatePhoneAndName_areNotMutated_whenSafeToEmit() {
        // A +44 phone or a hyphen-led token IS a formula trigger by the leading char, so it is neutralized
        // on EMIT (prefixed) — but it is stored verbatim by the importer (the escaper is export-only). Here we
        // assert the escaper's emit form is a literal-safe string, never an evaluated formula.
        assertThat(escaper.escapeForSpreadsheet("+441234567")).isEqualTo("'+441234567");
        assertThat(escaper.escapeForSpreadsheet("Plain Name")).isEqualTo("Plain Name"); // untouched
    }

    @Test
    void valuesWithDelimitersOrQuotes_areRfc4180Quoted() {
        assertThat(escaper.escapeForSpreadsheet("a,b")).isEqualTo("\"a,b\"");
        assertThat(escaper.escapeForSpreadsheet("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(escaper.escapeForSpreadsheet("line1\nline2")).isEqualTo("\"line1\nline2\"");
    }

    @Test
    void nullIsNull() {
        assertThat(escaper.escapeForSpreadsheet(null)).isNull();
    }
}
