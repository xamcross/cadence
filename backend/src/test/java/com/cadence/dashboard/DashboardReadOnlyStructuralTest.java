package com.cadence.dashboard;

import com.cadence.service.DashboardService;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F50 T031 (SC-011) — structural proof that {@link DashboardService} is strictly read-only (the F31
 * {@code NoAutoSendStructuralTest} constant-pool-scan precedent). It must hold NO reference to a dispatch /
 * calendar / mutation-capable type and NO repository mutator. The export AUDIT lives on the controller, so
 * {@code AuthAuditService} is banned from the SERVICE (scanning the controller would be vacuous).
 */
class DashboardReadOnlyStructuralTest {

    private static final List<String> BANNED_TYPES = List.of(
        "EmailDispatchService", "EmailSender", "CalendarEventService", "CalendarProviderClient", "AuthAuditService");

    // updateFirst/findAndModify are the Mongo CAS mutators; insert/save are the repository write methods. A
    // future regression that mutated state from the service would surface one of these in the constant pool.
    private static final List<String> BANNED_MUTATORS =
        List.of("updateFirst", "findAndModify", "insert", "save");

    @Test
    void service_hasNoBannedFieldTypes() {
        for (Field f : DashboardService.class.getDeclaredFields()) {
            assertThat(BANNED_TYPES).doesNotContain(f.getType().getSimpleName());
        }
    }

    @Test
    void serviceClass_constantPool_doesNotReferenceBannedTypesOrMutators() throws Exception {
        String resource = "/" + DashboardService.class.getName().replace('.', '/') + ".class";
        try (InputStream in = DashboardService.class.getResourceAsStream(resource)) {
            assertThat(in).as("compiled DashboardService.class on the test classpath").isNotNull();
            String pool = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
            for (String banned : BANNED_TYPES) {
                assertThat(pool).as("no reference to " + banned).doesNotContain(banned);
            }
            for (String mutator : BANNED_MUTATORS) {
                assertThat(pool).as("no repository mutator " + mutator).doesNotContain(mutator);
            }
        }
    }
}
