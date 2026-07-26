package com.cadence.service;

import com.cadence.api.EmailTemplateDtos.ApplyPresetStarterRequest;
import com.cadence.api.EmailTemplateDtos.ApplyToneRequest;
import com.cadence.api.EmailTemplateDtos.EditRequest;
import com.cadence.api.EmailTemplateDtos.ListResponse;
import com.cadence.api.EmailTemplateDtos.LockRequest;
import com.cadence.api.EmailTemplateDtos.PreviewRequest;
import com.cadence.api.EmailTemplateDtos.ResetRequest;
import com.cadence.api.EmailTemplateDtos.TemplateResponse;
import com.cadence.api.EmailTemplateExceptions;
import com.cadence.api.RbacExceptions;
import com.cadence.config.AuthProperties;
import com.cadence.config.EmailTemplateProperties;
import com.cadence.domain.AuthEventType;
import com.cadence.domain.Candidate;
import com.cadence.domain.EmailMessageType;
import com.cadence.domain.EmailTemplate;
import com.cadence.domain.InterviewPresetKey;
import com.cadence.domain.RenderedMessage;
import com.cadence.domain.Role;
import com.cadence.domain.TonePreset;
import com.cadence.repository.CandidateRepository;
import com.cadence.repository.EmailTemplateRepository;
import com.cadence.repository.InterviewTemplateRepository;
import com.cadence.service.BuiltInEmailTemplates.Content;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Email-template management + rendering/preview (F21). Resolves variant -> base override -> built-in
 * default (D1/D2). Validates tokens via {@link MergeTokenCatalogue} (value-free messages, D4/D12).
 * All writes go through {@code repository.save(...)} so the {@code @Version} optimistic lock engages;
 * a stale write (version mismatch or a concurrent first-edit {@link DuplicateKeyException}) -> 409 (D8).
 * Audits change-kinds with ids + type/stage/kind only — NEVER subject/body/content/PII (D9). Logs only
 * ids/{@code .name()} Strings (never an enum to {@code kv} — the F01.1 logstash footgun).
 */
@Service
public class EmailTemplateService {

    private static final Logger log = LoggerFactory.getLogger(EmailTemplateService.class);
    private static final String SOURCE_BUILTIN = "BUILTIN";
    private static final String SOURCE_OVERRIDE = "OVERRIDE";

    private final EmailTemplateRepository repo;
    private final BuiltInEmailTemplates builtins;
    private final TonePresetCatalogue tones;
    private final PresetEmailStarterCatalogue presetStarters;
    private final MergeTokenCatalogue catalogue;
    private final MergeRenderer renderer;
    private final CandidateRepository candidates;
    private final InterviewTemplateRepository interviewTemplates;
    private final AuthAuditService audit;
    private final EmailTemplateProperties props;
    private final AuthProperties authProps;
    private final Clock clock;

    public EmailTemplateService(EmailTemplateRepository repo, BuiltInEmailTemplates builtins,
                                TonePresetCatalogue tones, PresetEmailStarterCatalogue presetStarters,
                                MergeTokenCatalogue catalogue, MergeRenderer renderer,
                                CandidateRepository candidates, InterviewTemplateRepository interviewTemplates,
                                AuthAuditService audit, EmailTemplateProperties props, AuthProperties authProps,
                                Clock clock) {
        this.repo = repo;
        this.builtins = builtins;
        this.tones = tones;
        this.presetStarters = presetStarters;
        this.catalogue = catalogue;
        this.renderer = renderer;
        this.candidates = candidates;
        this.interviewTemplates = interviewTemplates;
        this.audit = audit;
        this.props = props;
        this.authProps = authProps;
        this.clock = clock;
    }

    private record Resolved(EmailMessageType type, String stageKey, String subject, String body,
                            boolean locked, Long version, String source,
                            String updatedByMemberId, Instant updatedAt) {}

    // --- reads -----------------------------------------------------------------------------------

    public ListResponse list(String workspaceId, String stageKey) {
        String sk = normalize(stageKey);
        if (!EmailTemplate.BASE.equals(sk)) validateStage(workspaceId, sk);
        List<TemplateResponse> out = new java.util.ArrayList<>();
        for (EmailMessageType type : EmailMessageType.values()) {
            out.add(toResponse(resolveForRender(workspaceId, type, sk)));
        }
        return new ListResponse(out);
    }

    public TemplateResponse get(String workspaceId, EmailMessageType type, String stageKey) {
        String sk = normalize(stageKey);
        if (!EmailTemplate.BASE.equals(sk)) validateStage(workspaceId, sk);
        return toResponse(resolveForRender(workspaceId, type, sk));
    }

    // --- writes ----------------------------------------------------------------------------------

    public TemplateResponse edit(String workspaceId, String actorMemberId, Role role,
                                 EmailMessageType type, EditRequest req) {
        String sk = normalize(req.stageKey());
        boolean variant = !EmailTemplate.BASE.equals(sk);
        EmailTemplate existing = repo.findByWorkspaceIdAndMessageTypeAndStageKey(workspaceId, type, sk).orElse(null);
        lockedEditGuard(existing, role);                 // 403 wins over content/version
        if (variant) validateStage(workspaceId, sk);     // 404 for a foreign/missing stage
        if (existing == null && variant) enforceVariantCap(workspaceId, type);
        validateContent(type, req.subject(), req.body()); // 400 (bad input) before 409 (concurrency)
        versionCheck(existing, req.expectedVersion());

        boolean creating = existing == null;
        EmailTemplate saved = persist(workspaceId, actorMemberId, type, sk, req.subject(), req.body(), existing);
        String kind = creating ? (variant ? "variant_edit" : "create_override") : (variant ? "variant_edit" : "edit");
        auditChange(AuthEventType.EMAIL_TEMPLATE_EDITED, workspaceId, actorMemberId, type, sk, kind);
        log.info("email template edited {} {} {}",
            kv("messageType", type.name()), kv("stageKey", sk), kv("workspaceId", workspaceId));
        return toResponse(resolveForRender(workspaceId, type, sk));
    }

    public TemplateResponse applyTone(String workspaceId, String actorMemberId, Role role,
                                      EmailMessageType type, ApplyToneRequest req) {
        String sk = normalize(req.stageKey());
        boolean variant = !EmailTemplate.BASE.equals(sk);
        TonePreset tone = parseTone(req.tone());
        Content content = tones.forTypeAndTone(type, tone);
        EmailTemplate existing = repo.findByWorkspaceIdAndMessageTypeAndStageKey(workspaceId, type, sk).orElse(null);
        lockedEditGuard(existing, role);
        if (variant) validateStage(workspaceId, sk);
        if (existing == null && variant) enforceVariantCap(workspaceId, type);
        versionCheck(existing, req.expectedVersion());

        persist(workspaceId, actorMemberId, type, sk, content.subject(), content.body(), existing);
        auditChange(AuthEventType.EMAIL_TEMPLATE_EDITED, workspaceId, actorMemberId, type, sk, "tone_apply");
        return toResponse(resolveForRender(workspaceId, type, sk));
    }

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

    public TemplateResponse reset(String workspaceId, String actorMemberId, Role role,
                                  EmailMessageType type, ResetRequest req) {
        String sk = normalize(req.stageKey());
        EmailTemplate existing = repo.findByWorkspaceIdAndMessageTypeAndStageKey(workspaceId, type, sk).orElse(null);
        if (existing == null) {
            return toResponse(resolveForRender(workspaceId, type, sk)); // idempotent no-op (no version bump, no audit)
        }
        lockedEditGuard(existing, role);
        versionCheck(existing, req.expectedVersion());
        try {
            repo.delete(existing);
        } catch (OptimisticLockingFailureException e) {
            throw new EmailTemplateExceptions.StaleTemplateException();
        }
        auditChange(AuthEventType.EMAIL_TEMPLATE_RESET, workspaceId, actorMemberId, type, sk, "reset");
        return toResponse(resolveForRender(workspaceId, type, sk));
    }

    /** Admin-only (enforced by the controller's method-level role gate) — no locked-edit guard. */
    public TemplateResponse setLocked(String workspaceId, String actorMemberId,
                                      EmailMessageType type, LockRequest req, boolean locked) {
        String sk = normalize(req.stageKey());
        boolean variant = !EmailTemplate.BASE.equals(sk);
        if (variant) validateStage(workspaceId, sk);
        EmailTemplate existing = repo.findByWorkspaceIdAndMessageTypeAndStageKey(workspaceId, type, sk).orElse(null);
        if (existing == null && variant) enforceVariantCap(workspaceId, type); // materialising a new variant counts
        versionCheck(existing, req.expectedVersion());

        EmailTemplate target;
        if (existing == null) {
            // Materialise the override from the effective (base/built-in) content, then lock it.
            Resolved eff = resolveForRender(workspaceId, type, sk);
            target = newTemplate(workspaceId, actorMemberId, type, sk, eff.subject(), eff.body());
        } else {
            target = existing;
            target.setUpdatedByMemberId(actorMemberId);
            target.setUpdatedAt(Instant.now(clock));
        }
        target.setLocked(locked);
        saveOrStale(target);
        auditChange(locked ? AuthEventType.EMAIL_TEMPLATE_LOCKED : AuthEventType.EMAIL_TEMPLATE_UNLOCKED,
            workspaceId, actorMemberId, type, sk, locked ? "lock" : "unlock");
        return toResponse(resolveForRender(workspaceId, type, sk));
    }

    // --- preview (render) ------------------------------------------------------------------------

    public RenderedMessage preview(String workspaceId, EmailMessageType type, PreviewRequest req) {
        String sk = normalize(req.stageKey());
        if (!EmailTemplate.BASE.equals(sk)) validateStage(workspaceId, sk);

        String subject;
        String body;
        if (req.tone() != null && !req.tone().isBlank()) {
            Content content = tones.forTypeAndTone(type, parseTone(req.tone()));
            subject = content.subject();
            body = content.body();
        } else {
            Resolved eff = resolveForRender(workspaceId, type, sk);
            subject = eff.subject();
            body = eff.body();
        }

        Map<String, String> values = new HashMap<>();
        if (req.candidateId() != null && !req.candidateId().isBlank()) {
            Candidate c = candidates.findByWorkspaceIdAndId(workspaceId, req.candidateId())
                .orElseThrow(RbacExceptions.ScopedNotFoundException::new);
            values.put("candidate_name", c.getName()); // decrypted on read; NEVER logged
        }
        if (req.sampleValues() != null) values.putAll(req.sampleValues());

        return renderer.render(type, subject, body, values);
    }

    /**
     * Render a candidate message for SEND (F22, research D9). Unlike {@link #preview} the candidate is
     * REQUIRED and resolved workspace-scoped (empty -> {@link RbacExceptions.ScopedNotFoundException} ->
     * 404, oracle-free); the decrypted candidate name is merged but the decryption (PII) stays INSIDE this
     * F21 service. {@code nonPiiContext} supplies non-candidate-derived tokens (e.g. a date string) and is
     * transient — it is never persisted by the caller. Resolves variant -> base override -> built-in
     * default via {@link #resolveForRender}.
     */
    public RenderedMessage renderForSend(String workspaceId, EmailMessageType type, String stageKey,
                                         String candidateId, Map<String, String> nonPiiContext) {
        String sk = normalize(stageKey);
        if (!EmailTemplate.BASE.equals(sk)) validateStage(workspaceId, sk);
        Resolved eff = resolveForRender(workspaceId, type, sk);

        Candidate c = candidates.findByWorkspaceIdAndId(workspaceId, candidateId)
            .orElseThrow(RbacExceptions.ScopedNotFoundException::new);

        Map<String, String> values = new HashMap<>();
        if (nonPiiContext != null) values.putAll(nonPiiContext);
        values.put("candidate_name", c.getName()); // decrypted on read; NEVER logged (PII stays inside F21)
        // GDPR Art. 14 (FR-020, contract C-LINK-4): the Privacy Notice link is a CONSTANT, injected
        // centrally here (never per call-site) so it carries no candidate token or PII. spaBaseUrl is
        // absolute, yielding the http(s)://.../privacy the URL-typed renderer requires. Set LAST so a
        // caller's nonPiiContext can never override it.
        values.put("privacy_link", authProps.getSpaBaseUrl() + "/privacy");

        return renderer.render(type, eff.subject(), eff.body(), values);
    }

    // --- internals -------------------------------------------------------------------------------

    private Resolved resolveForRender(String workspaceId, EmailMessageType type, String stageKey) {
        if (!EmailTemplate.BASE.equals(stageKey)) {
            EmailTemplate variant = repo.findByWorkspaceIdAndMessageTypeAndStageKey(workspaceId, type, stageKey).orElse(null);
            if (variant != null) return fromDoc(variant, stageKey);
        }
        EmailTemplate base = repo.findByWorkspaceIdAndMessageTypeAndStageKey(workspaceId, type, EmailTemplate.BASE).orElse(null);
        if (base != null) return fromDoc(base, stageKey);
        Content c = builtins.forType(type);
        return new Resolved(type, stageKey, c.subject(), c.body(), false, null, SOURCE_BUILTIN, null, null);
    }

    private Resolved fromDoc(EmailTemplate t, String requestedStageKey) {
        return new Resolved(t.getMessageType(), requestedStageKey, t.getSubject(), t.getBody(),
            t.isLocked(), t.getVersion(), SOURCE_OVERRIDE, t.getUpdatedByMemberId(), t.getUpdatedAt());
    }

    private TemplateResponse toResponse(Resolved r) {
        return new TemplateResponse(r.type().name(), r.stageKey(), r.subject(), r.body(), r.locked(),
            r.version(), r.source(), catalogue.permittedTokenNames(r.type()), r.updatedByMemberId(), r.updatedAt());
    }

    private EmailTemplate persist(String workspaceId, String actorMemberId, EmailMessageType type, String stageKey,
                                  String subject, String body, EmailTemplate existing) {
        EmailTemplate t = existing != null ? existing : newTemplate(workspaceId, actorMemberId, type, stageKey, subject, body);
        if (existing != null) {
            t.setSubject(subject);
            t.setBody(body);
            t.setUpdatedByMemberId(actorMemberId);
            t.setUpdatedAt(Instant.now(clock));
        }
        return saveOrStale(t);
    }

    private EmailTemplate newTemplate(String workspaceId, String actorMemberId, EmailMessageType type,
                                      String stageKey, String subject, String body) {
        EmailTemplate t = new EmailTemplate();
        Instant now = Instant.now(clock);
        t.setWorkspaceId(workspaceId);
        t.setMessageType(type);
        t.setStageKey(stageKey);
        t.setSubject(subject);
        t.setBody(body);
        t.setLocked(false);
        t.setCreatedByMemberId(actorMemberId);
        t.setUpdatedByMemberId(actorMemberId);
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        return t;
    }

    private EmailTemplate saveOrStale(EmailTemplate t) {
        try {
            return repo.save(t);
        } catch (OptimisticLockingFailureException | DuplicateKeyException e) {
            throw new EmailTemplateExceptions.StaleTemplateException();
        }
    }

    private void versionCheck(EmailTemplate existing, Long expectedVersion) {
        Long current = existing == null ? null : existing.getVersion();
        if (!Objects.equals(expectedVersion, current)) {
            throw new EmailTemplateExceptions.StaleTemplateException();
        }
    }

    private void lockedEditGuard(EmailTemplate existing, Role role) {
        if (existing != null && existing.isLocked() && role != Role.ADMIN) {
            throw new EmailTemplateExceptions.TemplateLockedException();
        }
    }

    private void validateStage(String workspaceId, String stageKey) {
        interviewTemplates.findByWorkspaceIdAndId(workspaceId, stageKey)
            .orElseThrow(RbacExceptions.ScopedNotFoundException::new);
    }

    private void enforceVariantCap(String workspaceId, EmailMessageType type) {
        long variants = repo.countByWorkspaceIdAndMessageTypeAndStageKeyNot(workspaceId, type, EmailTemplate.BASE);
        if (variants >= props.getMaxVariantsPerType()) {
            Map<String, String> e = new LinkedHashMap<>();
            e.put("stageKey", "Too many variants for this message type (max " + props.getMaxVariantsPerType() + ").");
            throw new EmailTemplateExceptions.InvalidTemplateException(e);
        }
    }

    private void validateContent(EmailMessageType type, String subject, String body) {
        Map<String, String> errors = new LinkedHashMap<>();
        String s = subject == null ? "" : subject;
        String b = body == null ? "" : body;
        if (s.isBlank()) errors.put("subject", "Subject is required.");
        else if (s.length() > props.getMaxSubjectLength())
            errors.put("subject", "Subject must be at most " + props.getMaxSubjectLength() + " characters.");
        if (b.isBlank()) errors.put("body", "Body is required.");
        else if (b.length() > props.getMaxBodyLength())
            errors.put("body", "Body must be at most " + props.getMaxBodyLength() + " characters.");
        if (catalogue.tokenCount(s) + catalogue.tokenCount(b) > props.getMaxTokensPerTemplate())
            errors.put("body", "Too many merge tokens (max " + props.getMaxTokensPerTemplate() + ").");
        errors.putAll(catalogue.validateTokens(type, s, b));
        if (!errors.isEmpty()) throw new EmailTemplateExceptions.InvalidTemplateException(errors);
    }

    private TonePreset parseTone(String tone) {
        try {
            return TonePreset.valueOf(tone == null ? "" : tone.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            Map<String, String> err = new LinkedHashMap<>();
            err.put("tone", "Unknown tone preset.");
            throw new EmailTemplateExceptions.InvalidTemplateException(err);
        }
    }

    private void auditChange(AuthEventType event, String workspaceId, String actorMemberId,
                             EmailMessageType type, String stageKey, String kind) {
        audit.record(event, workspaceId, actorMemberId, type.name() + "/" + stageKey + "/" + kind, null);
    }

    private static String normalize(String stageKey) {
        return (stageKey == null || stageKey.isBlank()) ? EmailTemplate.BASE : stageKey;
    }
}
