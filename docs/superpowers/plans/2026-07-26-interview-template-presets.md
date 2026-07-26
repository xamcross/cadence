# Interview Template Presets & Starter Emails Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a persistent "Start from a preset" gallery on the interview-templates screen (six code-shipped presets that pre-fill the existing editor) plus an opt-out post-save dialog that applies per-stage starter email variants.

**Architecture:** Two code-shipped backend catalogues (mirroring `BuiltInEmailTemplates`/`TonePresetCatalogue`), one read-only `GET /presets` endpoint on the existing interview-template controller, and one `apply-preset-starter` endpoint on the existing email-template controller reusing the F21 variant machinery. The frontend pre-fills the existing create form client-side (no create-from-preset backdoor) and shows a hand-rolled feature-local modal after a preset-based save. Spec: `docs/superpowers/specs/2026-07-26-interview-template-presets-design.md`.

**Tech Stack:** Java 21 / Spring Boot 3.3.5 / Spring Data Mongo; Angular 17.3 standalone components, signals, template-driven forms; JUnit 5 + Testcontainers (singleton `mongo:7`); Jasmine/Karma EdgeHeadless + axe-core.

## Global Constraints

- **No new runtime dependency, no new Mongo collection, no Mongock changeset** (spec §2, §4).
- **Zero-download rule:** backend tests need `$env:JAVA_HOME='C:/jdk-24.0.1'` and `$env:DOCKER_HOST='npipe:////./pipe/docker_engine'`; run `./gradlew.bat` from `backend/` (the wrapper is cached — never re-download). Frontend tests: `node_modules/.bin/ng test --watch=false` from `frontend/` (EdgeHeadless auto-resolves; never install a browser).
- **Deny-by-default:** every new handler must be covered by `@PreAuthorize` at class or method level (`RbacEndpointInventoryTest` fails the build otherwise). Both touched controllers already carry class-level `@PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")`.
- **PII:** never log or audit a template `name` or email subject/body — ids, enum `.name()` Strings, and preset keys only. Never pass a raw enum to logstash `kv(...)`.
- **Value-free validation messages:** field + rule only, never the submitted value.
- **i18n:** every user-facing frontend string carries an explicit `$localize`/`i18n` id in the `@@<feature>.<area>.<thing>` convention.
- **A11y:** every new/changed component spec includes `attachToBody` + `await axeViolations(el)` → `toEqual([])` (WCAG 2.2 AA).
- **Test hygiene:** integration tests extend the existing feature `ItBase` classes; clean collections with `mongoTemplate.remove(new Query(), Type.class)` — **never** `dropCollection`.
- **Encoding:** Java sources NUL/BOM-free; use plain ASCII in Java string literals (hyphens, no smart quotes).
- **Branch:** work on `feature/interview-template-presets` (already created, based on origin/main).
- Contract docs to keep in sync: `specs/009-interview-rule-engine/contracts/interview-template-api.md` (Task 3), `specs/010-email-template-library/contracts/email-template-api.md` (Task 4).

**Backend test command template** (PowerShell, from repo root):

```powershell
cd backend
$env:JAVA_HOME='C:/jdk-24.0.1'; $env:DOCKER_HOST='npipe:////./pipe/docker_engine'
./gradlew.bat test --tests "com.cadence.interview.InterviewPresetCatalogueValidityTest"
```

**Frontend test command template** (PowerShell, from repo root):

```powershell
cd frontend
node_modules/.bin/ng test --watch=false --include='**/interview-templates.component.spec.ts'
```

---

### Task 1: Preset key enum + interview-template preset catalogue

**Files:**
- Create: `backend/src/main/java/com/cadence/domain/InterviewPresetKey.java`
- Create: `backend/src/main/java/com/cadence/service/InterviewTemplatePresetCatalogue.java`
- Test: `backend/src/test/java/com/cadence/interview/InterviewPresetCatalogueValidityTest.java`

**Interfaces:**
- Consumes: `InterviewTemplateService`, `InterviewTemplateDtos.TemplateRequest` (positional record: `name, durationMinutes, slotCadenceMinutes, bufferBeforeMinutes, bufferAfterMinutes, dailyCapPerInterviewer, requiredMemberIds, optionalMemberIds, pools, blackouts, timeZoneOverride, workingHoursOverride`), `EmailMessageType` (values incl. `INVITATION`, `CONFIRMATION`, `REMINDER_24H`).
- Produces: `enum InterviewPresetKey { PHONE_SCREEN, HM_INTRO, TECH_DEEP_DIVE, PANEL_LOOP, HR_CULTURE, FINAL_ROUND }`; `@Component InterviewTemplatePresetCatalogue` with `public List<Preset> all()` and nested `public record Preset(InterviewPresetKey key, int durationMinutes, int slotCadenceMinutes, int bufferBeforeMinutes, int bufferAfterMinutes, int dailyCapPerInterviewer, int requiredCount, boolean optionalShadow, Integer poolN, List<EmailMessageType> starterEmailTypes)`.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/cadence/interview/InterviewPresetCatalogueValidityTest.java`. It mirrors the mock wiring of the existing `InterviewTemplateValidationTest` (same package) so every preset is proven valid against the *real* F12 service validation:

```java
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
```

- [ ] **Step 2: Run the test to verify it fails**

Run (from `backend/`, env vars per Global Constraints):
`./gradlew.bat test --tests "com.cadence.interview.InterviewPresetCatalogueValidityTest"`
Expected: **compilation failure** — `InterviewPresetKey` and `InterviewTemplatePresetCatalogue` do not exist.

- [ ] **Step 3: Implement the enum and catalogue**

Create `backend/src/main/java/com/cadence/domain/InterviewPresetKey.java`:

```java
package com.cadence.domain;

/**
 * Stable keys for the code-shipped interview-template presets (the "start from a preset" gallery).
 * UI labels/descriptions are frontend $localize strings keyed by name() - never served by the API.
 */
public enum InterviewPresetKey {
    PHONE_SCREEN,
    HM_INTRO,
    TECH_DEEP_DIVE,
    PANEL_LOOP,
    HR_CULTURE,
    FINAL_ROUND
}
```

Create `backend/src/main/java/com/cadence/service/InterviewTemplatePresetCatalogue.java`:

```java
package com.cadence.service;

import com.cadence.domain.EmailMessageType;
import com.cadence.domain.InterviewPresetKey;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Code-shipped interview-template presets. Static, workspace-free structural values only - member ids
 * are always chosen by the recruiter at apply time, and applying goes through the normal create path
 * (full validation; no backdoor). Mirrors the BuiltInEmailTemplates/TonePresetCatalogue fail-fast
 * pattern so preset updates ship with releases and a bad constant cannot boot.
 */
@Component
public class InterviewTemplatePresetCatalogue {

    /** poolN null = no pool suggested; optionalShadow = suggest one optional (shadow) seat. */
    public record Preset(InterviewPresetKey key, int durationMinutes, int slotCadenceMinutes,
                         int bufferBeforeMinutes, int bufferAfterMinutes, int dailyCapPerInterviewer,
                         int requiredCount, boolean optionalShadow, Integer poolN,
                         List<EmailMessageType> starterEmailTypes) {}

    private static final List<Preset> PRESETS = List.of(
        new Preset(InterviewPresetKey.PHONE_SCREEN, 30, 15, 0, 5, 4, 1, false, null,
            List.of(EmailMessageType.INVITATION, EmailMessageType.CONFIRMATION)),
        new Preset(InterviewPresetKey.HM_INTRO, 45, 15, 5, 5, 3, 1, false, null,
            List.of(EmailMessageType.INVITATION)),
        new Preset(InterviewPresetKey.TECH_DEEP_DIVE, 60, 30, 10, 10, 2, 1, true, null,
            List.of(EmailMessageType.INVITATION, EmailMessageType.CONFIRMATION, EmailMessageType.REMINDER_24H)),
        new Preset(InterviewPresetKey.PANEL_LOOP, 90, 30, 15, 15, 1, 1, false, 2,
            List.of(EmailMessageType.INVITATION, EmailMessageType.CONFIRMATION, EmailMessageType.REMINDER_24H)),
        new Preset(InterviewPresetKey.HR_CULTURE, 45, 15, 5, 5, 3, 1, false, null,
            List.of(EmailMessageType.INVITATION)),
        new Preset(InterviewPresetKey.FINAL_ROUND, 60, 30, 10, 10, 2, 1, false, 1,
            List.of(EmailMessageType.INVITATION, EmailMessageType.CONFIRMATION)));

    @PostConstruct
    void verifyComplete() {
        for (Preset p : PRESETS) {
            if (p.slotCadenceMinutes() < 1 || p.slotCadenceMinutes() > p.durationMinutes()
                || p.bufferBeforeMinutes() < 0 || p.bufferAfterMinutes() < 0
                || p.dailyCapPerInterviewer() < 1 || p.requiredCount() < 1
                || (p.poolN() != null && p.poolN() < 1)
                || p.starterEmailTypes().isEmpty()) {
                throw new IllegalStateException("Invalid interview preset " + p.key().name());
            }
        }
    }

    public List<Preset> all() {
        return PRESETS;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

`./gradlew.bat test --tests "com.cadence.interview.InterviewPresetCatalogueValidityTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/java/com/cadence/domain/InterviewPresetKey.java backend/src/main/java/com/cadence/service/InterviewTemplatePresetCatalogue.java backend/src/test/java/com/cadence/interview/InterviewPresetCatalogueValidityTest.java
git commit -m "feat(presets): code-shipped interview-template preset catalogue"
```

---

### Task 2: Preset email starter catalogue

**Files:**
- Create: `backend/src/main/java/com/cadence/service/PresetEmailStarterCatalogue.java`
- Test: `backend/src/test/java/com/cadence/emailtemplate/PresetStarterCompletenessTest.java`

**Interfaces:**
- Consumes: `InterviewTemplatePresetCatalogue.all()` (Task 1), `BuiltInEmailTemplates` (`public record Content(String subject, String body)`, `public Content forType(EmailMessageType)` — builds its map in the constructor, so it is directly constructible in unit tests), `MergeTokenCatalogue` (no-arg constructor; `public Map<String, String> validateTokens(EmailMessageType type, String subject, String body)` returns an empty map when valid).
- Produces: `@Component PresetEmailStarterCatalogue` with constructor `PresetEmailStarterCatalogue(BuiltInEmailTemplates builtins)` and `public BuiltInEmailTemplates.Content forPresetAndType(InterviewPresetKey preset, EmailMessageType type)` returning **null** for an undeclared pair.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/cadence/emailtemplate/PresetStarterCompletenessTest.java` (pure unit test, no Spring — mirrors `BuiltInTemplateCompletenessTest`'s role):

```java
package com.cadence.emailtemplate;

import com.cadence.domain.EmailMessageType;
import com.cadence.service.BuiltInEmailTemplates;
import com.cadence.service.BuiltInEmailTemplates.Content;
import com.cadence.service.InterviewTemplatePresetCatalogue;
import com.cadence.service.InterviewTemplatePresetCatalogue.Preset;
import com.cadence.service.MergeTokenCatalogue;
import com.cadence.service.PresetEmailStarterCatalogue;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every (preset, type) pair declared by the interview catalogue has a starter that is non-empty,
 * token-valid for its message type, and keeps the built-in privacy footer; no starter exists for an
 * undeclared pair. Starters derive from the built-in of the same type + a token-free paragraph, so
 * these properties hold by construction - this test keeps them from regressing.
 */
class PresetStarterCompletenessTest {

    private static final String PRIVACY = "View our Privacy Notice:";

    private final BuiltInEmailTemplates builtins = new BuiltInEmailTemplates();
    private final PresetEmailStarterCatalogue starters = new PresetEmailStarterCatalogue(builtins);
    private final InterviewTemplatePresetCatalogue presets = new InterviewTemplatePresetCatalogue();
    private final MergeTokenCatalogue tokens = new MergeTokenCatalogue();

    @Test
    void everyDeclaredPair_hasTokenValidStarter_keepingThePrivacyFooter() {
        for (Preset p : presets.all()) {
            for (EmailMessageType type : p.starterEmailTypes()) {
                Content c = starters.forPresetAndType(p.key(), type);
                assertThat(c).as(p.key() + "/" + type).isNotNull();
                assertThat(c.subject()).as(p.key() + "/" + type).isNotBlank();
                assertThat(c.body()).as(p.key() + "/" + type).isNotBlank();
                if (builtins.forType(type).body().contains(PRIVACY)) {
                    assertThat(c.body()).as("privacy footer " + p.key() + "/" + type).contains(PRIVACY);
                }
                assertThat(tokens.validateTokens(type, c.subject(), c.body()))
                    .as("merge tokens " + p.key() + "/" + type).isEmpty();
            }
        }
    }

    @Test
    void noStarterExists_forAnUndeclaredPair() {
        for (Preset p : presets.all()) {
            for (EmailMessageType type : EmailMessageType.values()) {
                if (!p.starterEmailTypes().contains(type)) {
                    assertThat(starters.forPresetAndType(p.key(), type)).as(p.key() + "/" + type).isNull();
                }
            }
        }
    }

    @Test
    void starterBody_containsThePresetSpecificParagraph() {
        Content c = starters.forPresetAndType(
            com.cadence.domain.InterviewPresetKey.TECH_DEEP_DIVE, EmailMessageType.INVITATION);
        assertThat(c.body()).contains("development environment");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

`./gradlew.bat test --tests "com.cadence.emailtemplate.PresetStarterCompletenessTest"`
Expected: **compilation failure** — `PresetEmailStarterCatalogue` does not exist.

- [ ] **Step 3: Implement the starter catalogue**

Create `backend/src/main/java/com/cadence/service/PresetEmailStarterCatalogue.java`. The paragraphs are deliberately **token-free** (no `{{...}}`) so token-safety is inherited from the built-in:

```java
package com.cadence.service;

import com.cadence.domain.EmailMessageType;
import com.cadence.domain.InterviewPresetKey;
import com.cadence.service.BuiltInEmailTemplates.Content;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Starter email wording per (interview preset, message type) - applied as per-stage F21 variants when
 * a template is created from a preset. Each starter derives from the built-in default of the same type
 * by inserting a token-free, preset-specific paragraph before the privacy footer, so merge-token safety
 * and the GDPR footer are inherited from BuiltInEmailTemplates by construction (the TonePresetCatalogue
 * "flavour the base" pattern). Content is never logged.
 */
@Component
public class PresetEmailStarterCatalogue {

    private static final String PRIVACY_MARKER = "\n\nView our Privacy Notice:";

    private final Map<String, Content> starters = new HashMap<>();

    public PresetEmailStarterCatalogue(BuiltInEmailTemplates builtins) {
        put(builtins, InterviewPresetKey.PHONE_SCREEN, EmailMessageType.INVITATION,
            "This is a short introductory phone screen - no preparation needed beyond a quiet spot and a good connection.");
        put(builtins, InterviewPresetKey.PHONE_SCREEN, EmailMessageType.CONFIRMATION,
            "This is a short introductory call - we will keep it focused and on time.");

        put(builtins, InterviewPresetKey.HM_INTRO, EmailMessageType.INVITATION,
            "This conversation with the hiring manager focuses on the role, the team, and your experience - no technical preparation needed.");

        put(builtins, InterviewPresetKey.TECH_DEEP_DIVE, EmailMessageType.INVITATION,
            "This technical session includes hands-on problem solving. Please be ready to share your screen and have your preferred development environment set up.");
        put(builtins, InterviewPresetKey.TECH_DEEP_DIVE, EmailMessageType.CONFIRMATION,
            "Please have your development environment ready - the session includes hands-on coding with screen sharing.");
        put(builtins, InterviewPresetKey.TECH_DEEP_DIVE, EmailMessageType.REMINDER_24H,
            "A quick reminder to have your development environment set up and screen sharing tested before the session.");

        put(builtins, InterviewPresetKey.PANEL_LOOP, EmailMessageType.INVITATION,
            "You will meet several interviewers in one longer session covering different topic areas. Short breaks are included.");
        put(builtins, InterviewPresetKey.PANEL_LOOP, EmailMessageType.CONFIRMATION,
            "Your panel session brings together several interviewers - the agenda is covered at the start.");
        put(builtins, InterviewPresetKey.PANEL_LOOP, EmailMessageType.REMINDER_24H,
            "A reminder about your panel session - you will meet several interviewers, with short breaks included.");

        put(builtins, InterviewPresetKey.HR_CULTURE, EmailMessageType.INVITATION,
            "This conversation focuses on ways of working, values, and what you are looking for - no preparation needed.");

        put(builtins, InterviewPresetKey.FINAL_ROUND, EmailMessageType.INVITATION,
            "This is the final conversation in the process - a chance to close remaining questions on both sides.");
        put(builtins, InterviewPresetKey.FINAL_ROUND, EmailMessageType.CONFIRMATION,
            "You are confirmed for the final round - we will cover any remaining questions on both sides.");
    }

    private void put(BuiltInEmailTemplates builtins, InterviewPresetKey preset, EmailMessageType type,
                     String paragraph) {
        starters.put(key(preset, type), withParagraph(builtins.forType(type), paragraph));
    }

    private static String key(InterviewPresetKey preset, EmailMessageType type) {
        return preset.name() + "|" + type.name();
    }

    /** Insert the paragraph before the privacy footer (or append if the built-in has no footer). */
    private static Content withParagraph(Content base, String paragraph) {
        int i = base.body().lastIndexOf(PRIVACY_MARKER);
        String body = i >= 0
            ? base.body().substring(0, i) + "\n\n" + paragraph + base.body().substring(i)
            : base.body() + "\n\n" + paragraph;
        return new Content(base.subject(), body);
    }

    @PostConstruct
    void verifyComplete() {
        for (Map.Entry<String, Content> e : starters.entrySet()) {
            Content c = e.getValue();
            if (c == null || c.subject() == null || c.subject().isBlank()
                || c.body() == null || c.body().isBlank()) {
                throw new IllegalStateException("Missing or empty preset starter for " + e.getKey());
            }
        }
    }

    /** null when the preset declares no starter for this type (the service maps that to a 400). */
    public Content forPresetAndType(InterviewPresetKey preset, EmailMessageType type) {
        return starters.get(key(preset, type));
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

`./gradlew.bat test --tests "com.cadence.emailtemplate.PresetStarterCompletenessTest"`
Expected: PASS (3 tests). If `validateTokens` fails, a paragraph accidentally contains `{{` — fix the paragraph, not the test.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/java/com/cadence/service/PresetEmailStarterCatalogue.java backend/src/test/java/com/cadence/emailtemplate/PresetStarterCompletenessTest.java
git commit -m "feat(presets): starter email wording catalogue derived from built-ins"
```

---

### Task 3: `GET /api/internal/interview-templates/presets` endpoint

**Files:**
- Modify: `backend/src/main/java/com/cadence/api/InterviewTemplateDtos.java` (append two records before the closing brace)
- Modify: `backend/src/main/java/com/cadence/api/InterviewTemplateController.java` (new dependency + one handler)
- Modify: `backend/src/test/java/com/cadence/interview/InterviewTemplateContractTest.java` (one line in the RBAC loop)
- Modify: `specs/009-interview-rule-engine/contracts/interview-template-api.md` (document the endpoint)
- Test: `backend/src/test/java/com/cadence/interview/InterviewTemplatePresetsEndpointTest.java`

**Interfaces:**
- Consumes: `InterviewTemplatePresetCatalogue.all()` (Task 1).
- Produces: `GET /api/internal/interview-templates/presets` → 200 `{"presets":[{"key":"PHONE_SCREEN","durationMinutes":30,"slotCadenceMinutes":15,"bufferBeforeMinutes":0,"bufferAfterMinutes":5,"dailyCapPerInterviewer":4,"requiredCount":1,"optionalShadow":false,"poolN":null,"starterEmailTypes":["INVITATION","CONFIRMATION"]}, ...]}` (6 entries, catalogue order). ADMIN/RECRUITER only (class-level gate). The frontend (Task 6) consumes this shape verbatim.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/cadence/interview/InterviewTemplatePresetsEndpointTest.java`:

```java
package com.cadence.interview;

import com.cadence.domain.Role;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The preset gallery read: static catalogue, role-gated, literal route not shadowed by GET /{id}. */
class InterviewTemplatePresetsEndpointTest extends InterviewItBase {

    @Test
    void recruiter_getsAllSixPresets_withStructuralValues() throws Exception {
        Cookie rec = cookie(member("rec@x.com", Role.RECRUITER));
        mvc.perform(get("/api/internal/interview-templates/presets").cookie(rec))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.presets.length()").value(6))
            .andExpect(jsonPath("$.presets[0].key").value("PHONE_SCREEN"))
            .andExpect(jsonPath("$.presets[0].durationMinutes").value(30))
            .andExpect(jsonPath("$.presets[3].key").value("PANEL_LOOP"))
            .andExpect(jsonPath("$.presets[3].poolN").value(2))
            .andExpect(jsonPath("$.presets[2].optionalShadow").value(true))
            .andExpect(jsonPath("$.presets[2].starterEmailTypes[2]").value("REMINDER_24H"));
    }

    @Test
    void literalPresetsRoute_doesNotShadowGetById() throws Exception {
        Cookie rec = cookie(member("rec2@x.com", Role.RECRUITER));
        mvc.perform(get("/api/internal/interview-templates/000000000000000000000000").cookie(rec))
            .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

`./gradlew.bat test --tests "com.cadence.interview.InterviewTemplatePresetsEndpointTest"`
Expected: FAIL — first test gets **404** (no `/presets` mapping; the request falls into `GET /{id}` and the id lookup misses).

- [ ] **Step 3: Add the DTOs**

In `backend/src/main/java/com/cadence/api/InterviewTemplateDtos.java`, append inside the class (after `ListResponse`):

```java
    /** One code-shipped preset for the "start from a preset" gallery - static values, no workspace state. */
    public record PresetDto(String key, int durationMinutes, int slotCadenceMinutes, int bufferBeforeMinutes,
                            int bufferAfterMinutes, int dailyCapPerInterviewer, int requiredCount,
                            boolean optionalShadow, Integer poolN, java.util.List<String> starterEmailTypes) {

        public static PresetDto from(com.cadence.service.InterviewTemplatePresetCatalogue.Preset p) {
            return new PresetDto(p.key().name(), p.durationMinutes(), p.slotCadenceMinutes(),
                p.bufferBeforeMinutes(), p.bufferAfterMinutes(), p.dailyCapPerInterviewer(),
                p.requiredCount(), p.optionalShadow(), p.poolN(),
                p.starterEmailTypes().stream().map(Enum::name).toList());
        }
    }

    public record PresetsResponse(java.util.List<PresetDto> presets) {}
```

(Use plain `List` + imports if the file already imports `java.util.List` — match the file's existing import style.)

- [ ] **Step 4: Add the handler**

In `backend/src/main/java/com/cadence/api/InterviewTemplateController.java`: add a `private final InterviewTemplatePresetCatalogue presetCatalogue;` field, add the parameter `InterviewTemplatePresetCatalogue presetCatalogue` to the constructor (assign it), and add the handler after the `list` method:

```java
    /**
     * Code-shipped preset gallery (spec 2026-07-26). Static catalogue, no workspace state, covered by
     * the class-level ADMIN/RECRUITER gate. The literal segment deterministically beats GET /{id}
     * under PathPattern specificity.
     */
    @GetMapping("/presets")
    public ResponseEntity<InterviewTemplateDtos.PresetsResponse> presets() {
        return ResponseEntity.ok(new InterviewTemplateDtos.PresetsResponse(
            presetCatalogue.all().stream().map(InterviewTemplateDtos.PresetDto::from).toList()));
    }
```

(Match the controller's existing import style — it likely imports the DTO types directly, e.g. `import com.cadence.api.InterviewTemplateDtos.PresetsResponse;` style or static nested references; follow whatever `TemplateResponse`/`ListResponse` do.)

- [ ] **Step 5: Run the test to verify it passes**

`./gradlew.bat test --tests "com.cadence.interview.InterviewTemplatePresetsEndpointTest"`
Expected: PASS (2 tests + 2 inherited base tests).

- [ ] **Step 6: Extend the RBAC matrix test**

In `backend/src/test/java/com/cadence/interview/InterviewTemplateContractTest.java`, method `nonPermittedRoles_areForbiddenOnEverySurface`, add one line alongside the existing `expectForbidden(get(...))` calls:

```java
            expectForbidden(get("/api/internal/interview-templates/presets").cookie(c));
```

Run: `./gradlew.bat test --tests "com.cadence.interview.InterviewTemplateContractTest"`
Expected: PASS.

- [ ] **Step 7: Update the contract doc**

In `specs/009-interview-rule-engine/contracts/interview-template-api.md`: in section A add:

```markdown
### `GET /api/internal/interview-templates/presets` — code-shipped preset gallery (2026-07-26 spec)

- **200**: `{ "presets": [PresetDto, ...] }` — six static presets (`PHONE_SCREEN`, `HM_INTRO`, `TECH_DEEP_DIVE`, `PANEL_LOOP`, `HR_CULTURE`, `FINAL_ROUND`) with structural values, panel hints (`requiredCount`, `optionalShadow`, `poolN`), and `starterEmailTypes`. No workspace state; applying a preset is client-side pre-fill through the normal create path.
- **Roles**: Admin, Recruiter (class-level gate). 401 unauthenticated / 403 other roles.
```

and add a `GET /presets` row to the section D RBAC matrix table matching the existing row format (Admin ✓, Recruiter ✓, others 403).

- [ ] **Step 8: Commit**

```powershell
git add backend/src/main/java/com/cadence/api/InterviewTemplateDtos.java backend/src/main/java/com/cadence/api/InterviewTemplateController.java backend/src/test/java/com/cadence/interview/InterviewTemplatePresetsEndpointTest.java backend/src/test/java/com/cadence/interview/InterviewTemplateContractTest.java specs/009-interview-rule-engine/contracts/interview-template-api.md
git commit -m "feat(presets): GET /interview-templates/presets gallery endpoint"
```

---

### Task 4: `POST /api/internal/email-templates/{messageType}/apply-preset-starter`

**Files:**
- Modify: `backend/src/main/java/com/cadence/api/EmailTemplateDtos.java` (one record)
- Modify: `backend/src/main/java/com/cadence/service/EmailTemplateService.java` (new dependency + one public method + one private helper)
- Modify: `backend/src/main/java/com/cadence/api/EmailTemplateController.java` (one handler)
- Modify: `specs/010-email-template-library/contracts/email-template-api.md` (document the endpoint)
- Test: `backend/src/test/java/com/cadence/emailtemplate/EmailTemplatePresetStarterTest.java`

**Interfaces:**
- Consumes: `PresetEmailStarterCatalogue.forPresetAndType(...)` (Task 2); existing private machinery of `EmailTemplateService`: `normalize`, `lockedEditGuard`, `validateStage`, `enforceVariantCap`, `versionCheck`, `persist`, `auditChange`, `toResponse`, `resolveForRender`.
- Produces: `POST /api/internal/email-templates/{messageType}/apply-preset-starter` with body `{stageKey, presetKey, expectedVersion}` → 200 `TemplateResponse` (variant materialised/overwritten). Errors: 400 `invalid_template` (BASE/blank stageKey, unknown presetKey, undeclared pair), 403 `template_locked`, 404 `not_found` (invalid messageType, foreign/unknown stageKey), 409 `stale_template`. Audit outcome: `<TYPE>/<stageKey>/preset_starter_apply` via existing `EMAIL_TEMPLATE_EDITED`. The frontend (Task 7) calls this once per checked type with `expectedVersion: null`.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/cadence/emailtemplate/EmailTemplatePresetStarterTest.java`. Reuse the base-class helpers (`member`, `cookie`, `seedStage`) and copy the private `count(AuthEventType, String)` audit helper **verbatim from `EmailTemplateAuditTest.java` in the same package** (it queries the audit collection by event type + outcome):

```java
package com.cadence.emailtemplate;

import com.cadence.domain.AuthEventType;
import com.cadence.domain.Role;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** apply-preset-starter mirrors apply-tone: guard ordering, oracle-free 404s, version semantics, audit kind. */
class EmailTemplatePresetStarterTest extends EmailTemplateItBase {

    private static final String URL = "/api/internal/email-templates/INVITATION/apply-preset-starter";

    // Copy the private `count(AuthEventType, String)` helper verbatim from EmailTemplateAuditTest here.

    @Test
    void materialisesVariant_fromPresetStarter_atVersionZero() throws Exception {
        Cookie rec = cookie(member("rec@x.com", Role.RECRUITER));
        seedStage(WS, "stage1");
        mvc.perform(post(URL).cookie(rec).with(csrf()).contentType("application/json")
                .content("{\"stageKey\":\"stage1\",\"presetKey\":\"TECH_DEEP_DIVE\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.stageKey").value("stage1"))
            .andExpect(jsonPath("$.source").value("OVERRIDE"))
            .andExpect(jsonPath("$.version").value(0))
            .andExpect(jsonPath("$.body").value(org.hamcrest.Matchers.containsString("development environment")));
        assertThat(count(AuthEventType.EMAIL_TEMPLATE_EDITED, "INVITATION/stage1/preset_starter_apply")).isEqualTo(1);
    }

    @Test
    void baseStageKey_isRefused400_valueFree() throws Exception {
        Cookie rec = cookie(member("rec2@x.com", Role.RECRUITER));
        mvc.perform(post(URL).cookie(rec).with(csrf()).contentType("application/json")
                .content("{\"stageKey\":\"BASE\",\"presetKey\":\"PHONE_SCREEN\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_template"))
            .andExpect(jsonPath("$.fields.stageKey").exists());
    }

    @Test
    void unknownPresetKey_isRefused400() throws Exception {
        Cookie rec = cookie(member("rec3@x.com", Role.RECRUITER));
        seedStage(WS, "stage1");
        mvc.perform(post(URL).cookie(rec).with(csrf()).contentType("application/json")
                .content("{\"stageKey\":\"stage1\",\"presetKey\":\"NOPE\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fields.presetKey").exists());
    }

    @Test
    void undeclaredTypeForPreset_isRefused400() throws Exception {
        // HM_INTRO declares INVITATION only -> REMINDER_24H has no starter.
        Cookie rec = cookie(member("rec4@x.com", Role.RECRUITER));
        seedStage(WS, "stage1");
        mvc.perform(post("/api/internal/email-templates/REMINDER_24H/apply-preset-starter")
                .cookie(rec).with(csrf()).contentType("application/json")
                .content("{\"stageKey\":\"stage1\",\"presetKey\":\"HM_INTRO\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fields.messageType").exists());
    }

    @Test
    void foreignStageKey_isOracleFree404() throws Exception {
        Cookie rec = cookie(member("rec5@x.com", Role.RECRUITER));
        seedStage("ws2", "foreignStage");
        mvc.perform(post(URL).cookie(rec).with(csrf()).contentType("application/json")
                .content("{\"stageKey\":\"foreignStage\",\"presetKey\":\"PHONE_SCREEN\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void reapply_overwritesWithVersionBump_andStaleExpectedVersionIs409() throws Exception {
        Cookie rec = cookie(member("rec6@x.com", Role.RECRUITER));
        seedStage(WS, "stage1");
        mvc.perform(post(URL).cookie(rec).with(csrf()).contentType("application/json")
                .content("{\"stageKey\":\"stage1\",\"presetKey\":\"PHONE_SCREEN\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(0));
        mvc.perform(post(URL).cookie(rec).with(csrf()).contentType("application/json")
                .content("{\"stageKey\":\"stage1\",\"presetKey\":\"TECH_DEEP_DIVE\",\"expectedVersion\":0}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        mvc.perform(post(URL).cookie(rec).with(csrf()).contentType("application/json")
                .content("{\"stageKey\":\"stage1\",\"presetKey\":\"PHONE_SCREEN\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("stale_template"));
    }

    @Test
    void lockedVariant_isRefused403ForRecruiter() throws Exception {
        Cookie admin = cookie(member("admin@x.com", Role.ADMIN));
        Cookie rec = cookie(member("rec7@x.com", Role.RECRUITER));
        seedStage(WS, "stage1");
        mvc.perform(post("/api/internal/email-templates/INVITATION/lock").cookie(admin).with(csrf())
                .contentType("application/json").content("{\"stageKey\":\"stage1\"}"))
            .andExpect(status().isOk());
        mvc.perform(post(URL).cookie(rec).with(csrf()).contentType("application/json")
                .content("{\"stageKey\":\"stage1\",\"presetKey\":\"PHONE_SCREEN\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("template_locked"));
    }

    @Test
    void nonPermittedRoles_areForbidden() throws Exception {
        seedStage(WS, "stage1");
        for (Role role : new Role[]{Role.HIRING_MANAGER, Role.INTERVIEWER, Role.READ_ONLY}) {
            Cookie c = cookie(member(role.name().toLowerCase() + "@x.com", role));
            mvc.perform(post(URL).cookie(c).with(csrf()).contentType("application/json")
                    .content("{\"stageKey\":\"stage1\",\"presetKey\":\"PHONE_SCREEN\"}"))
                .andExpect(status().isForbidden());
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

`./gradlew.bat test --tests "com.cadence.emailtemplate.EmailTemplatePresetStarterTest"`
Expected: FAIL — the apply-preset-starter requests return **404/405** (no mapping exists).

- [ ] **Step 3: Add the request DTO**

In `backend/src/main/java/com/cadence/api/EmailTemplateDtos.java`, next to `ApplyToneRequest`:

```java
    public record ApplyPresetStarterRequest(String stageKey, String presetKey, Long expectedVersion) {}
```

- [ ] **Step 4: Add the service method**

In `backend/src/main/java/com/cadence/service/EmailTemplateService.java`:

1. Add constructor dependency `PresetEmailStarterCatalogue presetStarters` (new field + constructor parameter, alongside the existing `TonePresetCatalogue tones`). Then run `Grep` for `new EmailTemplateService(` across `backend/src/test` — update any direct construction with the extra argument (as of exploration there are none, but verify).
2. Add the public method directly after `applyTone`, mirroring its guard ordering exactly (parse/lookup 400s first — the `parseTone` precedent — then lock 403 → stage 404 → variant cap 400 → version 409):

```java
    /**
     * Materialise a per-stage variant from the preset starter catalogue (spec 2026-07-26). BASE is
     * refused - a starter is inherently a stage variant. Same guard ordering and version/audit
     * semantics as applyTone; audit kind "preset_starter_apply".
     */
    public TemplateResponse applyPresetStarter(String workspaceId, String actorMemberId, Role role,
                                               EmailMessageType type, ApplyPresetStarterRequest req) {
        String sk = normalize(req.stageKey());
        if (EmailTemplate.BASE.equals(sk)) {
            Map<String, String> err = new LinkedHashMap<>();
            err.put("stageKey", "A preset starter applies to an interview stage, not the base template.");
            throw new EmailTemplateExceptions.InvalidTemplateException(err);
        }
        InterviewPresetKey preset = parsePresetKey(req.presetKey());
        Content content = presetStarters.forPresetAndType(preset, type);
        if (content == null) {
            Map<String, String> err = new LinkedHashMap<>();
            err.put("messageType", "This preset has no starter wording for this message type.");
            throw new EmailTemplateExceptions.InvalidTemplateException(err);
        }
        EmailTemplate existing = repo.findByWorkspaceIdAndMessageTypeAndStageKey(workspaceId, type, sk).orElse(null);
        lockedEditGuard(existing, role);
        validateStage(workspaceId, sk);
        if (existing == null) {
            enforceVariantCap(workspaceId, type);
        }
        versionCheck(existing, req.expectedVersion());

        persist(workspaceId, actorMemberId, type, sk, content.subject(), content.body(), existing);
        auditChange(AuthEventType.EMAIL_TEMPLATE_EDITED, workspaceId, actorMemberId, type, sk, "preset_starter_apply");
        return toResponse(resolveForRender(workspaceId, type, sk));
    }

    private InterviewPresetKey parsePresetKey(String raw) {
        try {
            return InterviewPresetKey.valueOf(raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            Map<String, String> err = new LinkedHashMap<>();
            err.put("presetKey", "Unknown preset.");
            throw new EmailTemplateExceptions.InvalidTemplateException(err);
        }
    }
```

Add imports: `com.cadence.domain.InterviewPresetKey`, `com.cadence.api.EmailTemplateDtos.ApplyPresetStarterRequest` (match the file's existing import style for DTOs).

- [ ] **Step 5: Add the controller handler**

In `backend/src/main/java/com/cadence/api/EmailTemplateController.java`, after the `applyTone` handler (inherits the class-level ADMIN/RECRUITER gate — satisfies `RbacEndpointInventoryTest` automatically):

```java
    @PostMapping("/{messageType}/apply-preset-starter")
    public ResponseEntity<TemplateResponse> applyPresetStarter(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable String messageType,
            @RequestBody ApplyPresetStarterRequest req) {
        return ResponseEntity.ok(service.applyPresetStarter(
            principal.workspaceId(), principal.memberId(), principal.role(), parseType(messageType), req));
    }
```

- [ ] **Step 6: Run the test to verify it passes**

`./gradlew.bat test --tests "com.cadence.emailtemplate.EmailTemplatePresetStarterTest"`
Expected: PASS (8 tests + 2 inherited). Also run the neighbours to catch regressions:
`./gradlew.bat test --tests "com.cadence.emailtemplate.*" --tests "com.cadence.rbac.RbcEndpointInventoryTest" 2>$null` — if the inventory-test name pattern misses, run `./gradlew.bat test --tests "com.cadence.rbac.*"`.
Expected: PASS.

- [ ] **Step 7: Update the contract doc**

In `specs/010-email-template-library/contracts/email-template-api.md`, after the apply-tone section:

```markdown
### `POST /api/internal/email-templates/{messageType}/apply-preset-starter` — apply a preset starter variant (2026-07-26 spec)

- **Body**: `{ "stageKey": "<interviewTemplateId>", "presetKey": "PHONE_SCREEN|HM_INTRO|TECH_DEEP_DIVE|PANEL_LOOP|HR_CULTURE|FINAL_ROUND", "expectedVersion": null|N }`. `stageKey` is REQUIRED to be a stage variant — `"BASE"`/blank → 400 `invalid_template` (a starter is inherently per-stage).
- **200**: `TemplateResponse` — variant materialised (version 0) or overwritten (version++) with the (preset, type) starter wording, then freely editable. Same lock (403 `template_locked`), stage (oracle-free 404), variant-cap (400) and version (409 `stale_template`) semantics as `apply-tone`; guard ordering identical.
- **400 `invalid_template`**: unknown `presetKey`, or the preset declares no starter for this `messageType` (value-free `fields`).
- **Audit**: one `EMAIL_TEMPLATE_EDITED` row, outcome `<TYPE>/<stageKey>/preset_starter_apply`. Content never audited/logged.
```

- [ ] **Step 8: Commit**

```powershell
git add backend/src/main/java/com/cadence/api/EmailTemplateDtos.java backend/src/main/java/com/cadence/service/EmailTemplateService.java backend/src/main/java/com/cadence/api/EmailTemplateController.java backend/src/test/java/com/cadence/emailtemplate/EmailTemplatePresetStarterTest.java specs/010-email-template-library/contracts/email-template-api.md
git commit -m "feat(presets): apply-preset-starter email variant endpoint"
```

---

### Task 5: Frontend — pools & optional members in the template form

The current form supports only `name/duration/cadence/buffers/cap/requiredCsv`; the server already accepts `optionalMemberIds` and `pools`, and the PANEL_LOOP / FINAL_ROUND / TECH_DEEP_DIVE presets need them. Follow the screen's existing CSV-input idiom.

**Files:**
- Modify: `frontend/src/app/features/interview-templates/interview-templates.service.ts`
- Modify: `frontend/src/app/features/interview-templates/interview-templates.component.ts`
- Test: `frontend/src/app/features/interview-templates/interview-templates.component.spec.ts`

**Interfaces:**
- Consumes: existing `TemplateRequest` (already has `optionalMemberIds?: string[]` and `pools?: PoolRule[]`), `PoolRule { memberIds: string[]; n: number }`.
- Produces: `TemplateResponse` gains `optionalMemberIds: string[]`; component gains `optionalCsv: string`, `pools: { membersCsv: string; n: number | null }[]`, `addPool(): void`, `removePool(i: number): void`; `submit()` sends `optionalMemberIds` + `pools`; `edit(t)` populates both. Task 6's `applyPreset` seeds `pools`.

- [ ] **Step 1: Write the failing tests**

Add to `interview-templates.component.spec.ts` (inside the existing `describe`, using the existing `setup` helper):

```ts
  describe('pools and optional members (preset groundwork)', () => {
    it('adds and removes pool rows', () => {
      const fixture = setup({ templates: [] });
      const c = fixture.componentInstance;
      expect(c.pools.length).toBe(0);
      c.addPool();
      expect(c.pools).toEqual([{ membersCsv: '', n: 1 }]);
      c.removePool(0);
      expect(c.pools.length).toBe(0);
    });

    it('submits optional members and pools parsed from CSV rows', () => {
      const createSpy = jasmine.createSpy('create').and.returnValue(of(template));
      const fixture = setup({ templates: [] },
        { create: createSpy as unknown as InterviewTemplatesService['create'] });
      const c = fixture.componentInstance;
      c.name = 'Panel loop';
      c.durationMinutes = 90;
      c.requiredCsv = 'm1';
      c.optionalCsv = 'm2, m3';
      c.pools = [{ membersCsv: 'm4, m5', n: 2 }, { membersCsv: '  ', n: 1 }];
      c.submit();
      expect(createSpy).toHaveBeenCalledWith(jasmine.objectContaining({
        optionalMemberIds: ['m2', 'm3'],
        pools: [{ memberIds: ['m4', 'm5'], n: 2 }]
      }));
    });

    it('edit() populates optional and pool CSV rows from the response', () => {
      const withPools = {
        ...template, optionalMemberIds: ['m9'],
        pools: [{ memberIds: ['m4', 'm5'], n: 2 }]
      };
      const fixture = setup({ templates: [withPools] });
      const c = fixture.componentInstance;
      c.edit(withPools);
      expect(c.optionalCsv).toBe('m9');
      expect(c.pools).toEqual([{ membersCsv: 'm4, m5', n: 2 }]);
    });
  });
```

Also extend the spec's `template` fixture with `optionalMemberIds: []` (the interface change makes it required).

- [ ] **Step 2: Run the tests to verify they fail**

`node_modules/.bin/ng test --watch=false --include='**/interview-templates.component.spec.ts'`
Expected: FAIL — `pools`/`addPool`/`optionalCsv` do not exist (TS compile errors count as the failing state).

- [ ] **Step 3: Implement service interface + form extension**

In `interview-templates.service.ts`, add to `TemplateResponse`:

```ts
  optionalMemberIds: string[];
```

In `interview-templates.component.ts`:

1. Form model additions (next to `requiredCsv`):

```ts
  optionalCsv = '';
  pools: { membersCsv: string; n: number | null }[] = [];
```

2. Methods (next to `resetForm`):

```ts
  addPool(): void {
    this.pools.push({ membersCsv: '', n: 1 });
  }

  removePool(i: number): void {
    this.pools.splice(i, 1);
  }

  private csvToIds(csv: string): string[] {
    return csv.split(',').map((s) => s.trim()).filter((s) => s.length > 0);
  }
```

3. In `submit()`, refactor `requiredMemberIds` to use `csvToIds(this.requiredCsv)` and extend the body:

```ts
      requiredMemberIds: this.csvToIds(this.requiredCsv),
      optionalMemberIds: this.csvToIds(this.optionalCsv),
      pools: this.pools
        .map((p) => ({ memberIds: this.csvToIds(p.membersCsv), n: Number(p.n) }))
        .filter((p) => p.memberIds.length > 0)
```

4. In `edit(t)`, add:

```ts
    this.optionalCsv = (t.optionalMemberIds ?? []).join(', ');
    this.pools = t.pools.map((p) => ({ membersCsv: p.memberIds.join(', '), n: p.n }));
```

5. In `resetForm()`, add:

```ts
    this.optionalCsv = '';
    this.pools = [];
```

6. Form markup — insert after the required-members `<label>`:

```html
        <label class="field" i18n="@@tmpl.form.optional">Optional member IDs (comma-separated)
          <input class="input" name="opt" [(ngModel)]="optionalCsv" /></label>
        @for (pool of pools; track $index) {
          <div class="pool-row">
            <label class="field" i18n="@@tmpl.form.poolMembers">Pool member IDs (comma-separated)
              <input class="input" name="pool-m-{{ $index }}" [(ngModel)]="pool.membersCsv" /></label>
            <label class="field" i18n="@@tmpl.form.poolN">Need any
              <input class="input" name="pool-n-{{ $index }}" type="number" min="1" [(ngModel)]="pool.n" /></label>
            <button type="button" class="btn btn--danger-soft btn--sm" (click)="removePool($index)"
              i18n="@@tmpl.form.poolRemove">Remove pool</button>
          </div>
        }
        <button type="button" class="btn btn--outline btn--sm" (click)="addPool()"
          i18n="@@tmpl.form.poolAdd">Add interviewer pool</button>
```

- [ ] **Step 4: Run the tests to verify they pass**

`node_modules/.bin/ng test --watch=false --include='**/interview-templates.component.spec.ts'`
Expected: PASS, including the pre-existing axe spec (the new inputs have labels).

- [ ] **Step 5: Commit**

```powershell
git add frontend/src/app/features/interview-templates/
git commit -m "feat(presets): pools + optional members in the interview-template form"
```

---

### Task 6: Frontend — preset gallery + apply-to-form

**Files:**
- Modify: `frontend/src/app/features/interview-templates/interview-templates.service.ts`
- Modify: `frontend/src/app/features/interview-templates/interview-templates.component.ts`
- Test: `frontend/src/app/features/interview-templates/interview-templates.component.spec.ts`

**Interfaces:**
- Consumes: `GET /internal/interview-templates/presets` (Task 3 shape); Task 5's form model (`pools`, `optionalCsv`).
- Produces: service `presets(): Observable<PresetList>` with `InterviewTemplatePreset { key: string; durationMinutes: number; slotCadenceMinutes: number; bufferBeforeMinutes: number; bufferAfterMinutes: number; dailyCapPerInterviewer: number; requiredCount: number; optionalShadow: boolean; poolN: number | null; starterEmailTypes: string[] }` and `PresetList { presets: InterviewTemplatePreset[] }`; component `presetList`, `presetsFailed`, `activePresetKey` signals, `loadPresets()`, `applyPreset(p)`, `presetLabels`. Task 7 reads `activePresetKey()` + `presetList()` in the save flow.

- [ ] **Step 1: Write the failing tests**

Add to the spec file. First extend the `setup` helper's service stub with a default:

```ts
      presets: () => of({ presets: [] as InterviewTemplatePreset[] }),
```

and import `InterviewTemplatePreset` from the service. Then:

```ts
  describe('preset gallery', () => {
    const panelLoop: InterviewTemplatePreset = {
      key: 'PANEL_LOOP', durationMinutes: 90, slotCadenceMinutes: 30, bufferBeforeMinutes: 15,
      bufferAfterMinutes: 15, dailyCapPerInterviewer: 1, requiredCount: 1, optionalShadow: false,
      poolN: 2, starterEmailTypes: ['INVITATION', 'CONFIRMATION', 'REMINDER_24H']
    };

    it('renders a card per preset with a localized name', () => {
      const fixture = setup({ templates: [] },
        { presets: () => of({ presets: [panelLoop] }) });
      const card = fixture.nativeElement.querySelector('.preset-card');
      expect(card).not.toBeNull();
      expect(card.textContent).toContain('Panel');
    });

    it('applying a preset pre-fills the form, seeds a pool row, and sets the banner', () => {
      const fixture = setup({ templates: [] },
        { presets: () => of({ presets: [panelLoop] }) });
      const c = fixture.componentInstance;
      c.applyPreset(panelLoop);
      expect(c.durationMinutes).toBe(90);
      expect(c.slotCadenceMinutes).toBe(30);
      expect(c.bufferBeforeMinutes).toBe(15);
      expect(c.dailyCapPerInterviewer).toBe(1);
      expect(c.pools).toEqual([{ membersCsv: '', n: 2 }]);
      expect(c.activePresetKey()).toBe('PANEL_LOOP');
      expect(c.name.length).toBeGreaterThan(0);
      fixture.detectChanges();
      expect(fixture.nativeElement.querySelector('.preset-banner')).not.toBeNull();
    });

    it('gallery load failure is non-blocking and offers a retry', () => {
      const fixture = setup({ templates: [] },
        { presets: () => throwError(() => new Error('down')) });
      const c = fixture.componentInstance;
      expect(c.presetsFailed()).toBeTrue();
      expect(fixture.nativeElement.querySelector('form')).not.toBeNull(); // blank create still works
    });

    it('has zero axe violations with the gallery rendered', async () => {
      const fixture = setup({ templates: [] },
        { presets: () => of({ presets: [panelLoop] }) });
      const violations = await axeViolations(fixture.nativeElement);
      expect(violations).withContext(violations.map((v) => v.id).join(', ')).toEqual([]);
    });
  });
```

- [ ] **Step 2: Run the tests to verify they fail**

`node_modules/.bin/ng test --watch=false --include='**/interview-templates.component.spec.ts'`
Expected: FAIL — `presets`/`InterviewTemplatePreset`/`applyPreset` do not exist.

- [ ] **Step 3: Implement service + gallery**

In `interview-templates.service.ts`, add the interfaces (after `TemplateList`) and method:

```ts
export interface InterviewTemplatePreset {
  key: string;
  durationMinutes: number;
  slotCadenceMinutes: number;
  bufferBeforeMinutes: number;
  bufferAfterMinutes: number;
  dailyCapPerInterviewer: number;
  requiredCount: number;
  optionalShadow: boolean;
  poolN: number | null;
  starterEmailTypes: string[];
}

export interface PresetList {
  presets: InterviewTemplatePreset[];
}
```

```ts
  presets(): Observable<PresetList> {
    return this.http.get<PresetList>(`${this.base}/presets`);
  }
```

In `interview-templates.component.ts`:

1. State + labels:

```ts
  presetList = signal<InterviewTemplatePreset[]>([]);
  presetsFailed = signal(false);
  activePresetKey = signal<string | null>(null);

  readonly presetLabels: Record<string, { name: string; desc: string }> = {
    PHONE_SCREEN: {
      name: $localize`:@@tmpl.preset.phoneScreen.name:Phone screen`,
      desc: $localize`:@@tmpl.preset.phoneScreen.desc:A short introductory call - one interviewer, quick turnaround.`
    },
    HM_INTRO: {
      name: $localize`:@@tmpl.preset.hmIntro.name:Hiring-manager intro`,
      desc: $localize`:@@tmpl.preset.hmIntro.desc:Role and team conversation with the hiring manager.`
    },
    TECH_DEEP_DIVE: {
      name: $localize`:@@tmpl.preset.techDeepDive.name:Technical deep-dive`,
      desc: $localize`:@@tmpl.preset.techDeepDive.desc:Hands-on technical session with screen sharing and an optional shadow.`
    },
    PANEL_LOOP: {
      name: $localize`:@@tmpl.preset.panelLoop.name:Panel / onsite loop`,
      desc: $localize`:@@tmpl.preset.panelLoop.desc:A longer session with a required host plus an interviewer pool.`
    },
    HR_CULTURE: {
      name: $localize`:@@tmpl.preset.hrCulture.name:HR / culture interview`,
      desc: $localize`:@@tmpl.preset.hrCulture.desc:Ways of working, values, and expectations.`
    },
    FINAL_ROUND: {
      name: $localize`:@@tmpl.preset.finalRound.name:Final round`,
      desc: $localize`:@@tmpl.preset.finalRound.desc:Closing conversation with a senior interviewer from a pool.`
    }
  };
```

2. Load in `ngOnInit()` (alongside the existing `load()`):

```ts
  loadPresets(): void {
    this.presetsFailed.set(false);
    this.api.presets().subscribe({
      next: (r) => this.presetList.set(r.presets),
      error: () => this.presetsFailed.set(true)
    });
  }
```

3. Apply (name pre-fill comes from the localized label; recruiter edits freely):

```ts
  applyPreset(p: InterviewTemplatePreset): void {
    this.resetForm();
    this.activePresetKey.set(p.key);
    this.name = this.presetLabels[p.key]?.name ?? p.key;
    this.durationMinutes = p.durationMinutes;
    this.slotCadenceMinutes = p.slotCadenceMinutes;
    this.bufferBeforeMinutes = p.bufferBeforeMinutes;
    this.bufferAfterMinutes = p.bufferAfterMinutes;
    this.dailyCapPerInterviewer = p.dailyCapPerInterviewer;
    this.pools = p.poolN != null ? [{ membersCsv: '', n: p.poolN }] : [];
  }
```

`resetForm()` must also do `this.activePresetKey.set(null);` (note: `applyPreset` sets it *after* calling `resetForm`, so the order above is load-bearing).

4. Gallery markup — insert a section between the list section and the form section:

```html
    <section class="presets">
      <h2 i18n="@@tmpl.presets.title">Start from a preset</h2>
      @if (presetsFailed()) {
        <p class="muted">
          <span i18n="@@tmpl.presets.loadErr">Presets could not be loaded.</span>
          <button type="button" class="btn btn--link" (click)="loadPresets()"
            i18n="@@tmpl.presets.retry">Try again</button>
        </p>
      } @else {
        <div class="preset-grid">
          @for (p of presetList(); track p.key) {
            <button type="button" class="card lift-card preset-card" (click)="applyPreset(p)">
              <span class="preset-card__name">{{ presetLabels[p.key]?.name || p.key }}</span>
              <span class="preset-card__desc muted">{{ presetLabels[p.key]?.desc || '' }}</span>
              <span class="preset-card__meta muted" i18n="@@tmpl.presets.meta">{{ p.durationMinutes }} min, max {{ p.dailyCapPerInterviewer }}/day</span>
            </button>
          }
        </div>
      }
    </section>
```

5. Banner — insert at the top of the form section (inside `<section class="form">`, above the `<h2>`):

```html
      @if (activePresetKey()) {
        <p class="alert alert--accent preset-banner">
          <span i18n="@@tmpl.form.presetBanner">Preset applied - pick your interviewers, then save.</span>
          <button type="button" class="btn btn--link" (click)="resetForm()"
            i18n="@@tmpl.form.presetClear">Clear</button>
        </p>
      }
```

6. Empty-state copy — change the existing `app-empty-state` `body` text to point at the gallery (same `@@tmpl.empty.body` id):
`"Start from a preset below, or build one from scratch with the form."`

7. Component styles — add to the `styles` array:

```css
    .preset-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(15rem, 1fr)); gap: var(--space-4); }
    .preset-card { display: flex; flex-direction: column; gap: var(--space-2); text-align: left; cursor: pointer; }
    .preset-banner { display: flex; align-items: center; gap: var(--space-2); justify-content: space-between; }
```

- [ ] **Step 4: Run the tests to verify they pass**

`node_modules/.bin/ng test --watch=false --include='**/interview-templates.component.spec.ts'`
Expected: PASS (all existing + 4 new).

- [ ] **Step 5: Commit**

```powershell
git add frontend/src/app/features/interview-templates/
git commit -m "feat(presets): start-from-preset gallery pre-fills the template form"
```

---

### Task 7: Frontend — post-save starter-email dialog

**Files:**
- Modify: `frontend/src/app/features/email-templates/email-templates.service.ts` (one method)
- Modify: `frontend/src/app/features/interview-templates/interview-templates.component.ts` (dialog + save-flow hook)
- Test: `frontend/src/app/features/interview-templates/interview-templates.component.spec.ts`

**Interfaces:**
- Consumes: Task 4's endpoint; Task 6's `activePresetKey`/`presetList`; `EmailTemplatesService` (root-provided — injectable into this component); `A11yModule` (`cdkTrapFocus`) from `@angular/cdk/a11y`; `RouterLink`.
- Produces: `EmailTemplatesService.applyPresetStarter(messageType: string, body: { stageKey: string; presetKey: string; expectedVersion: number | null }): Observable<EmailTemplate>`; component `starterPrompt` signal + `applyStarters()`, `applyStarterRow()`, `toggleStarterRow()`, `closeStarterPrompt()`.

- [ ] **Step 1: Write the failing tests**

Add to the spec (imports: `EmailTemplatesService` and `EmailTemplate` from `../email-templates/email-templates.service`, `provideRouter` from `@angular/router` — add `provideRouter([])` to the spec's `providers`):

```ts
  describe('post-save starter-email dialog', () => {
    const techDeepDive: InterviewTemplatePreset = {
      key: 'TECH_DEEP_DIVE', durationMinutes: 60, slotCadenceMinutes: 30, bufferBeforeMinutes: 10,
      bufferAfterMinutes: 10, dailyCapPerInterviewer: 2, requiredCount: 1, optionalShadow: true,
      poolN: null, starterEmailTypes: ['INVITATION', 'CONFIRMATION', 'REMINDER_24H']
    };
    const starterResponse = { messageType: 'INVITATION', stageKey: 't1', subject: 's', body: 'b',
      locked: false, version: 0, source: 'OVERRIDE', permittedTokens: [] } as EmailTemplate;

    function saveFromPreset(fixture: ReturnType<typeof setup>): void {
      const c = fixture.componentInstance;
      c.applyPreset(techDeepDive);
      c.requiredCsv = 'm1';
      c.submit();
      fixture.detectChanges();
    }

    it('opens the dialog after saving from a preset, listing the starter types checked', () => {
      const fixture = setup({ templates: [] },
        { presets: () => of({ presets: [techDeepDive] }) });
      saveFromPreset(fixture);
      const prompt = fixture.componentInstance.starterPrompt();
      expect(prompt).not.toBeNull();
      expect(prompt!.templateId).toBe('t1');
      expect(prompt!.rows.map((r) => r.type)).toEqual(['INVITATION', 'CONFIRMATION', 'REMINDER_24H']);
      expect(prompt!.rows.every((r) => r.checked)).toBeTrue();
      expect(fixture.nativeElement.querySelector('.ps-panel')).not.toBeNull();
    });

    it('does not open the dialog after a blank (non-preset) save', () => {
      const fixture = setup({ templates: [] });
      const c = fixture.componentInstance;
      c.name = 'Blank';
      c.requiredCsv = 'm1';
      c.submit();
      expect(c.starterPrompt()).toBeNull();
    });

    it('applies one starter per checked type with expectedVersion null', () => {
      const fixture = setup({ templates: [] },
        { presets: () => of({ presets: [techDeepDive] }) });
      const applySpy = spyOn(TestBed.inject(EmailTemplatesService), 'applyPresetStarter')
        .and.returnValue(of(starterResponse));
      saveFromPreset(fixture);
      fixture.componentInstance.toggleStarterRow('CONFIRMATION'); // uncheck one
      fixture.componentInstance.applyStarters();
      expect(applySpy).toHaveBeenCalledTimes(2);
      expect(applySpy).toHaveBeenCalledWith('INVITATION',
        { stageKey: 't1', presetKey: 'TECH_DEEP_DIVE', expectedVersion: null });
      expect(fixture.componentInstance.starterPrompt()!.rows
        .find((r) => r.type === 'INVITATION')!.status).toBe('done');
    });

    it('a failed apply marks the row failed and retry re-applies it', () => {
      const fixture = setup({ templates: [] },
        { presets: () => of({ presets: [techDeepDive] }) });
      const applySpy = spyOn(TestBed.inject(EmailTemplatesService), 'applyPresetStarter')
        .and.returnValue(throwError(() => new Error('boom')));
      saveFromPreset(fixture);
      const c = fixture.componentInstance;
      c.applyStarterRow(c.starterPrompt()!, 'INVITATION');
      expect(c.starterPrompt()!.rows.find((r) => r.type === 'INVITATION')!.status).toBe('failed');
      applySpy.and.returnValue(of(starterResponse));
      c.applyStarterRow(c.starterPrompt()!, 'INVITATION');
      expect(c.starterPrompt()!.rows.find((r) => r.type === 'INVITATION')!.status).toBe('done');
    });

    it('skip closes the dialog without calling the email service', () => {
      const fixture = setup({ templates: [] },
        { presets: () => of({ presets: [techDeepDive] }) });
      const applySpy = spyOn(TestBed.inject(EmailTemplatesService), 'applyPresetStarter');
      saveFromPreset(fixture);
      fixture.componentInstance.closeStarterPrompt();
      expect(fixture.componentInstance.starterPrompt()).toBeNull();
      expect(applySpy).not.toHaveBeenCalled();
    });

    it('has zero axe violations with the dialog open', async () => {
      const fixture = setup({ templates: [] },
        { presets: () => of({ presets: [techDeepDive] }) });
      saveFromPreset(fixture);
      const violations = await axeViolations(fixture.nativeElement);
      expect(violations).withContext(violations.map((v) => v.id).join(', ')).toEqual([]);
    });
  });
```

Note: `setup`'s `create` stub returns the `template` fixture whose `id` is `'t1'` — that is why `templateId` is `'t1'`.

- [ ] **Step 2: Run the tests to verify they fail**

`node_modules/.bin/ng test --watch=false --include='**/interview-templates.component.spec.ts'`
Expected: FAIL — `applyPresetStarter`, `starterPrompt`, `.ps-panel` do not exist.

- [ ] **Step 3: Add the email-service method**

In `frontend/src/app/features/email-templates/email-templates.service.ts`, next to `applyTone`:

```ts
  applyPresetStarter(messageType: string,
    body: { stageKey: string; presetKey: string; expectedVersion: number | null }): Observable<EmailTemplate> {
    return this.http.post<EmailTemplate>(`${this.base}/${messageType}/apply-preset-starter`, body);
  }
```

- [ ] **Step 4: Implement the dialog and save-flow hook**

In `interview-templates.component.ts`:

1. Imports: add `A11yModule` (`@angular/cdk/a11y`) and `RouterLink` (`@angular/router`) to the component's `imports` array; inject `private readonly emailApi = inject(EmailTemplatesService);` (import from `../email-templates/email-templates.service`).

2. Types + state (top of file, after the existing imports):

```ts
interface StarterRow {
  type: string;
  checked: boolean;
  status: 'idle' | 'applying' | 'done' | 'failed';
}

interface StarterPrompt {
  templateId: string;
  presetKey: string;
  rows: StarterRow[];
}
```

```ts
  starterPrompt = signal<StarterPrompt | null>(null);

  readonly starterTypeLabels: Record<string, string> = {
    INVITATION: $localize`:@@tmpl.starter.type.invitation:Invitation`,
    CONFIRMATION: $localize`:@@tmpl.starter.type.confirmation:Confirmation`,
    REMINDER_24H: $localize`:@@tmpl.starter.type.reminder24h:24-hour reminder`
  };
```

3. Hook into `submit()` — change the success callback signature to `next: (saved) =>` and, **before** the existing `this.resetForm();` line, capture the preset key; after the toast, open the prompt for preset-based creates:

```ts
      next: (saved) => {
        const presetKey = this.activePresetKey();
        this.saving.set(false);
        this.resetForm();
        this.load();
        this.toast.success(isEdit
          ? $localize`:@@toast.tmpl.updated:Template saved.`
          : $localize`:@@toast.tmpl.created:Template created.`);
        if (!isEdit && presetKey) {
          const types = this.presetList().find((p) => p.key === presetKey)?.starterEmailTypes ?? [];
          if (types.length > 0) {
            this.starterPrompt.set({
              templateId: saved.id,
              presetKey,
              rows: types.map((t) => ({ type: t, checked: true, status: 'idle' as const }))
            });
          }
        }
      },
```

4. Dialog methods:

```ts
  applyStarters(): void {
    const prompt = this.starterPrompt();
    if (!prompt) { return; }
    for (const row of prompt.rows) {
      if (row.checked && row.status !== 'done' && row.status !== 'applying') {
        this.applyStarterRow(prompt, row.type);
      }
    }
  }

  applyStarterRow(prompt: StarterPrompt, type: string): void {
    this.setStarterStatus(type, 'applying');
    this.emailApi.applyPresetStarter(type,
      { stageKey: prompt.templateId, presetKey: prompt.presetKey, expectedVersion: null }).subscribe({
      next: () => this.setStarterStatus(type, 'done'),
      error: () => this.setStarterStatus(type, 'failed')
    });
  }

  toggleStarterRow(type: string): void {
    this.starterPrompt.update((p) => p
      ? { ...p, rows: p.rows.map((r) => r.type === type ? { ...r, checked: !r.checked } : r) }
      : p);
  }

  closeStarterPrompt(): void {
    this.starterPrompt.set(null);
  }

  private setStarterStatus(type: string, status: StarterRow['status']): void {
    this.starterPrompt.update((p) => p
      ? { ...p, rows: p.rows.map((r) => r.type === type ? { ...r, status } : r) }
      : p);
  }
```

5. Dialog markup — append at the end of the template (the hand-rolled recipe from `ConfirmDialogComponent`: backdrop click + Escape close, `cdkTrapFocus`):

```html
    @if (starterPrompt(); as sp) {
      <div class="ps-backdrop" (click)="closeStarterPrompt()" (keydown.escape)="closeStarterPrompt()" tabindex="-1">
        <div class="ps-panel" role="dialog" aria-modal="true" aria-labelledby="starter-title"
             cdkTrapFocus [cdkTrapFocusAutoCapture]="true" (click)="$event.stopPropagation()">
          <h2 class="ps-title" id="starter-title" i18n="@@tmpl.starter.title">Add starter emails for this stage?</h2>
          <p class="ps-body" i18n="@@tmpl.starter.body">Pre-written wording for this interview type. Everything stays editable in Email templates.</p>
          <ul class="ps-list">
            @for (row of sp.rows; track row.type) {
              <li class="ps-row">
                <label>
                  <input type="checkbox" [checked]="row.checked" (change)="toggleStarterRow(row.type)"
                         [disabled]="row.status === 'applying' || row.status === 'done'" />
                  {{ starterTypeLabels[row.type] || row.type }}
                </label>
                @if (row.status === 'done') {
                  <span class="badge badge--ok" i18n="@@tmpl.starter.done">Added</span>
                }
                @if (row.status === 'failed') {
                  <button type="button" class="btn btn--outline btn--sm" (click)="applyStarterRow(sp, row.type)"
                    i18n="@@tmpl.starter.retry">Retry</button>
                }
              </li>
            }
          </ul>
          <div class="ps-actions">
            <button type="button" class="btn btn--ghost" (click)="closeStarterPrompt()"
              i18n="@@tmpl.starter.skip">Skip</button>
            <a class="btn btn--link" routerLink="/email-templates" (click)="closeStarterPrompt()"
              i18n="@@tmpl.starter.review">Review in Email templates</a>
            <button type="button" class="btn btn--primary" (click)="applyStarters()"
              i18n="@@tmpl.starter.apply">Add selected</button>
          </div>
        </div>
      </div>
    }
```

6. Styles (component `styles` array — mirror the ConfirmDialog inline values):

```css
    .ps-backdrop { position: fixed; inset: 0; background: rgb(40 33 24 / 0.45); display: flex;
      align-items: center; justify-content: center; z-index: calc(var(--z-overlay) + 10); }
    .ps-panel { background: var(--surface, #fff); border-radius: 0.75rem; padding: var(--space-5);
      width: min(30rem, calc(100% - 2rem)); }
    .ps-list { list-style: none; padding: 0; margin: var(--space-3) 0; display: flex;
      flex-direction: column; gap: var(--space-2); }
    .ps-row { display: flex; align-items: center; justify-content: space-between; gap: var(--space-2); }
    .ps-actions { display: flex; justify-content: flex-end; gap: var(--space-2); flex-wrap: wrap; }
```

(Check `styles.scss` for the actual surface variable name used by `.card` — reuse it for the panel background so dark-theme tokens, if any, hold.)

- [ ] **Step 5: Run the tests to verify they pass**

`node_modules/.bin/ng test --watch=false --include='**/interview-templates.component.spec.ts'`
Expected: PASS (all specs, including both axe specs).

- [ ] **Step 6: Commit**

```powershell
git add frontend/src/app/features/interview-templates/ frontend/src/app/features/email-templates/email-templates.service.ts
git commit -m "feat(presets): post-save starter-email dialog applies per-stage variants"
```

---

### Task 8: Full verification

**Files:** none (verification only; fix regressions where they surface).

- [ ] **Step 1: Full backend suite**

```powershell
cd backend
$env:JAVA_HOME='C:/jdk-24.0.1'; $env:DOCKER_HOST='npipe:////./pipe/docker_engine'
./gradlew.bat test
```
Expected: BUILD SUCCESSFUL, no failures (notably `RbacEndpointInventoryTest`, all `com.cadence.emailtemplate.*` and `com.cadence.interview.*`).

- [ ] **Step 2: Full frontend suite**

```powershell
cd frontend
node_modules/.bin/ng test --watch=false
```
Expected: all specs pass (~362 pre-existing + new ones), 0 failures.

- [ ] **Step 3: Fix anything that surfaced, re-run, then commit any fixes**

```powershell
git add -A
git commit -m "test(presets): full-suite fixes"
```
(Skip the commit if the working tree is clean.)

---

## Self-Review (completed at plan time)

- **Spec coverage:** §1 catalogue → Tasks 1-2; §2 endpoints/catalogues → Tasks 3-4; §2 frontend gallery/pre-fill/dialog → Tasks 5-7; §3 error handling → Task 4 tests (400/403/404/409) + Task 6 retry + Task 7 per-row failure; §4 security → class-level gates + RBAC tests + oracle-free 404 + no new deps/collections; §5 testing → validity test (T1), completeness test (T2), endpoint/RBAC tests (T3-4), component + axe specs (T5-7), full suites (T8).
- **Known deliberate scope addition:** Task 5 (pools/optional members in the form) — required for the panel presets to be applicable; follows the screen's existing CSV idiom.
- **Type consistency check:** `Preset.poolN: Integer` ↔ `PresetDto.poolN: Integer` ↔ TS `poolN: number | null`; `starterEmailTypes: List<EmailMessageType>` ↔ `List<String>` (`.name()`) ↔ TS `string[]`; `applyPresetStarter` request `{stageKey, presetKey, expectedVersion}` matches `ApplyPresetStarterRequest` and the TS body type; `forPresetAndType` returns `BuiltInEmailTemplates.Content` (null for undeclared pair) consumed in Task 4's null-check.
