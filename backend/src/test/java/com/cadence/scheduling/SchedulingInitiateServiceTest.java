package com.cadence.scheduling;

import com.cadence.api.SchedulingExceptions;
import com.cadence.domain.AuthEventType;
import com.cadence.domain.AvailabilityStatus;
import com.cadence.domain.BusyInterval;
import com.cadence.domain.Candidate;
import com.cadence.domain.EmailMessageType;
import com.cadence.domain.InterviewTemplate;
import com.cadence.domain.Member;
import com.cadence.domain.MemberAvailability;
import com.cadence.domain.Role;
import com.cadence.domain.SchedulingRequest;
import com.cadence.domain.SchedulingStatus;
import com.cadence.service.AvailabilityService;
import com.cadence.service.EmailDispatchService;
import com.cadence.service.SchedulingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * US1 initiation correctness: gate refusal -> NotContactable + zero persisted requests; busy required member
 * (zero slots) -> NoSlots; success -> persisted request with the hashed token (raw NOT stored), one INVITATION
 * enqueued, a SCHEDULING_LINK_SENT audit; re-send supersedes the prior live request.
 */
class SchedulingInitiateServiceTest extends SchedulingItBase {

    @Autowired SchedulingService service;
    @MockBean AvailabilityService availability;
    @MockBean EmailDispatchService dispatch;

    private void everyoneFree() {
        when(availability.query(eq(WS), any(), any(), anyList())).thenAnswer(inv -> {
            List<String> ids = inv.getArgument(3);
            List<MemberAvailability> out = new ArrayList<>();
            for (String id : ids) out.add(new MemberAvailability(id, AvailabilityStatus.DATA, List.of()));
            return out;
        });
    }

    @Test
    void notContactableCandidate_refused_andNoRequestPersisted() {
        configuredWorkspace();
        Member req = member("req@x.com", Role.INTERVIEWER);
        Member actor = member("rec@x.com", Role.RECRUITER);
        Candidate c = newCandidate("cand1", "Dana", "dana@x.com"); // lawfulBasis null -> NO_BASIS
        mongoTemplate.save(c);
        InterviewTemplate t = seedTemplate(req.getId());

        assertThatThrownBy(() -> service.initiate(WS, actor.getId(), "cand1", t.getId(),
                "Room 1", LocalDate.parse("2026-06-15"), LocalDate.parse("2026-06-20"), "1.2.3.4"))
            .isInstanceOf(SchedulingExceptions.NotContactableException.class);

        assertThat(mongoTemplate.count(new Query(), SchedulingRequest.class)).isZero();
        verify(dispatch, never()).enqueue(any(), any(), any(), any(), any(), any(), any());
        assertThat(auditCount(AuthEventType.SCHEDULING_REFUSED)).isEqualTo(1);
        assertThat(auditCount(AuthEventType.SCHEDULING_LINK_SENT)).isZero();
    }

    @Test
    void noCompliantSlots_throwsNoSlots_andNoRequestPersisted() {
        configuredWorkspace();
        Member req = member("req@x.com", Role.INTERVIEWER);
        Member actor = member("rec@x.com", Role.RECRUITER);
        seedContactableCandidate("cand1", "Dana", "dana@x.com");
        InterviewTemplate t = seedTemplate(req.getId());

        // Required member fully busy across working hours -> no slots.
        when(availability.query(eq(WS), any(), any(), anyList())).thenAnswer(inv -> {
            List<String> ids = inv.getArgument(3);
            List<MemberAvailability> out = new ArrayList<>();
            for (String id : ids) {
                out.add(new MemberAvailability(id, AvailabilityStatus.DATA, List.of(
                    new BusyInterval(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2027-01-01T00:00:00Z")))));
            }
            return out;
        });

        assertThatThrownBy(() -> service.initiate(WS, actor.getId(), "cand1", t.getId(),
                "Room 1", LocalDate.parse("2026-06-15"), LocalDate.parse("2026-06-20"), "1.2.3.4"))
            .isInstanceOf(SchedulingExceptions.NoSlotsException.class);

        assertThat(mongoTemplate.count(new Query(), SchedulingRequest.class)).isZero();
        assertThat(auditCount(AuthEventType.SCHEDULING_REFUSED)).isEqualTo(1);
    }

    @Test
    void success_persistsRequest_hashesToken_enqueuesInvitation_andAudits() {
        configuredWorkspace();
        everyoneFree();
        Member req = member("req@x.com", Role.INTERVIEWER);
        Member actor = member("rec@x.com", Role.RECRUITER);
        seedContactableCandidate("cand1", "Dana", "dana@x.com");
        InterviewTemplate t = seedTemplate(req.getId());

        SchedulingService.InitiateResult r = service.initiate(WS, actor.getId(), "cand1", t.getId(),
            "Room 1", LocalDate.parse("2026-06-15"), LocalDate.parse("2026-06-16"), "1.2.3.4");

        assertThat(r.status()).isEqualTo(SchedulingStatus.PENDING_SELECTION);
        assertThat(r.offeredSlotCount()).isGreaterThan(0);

        SchedulingRequest saved = mongoTemplate.findById(r.schedulingRequestId(), SchedulingRequest.class);
        assertThat(saved).isNotNull();
        // Only the HMAC token hash is persisted — the raw token is never stored (it rides the transient email).
        assertThat(saved.getTokenHash()).isNotBlank().hasSizeGreaterThan(20);
        assertThat(saved.getOfferedSlots()).isNotEmpty();
        // The raw-driver BSON has a tokenHash field but no plaintext token field.
        org.bson.Document raw = mongoTemplate.getCollection("schedulingRequests")
            .find(new org.bson.Document("_id", new org.bson.types.ObjectId(saved.getId()))).first();
        assertThat(raw.getString("tokenHash")).isEqualTo(saved.getTokenHash());
        assertThat(raw.containsKey("token")).isFalse();
        assertThat(raw.containsKey("rawToken")).isFalse();

        // Exactly one INVITATION enqueued for this candidate (the consent-gated link send).
        verify(dispatch, times(1)).enqueue(eq(WS), eq("cand1"), eq(EmailMessageType.INVITATION),
            eq("BASE"), any(), anyMap(), isNull());
        assertThat(auditCount(AuthEventType.SCHEDULING_LINK_SENT)).isEqualTo(1);
    }

    @Test
    void reSend_supersedesPriorLiveRequest() {
        configuredWorkspace();
        everyoneFree();
        Member req = member("req@x.com", Role.INTERVIEWER);
        Member actor = member("rec@x.com", Role.RECRUITER);
        seedContactableCandidate("cand1", "Dana", "dana@x.com");
        InterviewTemplate t = seedTemplate(req.getId());

        SchedulingService.InitiateResult first = service.initiate(WS, actor.getId(), "cand1", t.getId(),
            null, LocalDate.parse("2026-06-15"), LocalDate.parse("2026-06-16"), "1.2.3.4");
        SchedulingService.InitiateResult second = service.initiate(WS, actor.getId(), "cand1", t.getId(),
            null, LocalDate.parse("2026-06-15"), LocalDate.parse("2026-06-16"), "1.2.3.4");

        SchedulingRequest old = mongoTemplate.findById(first.schedulingRequestId(), SchedulingRequest.class);
        SchedulingRequest cur = mongoTemplate.findById(second.schedulingRequestId(), SchedulingRequest.class);
        assertThat(old.getStatus()).isEqualTo(SchedulingStatus.SUPERSEDED);
        assertThat(old.getSupersededByRequestId()).isEqualTo(second.schedulingRequestId());
        assertThat(cur.getStatus()).isEqualTo(SchedulingStatus.PENDING_SELECTION);
    }

    private long auditCount(AuthEventType type) {
        return mongoTemplate.count(
            new Query(Criteria.where("eventType").is(type.name())), "authAuditLog");
    }
}
