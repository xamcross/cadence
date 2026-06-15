package com.cadence.interview;

import com.cadence.api.InterviewTemplateDtos.BlackoutDto;
import com.cadence.api.InterviewTemplateDtos.PoolRuleDto;
import com.cadence.api.InterviewTemplateDtos.TemplateRequest;
import com.cadence.api.InterviewTemplateDtos.WorkingHoursDto;
import com.cadence.api.InterviewTemplateExceptions.InvalidTemplateException;
import com.cadence.config.InterviewTemplateProperties;
import com.cadence.domain.InterviewTemplate;
import com.cadence.domain.Member;
import com.cadence.repository.InterviewTemplateRepository;
import com.cadence.repository.MemberRepository;
import com.cadence.service.AuthAuditService;
import com.cadence.service.InterviewTemplateService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** SC-008: every invalid field is refused with a VALUE-FREE message and nothing is persisted. */
class InterviewTemplateValidationTest {

    private static final String WS = "ws1";

    private final InterviewTemplateRepository templates = mock(InterviewTemplateRepository.class);
    private final MemberRepository members = mock(MemberRepository.class);
    private final AuthAuditService audit = mock(AuthAuditService.class);

    private InterviewTemplateService service(InterviewTemplateProperties props, String... memberIds) {
        List<Member> ms = new ArrayList<>();
        for (String id : memberIds) {
            Member m = new Member();
            m.setId(id);
            m.setWorkspaceId(WS);
            ms.add(m);
        }
        when(members.findByWorkspaceId(WS)).thenReturn(ms);
        when(templates.save(any(InterviewTemplate.class))).thenAnswer(inv -> inv.getArgument(0));
        return new InterviewTemplateService(templates, members, audit, props,
            Clock.fixed(Instant.parse("2026-06-15T00:00:00Z"), ZoneOffset.UTC));
    }

    private static TemplateRequest valid() {
        return new TemplateRequest("Phone Screen", 60, 15, 15, 15, 2,
            List.of("m1"), List.of(), List.of(), List.of(), null, null);
    }

    private void expectInvalid(TemplateRequest req, String expectedField, InterviewTemplateService svc) {
        assertThatThrownBy(() -> svc.create(WS, "actor", req))
            .isInstanceOf(InvalidTemplateException.class)
            .satisfies(e -> assertThat(((InvalidTemplateException) e).getFields()).containsKey(expectedField));
        verify(templates, never()).save(any()); // nothing persisted
    }

    @Test
    void validRequest_createsActiveTemplate() {
        InterviewTemplateService svc = service(new InterviewTemplateProperties(), "m1");
        var resp = svc.create(WS, "actor", valid());
        assertThat(resp.status()).isEqualTo("ACTIVE");
        assertThat(resp.durationMinutes()).isEqualTo(60);
        verify(audit).record(any(), any(), any(), any(), any());
    }

    @Test
    void invalidFields_areEachRefusedWithNoPersist() {
        InterviewTemplateProperties props = new InterviewTemplateProperties();
        InterviewTemplateService svc = service(props, "m1", "m2", "m3");

        expectInvalid(rebuild(valid()).duration(0).build(), "durationMinutes", svc);
        expectInvalid(rebuild(valid()).cadence(120).build(), "slotCadenceMinutes", svc); // > duration
        expectInvalid(rebuild(valid()).cadence(0).build(), "slotCadenceMinutes", svc); // < 1
        expectInvalid(rebuild(valid()).bufferBefore(-1).build(), "bufferBeforeMinutes", svc);
        expectInvalid(rebuild(valid()).bufferAfter(-1).build(), "bufferAfterMinutes", svc);
        expectInvalid(rebuild(valid()).cap(0).build(), "dailyCapPerInterviewer", svc);
        expectInvalid(rebuild(valid()).required(List.of()).build(), "participants", svc); // no required & no pool
        expectInvalid(rebuild(valid()).required(List.of()).pools(List.of(new PoolRuleDto(List.of("m1", "m2"), 3))).build(),
            "pools[0].n", svc); // n > pool size
        expectInvalid(rebuild(valid()).blackouts(List.of(new BlackoutDto(
            Instant.parse("2026-07-08T00:00:00Z"), Instant.parse("2026-07-01T00:00:00Z")))).build(),
            "blackouts[0]", svc); // end <= start
        expectInvalid(rebuild(valid()).required(List.of("foreign")).build(), "members", svc); // not in workspace
        expectInvalid(rebuild(valid()).pools(List.of(new PoolRuleDto(List.of("m1", "m2"), 1))).build(),
            "members", svc); // m1 both required and in a pool
        expectInvalid(rebuild(valid()).required(List.of()).pools(List.of(
            new PoolRuleDto(List.of("m1", "m2"), 1), new PoolRuleDto(List.of("m1", "m3"), 1))).build(),
            "members", svc); // m1 in two pools
        expectInvalid(rebuild(valid()).timeZone("Not/AZone").build(), "timeZoneOverride", svc);
        expectInvalid(rebuild(valid()).workingHours(new WorkingHoursDto(LocalTime.of(17, 0), LocalTime.of(9, 0))).build(),
            "workingHoursOverride", svc); // end before start
    }

    @Test
    void overTemplateCaps_areRefused() {
        InterviewTemplateProperties tiny = new InterviewTemplateProperties();
        tiny.setMaxMembers(1);
        InterviewTemplateService svc = service(tiny, "m1", "m2");
        expectInvalid(rebuild(valid()).required(List.of("m1", "m2")).build(), "members", svc);
    }

    // --- tiny fluent rebuilder for TemplateRequest ---
    private static Builder rebuild(TemplateRequest r) { return new Builder(r); }

    private static final class Builder {
        String name; Integer duration, cadence, bb, ba, cap; List<String> required, optional;
        List<PoolRuleDto> pools; List<BlackoutDto> blackouts; String tz; WorkingHoursDto wh;
        Builder(TemplateRequest r) {
            name = r.name(); duration = r.durationMinutes(); cadence = r.slotCadenceMinutes();
            bb = r.bufferBeforeMinutes(); ba = r.bufferAfterMinutes(); cap = r.dailyCapPerInterviewer();
            required = r.requiredMemberIds(); optional = r.optionalMemberIds(); pools = r.pools();
            blackouts = r.blackouts(); tz = r.timeZoneOverride(); wh = r.workingHoursOverride();
        }
        Builder duration(int v) { duration = v; return this; }
        Builder cadence(int v) { cadence = v; return this; }
        Builder bufferBefore(int v) { bb = v; return this; }
        Builder bufferAfter(int v) { ba = v; return this; }
        Builder cap(int v) { cap = v; return this; }
        Builder required(List<String> v) { required = v; return this; }
        Builder pools(List<PoolRuleDto> v) { pools = v; return this; }
        Builder blackouts(List<BlackoutDto> v) { blackouts = v; return this; }
        Builder timeZone(String v) { tz = v; return this; }
        Builder workingHours(WorkingHoursDto v) { wh = v; return this; }
        TemplateRequest build() {
            return new TemplateRequest(name, duration, cadence, bb, ba, cap, required, optional, pools, blackouts, tz, wh);
        }
    }
}
