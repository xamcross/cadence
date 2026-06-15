package com.cadence.service;

import com.cadence.api.InterviewTemplateDtos.BlackoutDto;
import com.cadence.api.InterviewTemplateDtos.PoolRuleDto;
import com.cadence.api.InterviewTemplateDtos.TemplateRequest;
import com.cadence.api.InterviewTemplateDtos.TemplateResponse;
import com.cadence.api.InterviewTemplateDtos.WorkingHoursDto;
import com.cadence.api.InterviewTemplateExceptions;
import com.cadence.api.RbacExceptions;
import com.cadence.config.InterviewTemplateProperties;
import com.cadence.domain.AuthEventType;
import com.cadence.domain.BlackoutPeriod;
import com.cadence.domain.InterviewTemplate;
import com.cadence.domain.Member;
import com.cadence.domain.PoolRule;
import com.cadence.domain.TemplateStatus;
import com.cadence.domain.WorkingHours;
import com.cadence.repository.InterviewTemplateRepository;
import com.cadence.repository.MemberRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Interview-template management (F12, US1). Full FR-002/FR-024 validation with VALUE-FREE messages
 * (field + rule, never the submitted value — D10); workspace-membership + no-dual-role + no-member-in-
 * two-pools checks (D8); soft-retire (FR-004). Audits lifecycle events with internal ids only — NEVER
 * the template name or any free text (FR-022/FR-023). Logs only ids/{@code .name()} Strings (never an
 * enum to {@code kv} — the F01.1 logstash Jackson-3 footgun).
 */
@Service
public class InterviewTemplateService {

    private static final Logger log = LoggerFactory.getLogger(InterviewTemplateService.class);
    private static final int NAME_MAX = 200;

    private final InterviewTemplateRepository templates;
    private final MemberRepository members;
    private final AuthAuditService audit;
    private final InterviewTemplateProperties props;
    private final Clock clock;

    public InterviewTemplateService(InterviewTemplateRepository templates, MemberRepository members,
                                    AuthAuditService audit, InterviewTemplateProperties props, Clock clock) {
        this.templates = templates;
        this.members = members;
        this.audit = audit;
        this.props = props;
        this.clock = clock;
    }

    public TemplateResponse create(String workspaceId, String actorMemberId, TemplateRequest req) {
        InterviewTemplate t = validateInto(workspaceId, new InterviewTemplate(), req);
        Instant now = Instant.now(clock);
        t.setWorkspaceId(workspaceId);
        t.setStatus(TemplateStatus.ACTIVE);
        t.setCreatedByMemberId(actorMemberId);
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        InterviewTemplate saved = templates.save(t);
        audit.record(AuthEventType.INTERVIEW_TEMPLATE_CREATED, workspaceId, actorMemberId, "created", null);
        log.info("interview template created {} {}", kv("templateId", saved.getId()), kv("workspaceId", workspaceId));
        return TemplateResponse.from(saved);
    }

    public TemplateResponse update(String workspaceId, String actorMemberId, String id, TemplateRequest req) {
        InterviewTemplate existing = templates.findByWorkspaceIdAndId(workspaceId, id)
            .orElseThrow(RbacExceptions.ScopedNotFoundException::new);
        validateInto(workspaceId, existing, req); // re-validate; mutate the loaded doc in place
        existing.setUpdatedAt(Instant.now(clock));
        InterviewTemplate saved = templates.save(existing);
        audit.record(AuthEventType.INTERVIEW_TEMPLATE_UPDATED, workspaceId, actorMemberId, "updated", null);
        return TemplateResponse.from(saved);
    }

    public TemplateResponse retire(String workspaceId, String actorMemberId, String id) {
        InterviewTemplate t = templates.findByWorkspaceIdAndId(workspaceId, id)
            .orElseThrow(RbacExceptions.ScopedNotFoundException::new);
        if (t.getStatus() != TemplateStatus.RETIRED) {
            t.setStatus(TemplateStatus.RETIRED);
            t.setUpdatedAt(Instant.now(clock));
            templates.save(t);
            audit.record(AuthEventType.INTERVIEW_TEMPLATE_RETIRED, workspaceId, actorMemberId, "retired", null);
        }
        return TemplateResponse.from(t);
    }

    public List<TemplateResponse> list(String workspaceId, String statusFilter) {
        List<InterviewTemplate> found;
        if ("ALL".equalsIgnoreCase(statusFilter)) {
            found = templates.findByWorkspaceId(workspaceId);
        } else {
            TemplateStatus status = "RETIRED".equalsIgnoreCase(statusFilter) ? TemplateStatus.RETIRED : TemplateStatus.ACTIVE;
            found = templates.findByWorkspaceIdAndStatus(workspaceId, status);
        }
        return found.stream().map(TemplateResponse::from).toList();
    }

    public TemplateResponse get(String workspaceId, String id) {
        return templates.findByWorkspaceIdAndId(workspaceId, id)
            .map(TemplateResponse::from)
            .orElseThrow(RbacExceptions.ScopedNotFoundException::new);
    }

    // --- validation (value-free messages; mutates target on success) -----------------------------

    private InterviewTemplate validateInto(String workspaceId, InterviewTemplate target, TemplateRequest req) {
        Map<String, String> errors = new LinkedHashMap<>();

        String name = req.name() == null ? null : req.name().trim();
        if (name == null || name.isBlank()) {
            errors.put("name", "A template name is required.");
        } else if (name.length() > NAME_MAX) {
            errors.put("name", "Name must be at most " + NAME_MAX + " characters.");
        }

        int duration = req.durationMinutes() == null ? 0 : req.durationMinutes();
        if (duration <= 0) {
            errors.put("durationMinutes", "Duration must be greater than zero.");
        }

        int cadence = req.slotCadenceMinutes() == null ? props.getDefaultSlotCadenceMinutes() : req.slotCadenceMinutes();
        if (cadence < 1 || (duration > 0 && cadence > duration)) {
            errors.put("slotCadenceMinutes", "Cadence must be between 1 and the duration.");
        }

        int bufferBefore = req.bufferBeforeMinutes() == null ? 0 : req.bufferBeforeMinutes();
        int bufferAfter = req.bufferAfterMinutes() == null ? 0 : req.bufferAfterMinutes();
        if (bufferBefore < 0) {
            errors.put("bufferBeforeMinutes", "Buffer cannot be negative.");
        }
        if (bufferAfter < 0) {
            errors.put("bufferAfterMinutes", "Buffer cannot be negative.");
        }

        int cap = req.dailyCapPerInterviewer() == null ? 0 : req.dailyCapPerInterviewer();
        if (cap < 1) {
            errors.put("dailyCapPerInterviewer", "Daily cap must be at least 1.");
        }

        List<String> required = req.requiredMemberIds() == null ? List.of() : req.requiredMemberIds();
        List<String> optional = req.optionalMemberIds() == null ? List.of() : req.optionalMemberIds();
        List<PoolRuleDto> poolDtos = req.pools() == null ? List.of() : req.pools();
        List<BlackoutDto> blackoutDtos = req.blackouts() == null ? List.of() : req.blackouts();

        if (required.isEmpty() && poolDtos.isEmpty()) {
            errors.put("participants", "At least one required participant or one pool is needed.");
        }

        // Pool shape: non-empty, n within 1..distinct size.
        for (int i = 0; i < poolDtos.size(); i++) {
            PoolRuleDto p = poolDtos.get(i);
            List<String> ids = p.memberIds() == null ? List.of() : p.memberIds();
            int distinct = (int) ids.stream().distinct().count();
            int n = p.n() == null ? 0 : p.n();
            if (distinct == 0) {
                errors.put("pools[" + i + "]", "A pool must have at least one member.");
            } else if (n < 1 || n > distinct) {
                errors.put("pools[" + i + "].n", "N must be between 1 and the number of distinct pool members.");
            }
        }

        for (int i = 0; i < blackoutDtos.size(); i++) {
            BlackoutDto b = blackoutDtos.get(i);
            if (b.start() == null || b.end() == null || !b.end().isAfter(b.start())) {
                errors.put("blackouts[" + i + "]", "Blackout end must be after start.");
            }
        }

        if (req.timeZoneOverride() != null) {
            try {
                ZoneId.of(req.timeZoneOverride());
            } catch (DateTimeException e) {
                errors.put("timeZoneOverride", "Must be a valid IANA time zone, e.g. Europe/London.");
            }
        }
        WorkingHours whOverride = null;
        WorkingHoursDto wh = req.workingHoursOverride();
        if (wh != null) {
            if (wh.start() == null || wh.end() == null) {
                errors.put("workingHoursOverride", "Working hours start and end are required.");
            } else if (!wh.end().isAfter(wh.start())) {
                errors.put("workingHoursOverride", "End time must be after start time (overnight windows are not supported).");
            } else {
                whOverride = new WorkingHours(wh.start(), wh.end());
            }
        }

        // Membership: every referenced member must belong to this workspace (D8 — closes the
        // cross-workspace availability-leak vector). Dual-role and two-pool checks prevent double-counting.
        Set<String> poolMembersSeen = new HashSet<>();
        List<String> allRefs = new ArrayList<>();
        allRefs.addAll(required);
        allRefs.addAll(optional);
        poolDtos.forEach(p -> { if (p.memberIds() != null) allRefs.addAll(p.memberIds()); });

        Set<String> workspaceMemberIds = members.findByWorkspaceId(workspaceId).stream()
            .map(Member::getId).collect(Collectors.toSet());
        boolean foreign = allRefs.stream().anyMatch(id -> id == null || !workspaceMemberIds.contains(id));
        if (foreign) {
            errors.put("members", "Every participant must be a member of this workspace.");
        }

        Set<String> requiredSet = new HashSet<>(required);
        for (PoolRuleDto p : poolDtos) {
            List<String> ids = p.memberIds() == null ? List.of() : p.memberIds();
            for (String id : ids.stream().distinct().toList()) {
                if (requiredSet.contains(id)) {
                    errors.put("members", "A member cannot be both required and in a pool.");
                }
                if (!poolMembersSeen.add(id)) {
                    errors.put("members", "A member cannot appear in more than one pool.");
                }
            }
        }

        long distinctMembers = allRefs.stream().filter(java.util.Objects::nonNull).distinct().count();
        if (distinctMembers > props.getMaxMembers()) {
            errors.put("members", "Too many distinct participants (max " + props.getMaxMembers() + ").");
        }
        if (poolDtos.size() > props.getMaxPools()) {
            errors.put("pools", "Too many pools (max " + props.getMaxPools() + ").");
        }
        if (blackoutDtos.size() > props.getMaxBlackouts()) {
            errors.put("blackouts", "Too many blackout periods (max " + props.getMaxBlackouts() + ").");
        }

        if (!errors.isEmpty()) {
            throw new InterviewTemplateExceptions.InvalidTemplateException(errors);
        }

        // Apply (validated) onto the target. Full replace of mutable collections.
        target.setName(name);
        target.setDurationMinutes(duration);
        target.setSlotCadenceMinutes(cadence);
        target.setBufferBeforeMinutes(bufferBefore);
        target.setBufferAfterMinutes(bufferAfter);
        target.setDailyCapPerInterviewer(cap);
        target.setRequiredMemberIds(new ArrayList<>(required));
        target.setOptionalMemberIds(new ArrayList<>(optional));
        target.setPools(poolDtos.stream()
            .map(p -> new PoolRule(p.memberIds().stream().distinct().toList(), p.n())).collect(Collectors.toList()));
        target.setBlackouts(blackoutDtos.stream()
            .map(b -> new BlackoutPeriod(b.start(), b.end())).collect(Collectors.toList()));
        target.setTimeZoneOverride(req.timeZoneOverride());
        target.setWorkingHoursOverride(whOverride);
        return target;
    }
}
