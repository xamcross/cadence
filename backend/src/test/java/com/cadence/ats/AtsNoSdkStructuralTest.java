package com.cadence.ats;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural (SC-009/FR-026): the provider abstraction holds — NO class in {@code com.cadence.service} or
 * {@code com.cadence.scheduler} references the concrete {@code GreenhouseAtsClient} or a Greenhouse URL host.
 * Business logic depends only on the {@code AtsConnector} interface, so the Lever (F41) connector can be added
 * by swapping the bean wiring with zero service/scheduler edits. A source scan (the F22 swap-test spirit).
 */
class AtsNoSdkStructuralTest {

    private static final List<Path> ROOTS = List.of(
        Path.of("src/main/java/com/cadence/service"),
        Path.of("src/main/java/com/cadence/scheduler"));

    @Test
    void noServiceOrSchedulerReferencesTheConcreteClientOrProviderUrl() throws IOException {
        for (Path root : ROOTS) {
            if (!Files.exists(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                List<Path> offenders = files
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(AtsNoSdkStructuralTest::referencesGreenhouseClientOrHost)
                    .toList();
                assertThat(offenders)
                    .as("service/scheduler must depend on AtsConnector, not the Greenhouse client/URL")
                    .isEmpty();
            }
        }
    }

    private static boolean referencesGreenhouseClientOrHost(Path file) {
        try {
            String src = Files.readString(file);
            return src.contains("GreenhouseAtsClient")
                || src.contains("harvest.greenhouse")
                || src.contains("greenhouse.io");
        } catch (IOException e) {
            return false;
        }
    }
}
