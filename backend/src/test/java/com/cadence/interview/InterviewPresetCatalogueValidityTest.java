package com.cadence.interview;

import com.cadence.api.InterviewTemplateDtos.PoolRuleDto;
import com.cadence.api.InterviewTemplateDtos.TemplateRequest;
import com.cadence.config.InterviewTemplateProperties;
import com.cadence.domain.InterviewPresetKey;
import com.cadence.domain.InterviewTemplate;
import com.cadence.domain.Member;
import com.cadence.repository.InterviewTemplateRepository;
import com.cadence.repository.MemberRepository;
import com.cadence.service.AuthAuditService;
import com.cadence.service.InterviewTemplatePresetCatalogue;
import com.cadence.service.InterviewTemplatePresetCatalogue.Preset;
import com.cadence.service.InterviewTemplateService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Every code-shipped preset, combined with real member choices, passes the F12 service validation unchanged. */
class InterviewPresetCatalogueValidityTest {

    private static final String WS = "ws1";

    private final InterviewTemplateRepository templates = mock(InterviewTemplateRepository.class);
    private final MemberRepository members = mock(MemberRepository.class);
    private final AuthAuditService audit = mock(AuthAuditService.class);
    private final InterviewTemplatePresetCatalogue catalogue = new InterviewTemplatePresetCatalogue();

    private InterviewTemplateService service(String... memberIds) {
        List<Member> ms = new ArrayList<>();
        for (String id : memberIds) {
            Member m = new Member();
            m.setId(id);
            m.setWorkspaceId(WS);
            ms.add(m);
        }
        when(members.findByWorkspaceId(WS)).thenReturn(ms);
        when(templates.save(any(InterviewTemplate.class))).thenAnswer(inv -> inv.getArgument(0));
        return new InterviewTemplateService(templates, members, audit, new InterviewTemplateProperties(),
            Clock.fixed(Instant.parse("2026-07-26T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void catalogue_hasAllSixPresets_inGalleryOrder() {
        assertThat(catalogue.all()).extracting(Preset::key).containsExactly(
            InterviewPresetKey.PHONE_SCREEN, InterviewPresetKey.HM_INTRO, InterviewPresetKey.TECH_DEEP_DIVE,
            InterviewPresetKey.PANEL_LOOP, InterviewPresetKey.HR_CULTURE, InterviewPresetKey.FINAL_ROUND);
    }

    @Test
    void everyPreset_passesServiceValidation_onceMembersAreChosen() {
        InterviewTemplateService svc = service("m1", "m2", "m3", "m4");
        for (Preset p : catalogue.all()) {
            List<String> optional = p.optionalShadow() ? List.of("m2") : List.of();
            List<PoolRuleDto> pools = p.poolN() == null ? List.of()
                : List.of(new PoolRuleDto(List.of("m3", "m4"), p.poolN()));
            TemplateRequest req = new TemplateRequest("Preset " + p.key().name(), p.durationMinutes(),
                p.slotCadenceMinutes(), p.bufferBeforeMinutes(), p.bufferAfterMinutes(),
                p.dailyCapPerInterviewer(), List.of("m1"), optional, pools, List.of(), null, null);
            assertThat(svc.create(WS, "actor", req).status()).as(p.key().name()).isEqualTo("ACTIVE");
        }
    }

    @Test
    void everyPreset_declaresAtLeastInvitationStarter() {
        for (Preset p : catalogue.all()) {
            assertThat(p.starterEmailTypes()).as(p.key().name()).isNotEmpty();
        }
    }
}
