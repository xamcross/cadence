package com.cadence.service;

import com.cadence.api.WorkspaceDtos;
import com.cadence.api.WorkspaceExceptions;
import com.cadence.domain.WorkingHours;
import com.cadence.domain.WorkspaceConfig;
import com.cadence.repository.WorkspaceConfigRepository;
import com.mongodb.client.result.UpdateResult;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Workspace configuration (F03). The wizard {@link #completeSetup} is the ONLY inserter — a
 * conditional upsert keyed by {@code {workspaceId, configuredAt:null}} so concurrent first-run
 * submissions resolve to exactly one configured record (research D4). Reads ({@link #getConfig},
 * {@link #isConfigured}) NEVER create the document. Ongoing edits use targeted {@code $set} of only
 * the changed fields so concurrent different-field edits do not lost-update each other.
 *
 * The email-provider credential is written through the entity ({@code save}) so the registered
 * {@code PiiStringConverter} encrypts it at rest; it is never returned (FR-016/FR-017).
 */
@Service
public class WorkspaceConfigService {

    // Validation bounds (research D7) — constants so FR-005/SC-008 are testable.
    static final int SLA_MIN = 1, SLA_MAX = 30;
    static final int RETENTION_MIN = 30, RETENTION_MAX = 3650;
    private static final int NAME_MAX = 200;
    private static final int TEMPLATE_KEY_MAX = 128;
    private static final Pattern COLOR = Pattern.compile("^#[0-9A-Fa-f]{6}$");
    // ASCII letters/digits/hyphen, dot-separated labels, >=2 labels. Rejects Unicode/control/punycode-decoded.
    private static final Pattern DOMAIN =
        Pattern.compile("^(?=.{1,253}$)([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)(\\.[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)+$");
    private static final Pattern TEMPLATE_KEY = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");

    private final MongoTemplate mongo;
    private final WorkspaceConfigRepository configs;
    private final AuthAuditService audit;
    private final Clock clock;

    public WorkspaceConfigService(MongoTemplate mongo, WorkspaceConfigRepository configs,
                                  AuthAuditService audit, Clock clock) {
        this.mongo = mongo;
        this.configs = configs;
        this.audit = audit;
        this.clock = clock;
    }

    // --- reads (NEVER create the doc — research D4 invariant) ---------------------------------

    public boolean isConfigured(String workspaceId) {
        return configs.existsByWorkspaceIdAndConfiguredAtNotNull(workspaceId);
    }

    public WorkspaceDtos.WorkspaceConfigResponse getConfig(String workspaceId) {
        return configs.findByWorkspaceId(workspaceId)
            .map(WorkspaceDtos.WorkspaceConfigResponse::from)
            .orElseGet(WorkspaceDtos.WorkspaceConfigResponse::unconfigured);
    }

    // --- first-run setup (the only inserter) -------------------------------------------------

    public WorkspaceDtos.WorkspaceConfigResponse completeSetup(
            String workspaceId, String actorMemberId, WorkspaceDtos.SetupRequest req) {

        Map<String, String> errors = new LinkedHashMap<>();
        validateName(req.name(), errors);
        validateTimeZone(req.timeZone(), errors);
        WorkingHours hours = validateWorkingHours(req.workingHours(), errors);
        validateSla(req.slaSilenceWindowDays(), errors);
        validateRetention(req.retentionPeriodDays(), errors);
        if (!errors.isEmpty()) {
            throw new WorkspaceExceptions.ValidationException(errors);
        }
        if (!req.retentionAcknowledged()) {
            throw new WorkspaceExceptions.RetentionNotAcknowledgedException();
        }

        Instant now = Instant.now(clock);
        Query filter = new Query(Criteria.where("workspaceId").is(workspaceId).and("configuredAt").is(null));
        Update update = new Update()
            .set("name", req.name().trim())
            .set("timeZone", req.timeZone())
            .set("workingHours", hours)
            .set("slaSilenceWindowDays", req.slaSilenceWindowDays())
            .set("retentionPeriodDays", req.retentionPeriodDays())
            .set("configuredAt", now)
            .set("retentionAcknowledgedAt", now)
            .set("updatedAt", now)
            .setOnInsert("workspaceId", workspaceId)
            .setOnInsert("createdAt", now)
            .setOnInsert("templateLocks", new HashMap<String, Boolean>());
        try {
            mongo.upsert(filter, update, WorkspaceConfig.class);
        } catch (DuplicateKeyException e) {
            // Either a configured doc already exists (filter miss -> insert -> unique clash) or the
            // concurrent first-run loser. Audit the attempt so both attempts are recorded (US1 AS-7).
            audit.setupConflict(workspaceId, actorMemberId);
            throw new WorkspaceExceptions.AlreadyConfiguredException();
        }
        audit.workspaceConfigured(workspaceId, actorMemberId, req.retentionPeriodDays());
        return getConfig(workspaceId);
    }

    // --- ongoing operational settings (targeted $set, no lost update) ------------------------

    public WorkspaceDtos.WorkspaceConfigResponse updateSettings(
            String workspaceId, String actorMemberId, WorkspaceDtos.SettingsPatch patch) {

        WorkspaceConfig current = requireConfigured(workspaceId);

        Map<String, String> errors = new LinkedHashMap<>();
        Update update = new Update();
        Map<String, String[]> audits = new LinkedHashMap<>(); // settingCode -> [old,new] (null for non-retention)

        if (patch.name() != null) {
            validateName(patch.name(), errors);
            update.set("name", patch.name().trim());
            audits.put("name", null);
        }
        if (patch.timeZone() != null) {
            validateTimeZone(patch.timeZone(), errors);
            update.set("timeZone", patch.timeZone());
            audits.put("time_zone", null);
        }
        if (patch.workingHours() != null) {
            WorkingHours hours = validateWorkingHours(patch.workingHours(), errors);
            update.set("workingHours", hours);
            audits.put("working_hours", null);
        }
        if (patch.slaSilenceWindowDays() != null) {
            validateSla(patch.slaSilenceWindowDays(), errors);
            update.set("slaSilenceWindowDays", patch.slaSilenceWindowDays());
            audits.put("sla_window", null);
        }
        if (patch.retentionPeriodDays() != null) {
            validateRetention(patch.retentionPeriodDays(), errors);
            update.set("retentionPeriodDays", patch.retentionPeriodDays());
            audits.put("retention_period",
                new String[]{Integer.toString(current.getRetentionPeriodDays()),
                             Integer.toString(patch.retentionPeriodDays())});
        }
        if (!errors.isEmpty()) {
            throw new WorkspaceExceptions.ValidationException(errors);
        }
        if (!audits.isEmpty()) {
            update.set("updatedAt", Instant.now(clock));
            mongo.updateFirst(byWorkspace(workspaceId), update, WorkspaceConfig.class);
            audits.forEach((code, oldNew) ->
                audit.configChanged(workspaceId, actorMemberId, code,
                    oldNew == null ? null : oldNew[0], oldNew == null ? null : oldNew[1]));
        }
        return getConfig(workspaceId);
    }

    // --- branding colour (logo bytes live in BrandingService) --------------------------------

    public WorkspaceDtos.WorkspaceConfigResponse setBrandColor(
            String workspaceId, String actorMemberId, String brandColor) {
        requireConfigured(workspaceId);
        Map<String, String> errors = new LinkedHashMap<>();
        validateColor(brandColor, errors);
        if (!errors.isEmpty()) {
            throw new WorkspaceExceptions.ValidationException(errors);
        }
        mongo.updateFirst(byWorkspace(workspaceId),
            new Update().set("brandColor", brandColor).set("updatedAt", Instant.now(clock)),
            WorkspaceConfig.class);
        audit.configChanged(workspaceId, actorMemberId, "branding", null, null);
        return getConfig(workspaceId);
    }

    // --- email config (targeted $set — no whole-doc clobber; credential encrypted explicitly) -----

    public WorkspaceDtos.WorkspaceConfigResponse setEmailConfig(
            String workspaceId, String actorMemberId, String sendingDomain, String credential) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (sendingDomain == null || !DOMAIN.matcher(sendingDomain).matches()) {
            errors.put("sendingDomain", "Must be a valid domain name.");
        }
        if (credential == null || credential.isBlank()) {
            errors.put("credential", "A provider credential is required.");
        }
        if (!errors.isEmpty()) {
            throw new WorkspaceExceptions.ValidationException(errors);
        }
        // Targeted $set (no whole-doc clobber). The registered PiiStringConverter IS applied to the
        // $set value, so we pass the PLAINTEXT and it is encrypted at rest (verified by the cold-reload
        // decrypt test). Concurrent edits to other fields are preserved.
        Update update = new Update()
            .set("emailSendingDomain", sendingDomain)
            .set("emailProviderCredential", credential)
            .set("updatedAt", Instant.now(clock));
        requireConfiguredUpdate(workspaceId, update);
        audit.configChanged(workspaceId, actorMemberId, "email_config", null, null);
        return getConfig(workspaceId);
    }

    public void unsetCredential(String workspaceId, String actorMemberId) {
        // $set null (NOT $unset): $unset on a converter-managed field passes the unset marker to the
        // String converter and throws ClassCastException. Null clears it -> credentialSet=false.
        Update update = new Update().set("emailProviderCredential", null).set("updatedAt", Instant.now(clock));
        requireConfiguredUpdate(workspaceId, update);
        audit.configChanged(workspaceId, actorMemberId, "email_config", null, null);
    }

    // --- template governance (forward contract to F21) ---------------------------------------

    public WorkspaceDtos.WorkspaceConfigResponse setTemplateLock(
            String workspaceId, String actorMemberId, String key, boolean locked) {
        requireConfigured(workspaceId);
        if (key == null || !TEMPLATE_KEY.matcher(key).matches()) {
            throw new WorkspaceExceptions.ValidationException(
                Map.of("key", "Template key must be 1-" + TEMPLATE_KEY_MAX + " chars [A-Za-z0-9_-]."));
        }
        mongo.updateFirst(byWorkspace(workspaceId),
            new Update().set("templateLocks." + key, locked).set("updatedAt", Instant.now(clock)),
            WorkspaceConfig.class);
        audit.configChanged(workspaceId, actorMemberId, "template_lock", null, null);
        return getConfig(workspaceId);
    }

    // --- helpers -----------------------------------------------------------------------------

    private WorkspaceConfig requireConfigured(String workspaceId) {
        return configs.findByWorkspaceId(workspaceId)
            .filter(WorkspaceConfig::isConfigured)
            .orElseThrow(WorkspaceExceptions.NotConfiguredException::new);
    }

    /** Apply a targeted update only to a configured workspace; 0 matches -> NotConfigured (no upsert). */
    private void requireConfiguredUpdate(String workspaceId, Update update) {
        Query q = new Query(Criteria.where("workspaceId").is(workspaceId).and("configuredAt").ne(null));
        UpdateResult r = mongo.updateFirst(q, update, WorkspaceConfig.class);
        if (r.getMatchedCount() == 0) {
            throw new WorkspaceExceptions.NotConfiguredException();
        }
    }

    private static Query byWorkspace(String workspaceId) {
        return new Query(Criteria.where("workspaceId").is(workspaceId));
    }

    private static void validateName(String name, Map<String, String> errors) {
        if (name == null || name.isBlank()) {
            errors.put("name", "A workspace name is required.");
        } else if (name.trim().length() > NAME_MAX) {
            errors.put("name", "Name must be at most " + NAME_MAX + " characters.");
        }
    }

    private static void validateTimeZone(String tz, Map<String, String> errors) {
        if (tz == null || tz.isBlank()) {
            errors.put("timeZone", "A time zone is required.");
            return;
        }
        try {
            ZoneId.of(tz);
        } catch (DateTimeException e) {
            errors.put("timeZone", "Must be a valid IANA time zone, e.g. Europe/London.");
        }
    }

    private static WorkingHours validateWorkingHours(WorkspaceDtos.WorkingHoursDto wh, Map<String, String> errors) {
        if (wh == null || wh.start() == null || wh.end() == null) {
            errors.put("workingHours", "Working hours start and end are required.");
            return null;
        }
        if (!wh.end().isAfter(wh.start())) {
            errors.put("workingHours", "End time must be after start time (overnight windows are not supported).");
            return null;
        }
        return new WorkingHours(wh.start(), wh.end());
    }

    private static void validateSla(Integer days, Map<String, String> errors) {
        if (days == null) {
            errors.put("slaSilenceWindowDays", "An SLA silence window is required.");
        } else if (days < SLA_MIN || days > SLA_MAX) {
            errors.put("slaSilenceWindowDays", "Must be between " + SLA_MIN + " and " + SLA_MAX + " days.");
        }
    }

    private static void validateRetention(Integer days, Map<String, String> errors) {
        if (days == null) {
            errors.put("retentionPeriodDays", "A data-retention period is required.");
        } else if (days < RETENTION_MIN || days > RETENTION_MAX) {
            errors.put("retentionPeriodDays", "Must be between " + RETENTION_MIN + " and " + RETENTION_MAX + " days.");
        }
    }

    private static void validateColor(String color, Map<String, String> errors) {
        if (color == null || !COLOR.matcher(color).matches()) {
            errors.put("brandColor", "Must be a hex colour like #1F2937.");
        }
    }
}
