package com.cadence.csvimport;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F42 structural (the F22 swap-test / F40 no-SDK precedent): the Commons-CSV dependency is CONFINED to
 * {@code CsvImportProcessor} — no other class in {@code com.cadence.service}/{@code scheduler}/{@code api}
 * references {@code org.apache.commons.csv}. The rest of the layer depends only on the {@link
 * com.cadence.service.CsvRow} seam, so the parser is swappable.
 */
class CsvParserConfinementTest {

    private static final List<Path> ROOTS = List.of(
        Path.of("src/main/java/com/cadence/service"),
        Path.of("src/main/java/com/cadence/scheduler"),
        Path.of("src/main/java/com/cadence/api"));

    @Test
    void onlyCsvImportProcessorReferencesCommonsCsv() throws IOException {
        for (Path root : ROOTS) {
            if (!Files.exists(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                List<Path> offenders = files
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().equals("CsvImportProcessor.java"))
                    .filter(CsvParserConfinementTest::referencesCommonsCsv)
                    .toList();
                assertThat(offenders)
                    .as("only CsvImportProcessor may reference org.apache.commons.csv")
                    .isEmpty();
            }
        }
    }

    private static boolean referencesCommonsCsv(Path file) {
        try {
            return Files.readString(file).contains("org.apache.commons.csv");
        } catch (IOException e) {
            return false;
        }
    }
}
