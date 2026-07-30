package com.cadence.billing;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 032 -- the AtsNoSdkStructuralTest analogue: business logic must depend on the BillingProvider
 * seam only. No file under service/ or scheduler/ may mention the concrete client or the
 * Freemius hosts (FR-018 / SC-005-adjacent).
 */
class BillingNoSdkStructuralTest {

    private static final List<String> FORBIDDEN = List.of("FreemiusBillingClient", "freemius.com");

    @Test
    void serviceAndSchedulerLayers_neverReferenceTheConcreteBillingClient() throws IOException {
        for (String dir : List.of("src/main/java/com/cadence/service", "src/main/java/com/cadence/scheduler")) {
            try (Stream<Path> files = Files.walk(Path.of(dir))) {
                files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                    String source;
                    try {
                        source = Files.readString(p);
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                    for (String needle : FORBIDDEN) {
                        assertThat(source).withFailMessage("%s references '%s'", p, needle)
                            .doesNotContain(needle);
                    }
                });
            }
        }
    }
}
