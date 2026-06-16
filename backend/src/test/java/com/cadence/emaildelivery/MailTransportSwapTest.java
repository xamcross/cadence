package com.cadence.emaildelivery;

import com.cadence.domain.AuthEventType;
import com.cadence.domain.Candidate;
import com.cadence.domain.DispatchStatus;
import com.cadence.domain.EmailMessageType;
import com.cadence.domain.RenderedMessage;
import com.cadence.integration.MailTransport;
import com.cadence.integration.OutboundEmail;
import com.cadence.integration.SendOutcome;
import com.cadence.service.EmailBounceService;
import com.cadence.service.EmailBounceService.EventType;
import com.cadence.service.EmailDispatchService;
import com.cadence.service.EmailDispatchService.DispatchResult;
import com.cadence.service.EmailTemplateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * T049 (US5) — provider portability (FR-003 / SC-007). The {@link MailTransport} bean is replaced with a
 * SECOND alternate recording implementation, and the full dispatch behaviour (gate refusal, idempotency,
 * send-accounting, bounce flip + dead-letter) is preserved with ZERO changes to any service class — the
 * services depend only on the {@code EmailSender}/{@code MailTransport} seam.
 *
 * <p>Plus a STRUCTURAL guard: no class in {@code com.cadence.service} references a concrete provider/SMTP/
 * JavaMailSender/jakarta.mail type — only the {@code com.cadence.integration} package may. The scan reads
 * each service class file's constant pool (UTF-8 strings) so it catches a transitive/local reference, not
 * just a declared field type.
 */
@Import(MailTransportSwapTest.AltTransportConfig.class)
@org.springframework.test.context.TestPropertySource(
    properties = "spring.main.allow-bean-definition-overriding=true") // swap the base @Primary transport bean
class MailTransportSwapTest extends EmailDeliveryItBase {

    @Autowired EmailDispatchService dispatch;
    @Autowired EmailBounceService bounce;
    @Autowired AltRecordingTransport altTransport;
    @MockBean EmailTemplateService templates;

    /**
     * A distinct second transport impl (a {@link MailTransport}, IS-A {@link RecordingMailTransport} only so the
     * base fixture's typed field injection still resolves) — proves swapping the bean changes nothing in the
     * business code. {@link #transmit} is fully overridden; it does NOT use the base recording state.
     */
    static final class AltRecordingTransport extends RecordingMailTransport {
        final ConcurrentLinkedQueue<OutboundEmail> seen = new ConcurrentLinkedQueue<>();
        @Override public SendOutcome transmit(OutboundEmail message) {
            seen.add(message);
            return SendOutcome.accepted("alt-" + seen.size());
        }
    }

    /**
     * Override the base fixture's {@code @Primary recordingMailTransport} bean with the alternate impl — by
     * sharing the bean NAME (Spring bean-definition override) there is exactly one {@code @Primary}
     * {@link MailTransport}, so no {@code NoUniqueBeanDefinitionException}.
     */
    @TestConfiguration
    static class AltTransportConfig {
        @Bean @Primary
        RecordingMailTransport recordingMailTransport() {
            return new AltRecordingTransport();
        }
    }

    private DispatchResult send(String candidateId) {
        return dispatch.enqueue(WS, candidateId, EmailMessageType.CONFIRMATION, "BASE",
            Instant.now(clock), null, null);
    }

    @Test
    void dispatchBehaviour_preserved_withTheSwappedTransport() {
        when(templates.renderForSend(eq(WS), eq(EmailMessageType.CONFIRMATION), anyString(), any(), any()))
            .thenReturn(new RenderedMessage("Subject", "Body", "Body", List.of()));

        // gate refusal is unchanged (no basis -> REFUSED, zero transmits)
        mongoTemplate.save(newCandidate("c-norebasis", "Dana", "d@x.com"));
        assertThat(send("c-norebasis").status()).isEqualTo(DispatchStatus.REFUSED);
        assertThat(altTransport.seen).isEmpty();

        // a consenting send goes through the SWAPPED transport
        seedContactableCandidate("c1", "Dana", "dana@x.com");
        DispatchResult first = send("c1");
        assertThat(first.status()).isEqualTo(DispatchStatus.SENT);
        assertThat(altTransport.seen).hasSize(1);

        // idempotency is unchanged: a duplicate enqueue (same key) does NOT transmit again
        DispatchResult dup = send("c1");
        assertThat(dup.idempotentDuplicate()).isTrue();
        assertThat(altTransport.seen).hasSize(1);

        // bounce flip + dead-letter visibility is unchanged through the swapped transport's providerMessageRef
        String ref = mongoTemplate.findById(first.dispatchId(), com.cadence.domain.EmailDispatch.class)
            .getProviderMessageRef();
        bounce.process("evt-1", ref, EventType.HARD_BOUNCE);
        Candidate flagged = mongoTemplate.findById("c1", Candidate.class);
        assertThat(flagged.isUndeliverable()).isTrue();
        List<com.cadence.domain.AuthAuditEvent> bounced = mongoTemplate.find(
            Query.query(Criteria.where("eventType").is(AuthEventType.EMAIL_DISPATCH_BOUNCED)),
            com.cadence.domain.AuthAuditEvent.class);
        assertThat(bounced).hasSize(1);
    }

    @Test
    void noServiceClass_referencesAProviderOrSmtpType() throws IOException {
        Path serviceDir = Path.of("build", "classes", "java", "main", "com", "cadence", "service");
        assertThat(Files.isDirectory(serviceDir)).as("compiled service classes present").isTrue();

        // Forbidden concrete-transport type tokens. Note: EmailSender/MailTransport (the integration-package
        // SEAM interfaces) are ALLOWED in the service layer (DeadLetterService uses EmailSender); the ban is
        // on a CONCRETE provider/SMTP type leaking past the seam.
        String[] forbidden = {
            "org/springframework/mail/javamail/JavaMailSender",
            "jakarta/mail/", "javax/mail/",
            "com/cadence/integration/SmtpMailTransport",
            "com/cadence/integration/SmtpEmailSender"
        };

        try (Stream<Path> files = Files.walk(serviceDir)) {
            List<Path> classFiles = files.filter(p -> p.toString().endsWith(".class")).toList();
            assertThat(classFiles).isNotEmpty();
            for (Path classFile : classFiles) {
                byte[] bytes = Files.readAllBytes(classFile);
                String pool = constantPoolStrings(bytes);
                for (String token : forbidden) {
                    assertThat(pool)
                        .as("%s must not reference %s (only com.cadence.integration may)", classFile.getFileName(), token)
                        .doesNotContain(token);
                }
            }
        }
    }

    /** Render the class file's bytes as a single Latin-1 string so the constant-pool UTF-8 entries are searchable. */
    private static String constantPoolStrings(byte[] classBytes) {
        return new String(classBytes, java.nio.charset.StandardCharsets.ISO_8859_1);
    }
}
