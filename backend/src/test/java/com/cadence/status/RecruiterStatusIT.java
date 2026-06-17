package com.cadence.status;

import com.cadence.api.CandidateStatusDtos.CandidateStatusView;
import com.cadence.api.CandidateStatusDtos.PublishStatusRequest;
import com.cadence.api.CandidateStatusExceptions;
import com.cadence.api.RbacExceptions;
import com.cadence.config.AuthProperties;
import com.cadence.domain.CandidateAuditEvent;
import com.cadence.domain.CandidateEventType;
import com.cadence.domain.CandidateStatusOutcome;
import com.cadence.domain.ErasureState;
import com.cadence.repository.CandidateRepository;
import com.cadence.security.PiiCrypto;
import com.cadence.service.CandidateStatusService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F30 T025 (Testcontainers): (a) read-your-write update reflects (SC-005); (b) rotation invalidates the old
 * token + the new resolves (SC-011); (c) cold-converter reload decrypts statusStage/statusNextStep; (d)
 * publish provisions a token + the audit carries actor/RECORDED/timestamp (SC-014); (e) publish is guarded
 * on erasureState:ACTIVE (publish onto erased -> scoped 404).
 */
class RecruiterStatusIT extends StatusItBase {

    @Autowired CandidateStatusService service;
    @Autowired CandidateRepository candidates;
    @Autowired PiiCrypto crypto;
    @Autowired AuthProperties authProps;
    @Autowired MongoDatabaseFactory dbFactory;

    private static String tokenOf(String link) {
        return link.substring(link.indexOf("token=") + "token=".length());
    }

    @Test
    void readYourWrite_updateReflects() {
        configuredWorkspace();
        seedCandidate("c1", "Ada", "ada@x.test");
        service.publish(WS, "c1", "actor", new PublishStatusRequest(
            CandidateStatusOutcome.IN_PROGRESS, "Phone screen", "Scheduling onsite", LocalDate.now(clock).plusDays(2)));
        String token = tokenOf(service.statusLinkFor(WS, "c1"));

        CandidateStatusView v1 = service.view(token, "ip");
        assertThat(v1.stage()).isEqualTo("Phone screen");

        // Publish v2 (changed stage + date) — the view must reflect it, no stale read.
        service.publish(WS, "c1", "actor", new PublishStatusRequest(
            CandidateStatusOutcome.IN_PROGRESS, "Onsite", "Collecting feedback", LocalDate.now(clock).plusDays(5)));
        CandidateStatusView v2 = service.view(token, "ip");
        assertThat(v2.stage()).isEqualTo("Onsite");
        assertThat(v2.nextStep()).isEqualTo("Collecting feedback");
    }

    @Test
    void rotation_invalidatesOldToken_andNewResolves() {
        configuredWorkspace();
        seedCandidate("c1", "Ada", "ada@x.test");
        service.publish(WS, "c1", "actor", new PublishStatusRequest(
            CandidateStatusOutcome.IN_PROGRESS, "Onsite", "Feedback", LocalDate.now(clock).plusDays(2)));
        String oldToken = tokenOf(service.statusLinkFor(WS, "c1"));
        assertThat(service.view(oldToken, "ip")).isNotNull(); // resolves before rotation

        String newToken = tokenOf(service.rotateLink(WS, "c1", "actor"));
        assertThat(newToken).isNotEqualTo(oldToken);

        // Old token no longer resolves; new token does.
        assertThatThrownBy(() -> service.view(oldToken, "ip"))
            .isInstanceOf(CandidateStatusExceptions.StatusNotFoundException.class);
        assertThat(service.view(newToken, "ip").stage()).isEqualTo("Onsite");

        // QA NIT: the rotation appended a STATUS_LINK_ROTATED candidate-audit event.
        List<CandidateAuditEvent> events = mongoTemplate.find(
            Query.query(Criteria.where("candidateId").is("c1")), CandidateAuditEvent.class);
        assertThat(events).anyMatch(e -> e.getEventType() == CandidateEventType.STATUS_LINK_ROTATED);
    }

    @Test
    void coldConverterReload_decryptsStatusFreeText() {
        configuredWorkspace();
        seedCandidate("c1", "Ada", "ada@x.test");
        service.publish(WS, "c1", "actor", new PublishStatusRequest(
            CandidateStatusOutcome.IN_PROGRESS, "Onsite stage", "Awaiting scorecards", LocalDate.now(clock).plusDays(2)));

        // A freshly-built MongoTemplate with a cold MongoCustomConversions must decrypt the stored ciphertext.
        MongoCustomConversions conversions = new com.cadence.config.MongoPiiConfig().mongoCustomConversions(crypto);
        MongoMappingContext ctx = new MongoMappingContext();
        ctx.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
        ctx.afterPropertiesSet();
        MappingMongoConverter converter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, ctx);
        converter.setCustomConversions(conversions);
        converter.afterPropertiesSet();
        MongoTemplate cold = new MongoTemplate(dbFactory, converter);

        var loaded = cold.findById("c1", com.cadence.domain.Candidate.class);
        assertThat(loaded).isNotNull();
        assertThat(loaded.getStatusStage()).isEqualTo("Onsite stage");
        assertThat(loaded.getStatusNextStep()).isEqualTo("Awaiting scorecards");
    }

    @Test
    void publish_provisionsToken_andAuditCarriesActorAndOutcome() {
        configuredWorkspace();
        seedCandidate("c1", "Ada", "ada@x.test");
        service.publish(WS, "c1", "member-99", new PublishStatusRequest(
            CandidateStatusOutcome.IN_PROGRESS, "Onsite", "Feedback", LocalDate.now(clock).plusDays(2)));

        var c = candidates.findById("c1").orElseThrow();
        assertThat(c.getStatusToken()).isNotNull();
        assertThat(c.getStatusTokenHash()).isNotNull();
        assertThat(c.getStatusPublishedByMemberId()).isEqualTo("member-99");
        assertThat(c.getStatusPublishedAt()).isNotNull();

        List<CandidateAuditEvent> events = mongoTemplate.find(
            Query.query(Criteria.where("candidateId").is("c1")), CandidateAuditEvent.class);
        CandidateAuditEvent published = events.stream()
            .filter(e -> e.getEventType() == CandidateEventType.STATUS_PUBLISHED).findFirst().orElseThrow();
        assertThat(published.getActorMemberId()).isEqualTo("member-99");
        assertThat(published.getOutcome().name()).isEqualTo("RECORDED");
        assertThat(published.getOccurredAt()).isNotNull();
        // The token-issuance was also audited (no silent credential mint, FR-034).
        assertThat(events).anyMatch(e -> e.getEventType() == CandidateEventType.STATUS_LINK_ISSUED);
    }

    @Test
    void publish_ontoErasedCandidate_isScoped404() {
        configuredWorkspace();
        seedCandidate("c1", "Ada", "ada@x.test");
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is("c1")),
            new Update().set("erasureState", ErasureState.ERASED), com.cadence.domain.Candidate.class);

        assertThatThrownBy(() -> service.publish(WS, "c1", "actor", new PublishStatusRequest(
                CandidateStatusOutcome.IN_PROGRESS, "Onsite", "Feedback", LocalDate.now(clock).plusDays(2))))
            .isInstanceOf(RbacExceptions.ScopedNotFoundException.class);
    }
}
