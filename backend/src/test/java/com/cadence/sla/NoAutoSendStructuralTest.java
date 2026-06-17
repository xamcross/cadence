package com.cadence.sla;

import com.cadence.scheduler.SlaNudgeScheduler;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F31 T030 (SC-008) — structural proof that the scheduled scan has NO send path (the F22 {@code MailTransportSwapTest}
 * constant-pool-scan precedent). The only caller that enqueues an SLA holding message is the recruiter approve
 * service method; the scheduler/scan never references {@code EmailDispatchService}. Two non-vacuous checks:
 * (1) no declared field of {@code SlaNudgeScheduler} is an {@code EmailDispatchService};
 * (2) the compiled {@code SlaNudgeScheduler.class} constant pool does not mention {@code EmailDispatchService}.
 */
class NoAutoSendStructuralTest {

    @Test
    void scheduler_hasNoEmailDispatchServiceField() {
        for (Field f : SlaNudgeScheduler.class.getDeclaredFields()) {
            assertThat(f.getType().getSimpleName()).isNotEqualTo("EmailDispatchService");
        }
    }

    @Test
    void schedulerClass_constantPool_doesNotReferenceEmailDispatchService() throws Exception {
        String resource = "/" + SlaNudgeScheduler.class.getName().replace('.', '/') + ".class";
        try (InputStream in = SlaNudgeScheduler.class.getResourceAsStream(resource)) {
            assertThat(in).as("compiled SlaNudgeScheduler.class on the test classpath").isNotNull();
            byte[] bytes = in.readAllBytes();
            String pool = new String(bytes, StandardCharsets.ISO_8859_1);
            assertThat(pool).doesNotContain("EmailDispatchService");
        }
    }
}
