package com.cadence.service;

import com.cadence.api.BillingDtos;
import com.cadence.api.BillingExceptions;
import com.cadence.domain.BillingPlan;
import com.cadence.domain.EntitlementStatus;
import com.cadence.domain.WorkspaceEntitlement;
import com.cadence.integration.BillingApiException;
import com.cadence.integration.BillingLicense;
import com.cadence.integration.BillingProvider;
import com.cadence.config.BillingProperties;
import com.cadence.repository.MemberRepository;
import com.cadence.repository.WorkspaceEntitlementRepository;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 032 -- checkout URL, license claim (the ONLY binding act, FR-006/FR-007), and provider-truth
 * refresh (FR-010/FR-011). Binding is insert-only under the two unique indexes; the
 * DuplicateKeyException loser re-reads to classify the race deterministically. Refresh never
 * downgrades on a provider error (FR-011) -- BillingApiException propagates to the caller, which
 * isolates per row. Logs carry workspace/Freemius ids only.
 */
@Service
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);

    private final WorkspaceEntitlementRepository entitlements;
    private final MemberRepository members;
    private final BillingProvider provider;
    private final BillingProperties props;
    private final AuthAuditService audit;
    private final MongoTemplate mongo;
    private final Clock clock;

    public BillingService(WorkspaceEntitlementRepository entitlements, MemberRepository members,
                          BillingProvider provider, BillingProperties props, AuthAuditService audit,
                          MongoTemplate mongo, Clock clock) {
        this.entitlements = entitlements;
        this.members = members;
        this.provider = provider;
        this.props = props;
        this.audit = audit;
        this.mongo = mongo;
        this.clock = clock;
    }

    public BillingDtos.EntitlementResponse view(String workspaceId) {
        Instant now = Instant.now(clock);
        return entitlements.findByWorkspaceId(workspaceId)
            .filter(e -> e.confersTeam(now))
            .map(e -> new BillingDtos.EntitlementResponse(BillingPlan.TEAM, e.getStatus(),
                e.getExpiresAt(), e.getBoundAt()))
            .orElse(new BillingDtos.EntitlementResponse(BillingPlan.FREE, null, null, null));
    }

    public String checkoutUrl(String workspaceId, String actorMemberId) {
        String adminEmail = members.findById(actorMemberId)
            .map(m -> m.getEmail()) // converter-decrypted; goes into the checkout URL only, never logged
            .orElseThrow(() -> new BillingExceptions.ClaimUnavailableException());
        audit.billingCheckoutStarted(workspaceId, actorMemberId);
        return provider.checkoutUrl(adminEmail);
    }

    public BillingDtos.EntitlementResponse claim(String workspaceId, String licenseId, String actorMemberId) {
        Optional<WorkspaceEntitlement> existing = entitlements.findByWorkspaceId(workspaceId);
        if (existing.isPresent()) {
            if (licenseId.equals(existing.get().getFsLicenseId())) {
                return view(workspaceId); // idempotent re-claim (return-page refresh)
            }
            throw new BillingExceptions.ClaimRejectedException("already_upgraded");
        }
        BillingLicense license = fetchForClaim(licenseId);
        if (!props.getTeamPlanId().equals(license.planId())) {
            throw new BillingExceptions.ClaimRejectedException("wrong_plan");
        }
        Instant now = Instant.now(clock);
        boolean pastEnd = license.expiresAt() != null && !license.expiresAt().isAfter(now);
        if (license.cancelled() || pastEnd) {
            throw new BillingExceptions.ClaimRejectedException("license_inactive"); // FR-006: active only
        }
        WorkspaceEntitlement e = new WorkspaceEntitlement();
        e.setWorkspaceId(workspaceId);
        e.setFsLicenseId(license.id());
        e.setFsUserId(license.userId());
        e.setFsPlanId(license.planId());
        e.setStatus(EntitlementStatus.ACTIVE);
        e.setExpiresAt(license.expiresAt());
        e.setBoundAt(now);
        e.setLastVerifiedAt(now);
        e.setUpdatedAt(now);
        try {
            entitlements.insert(e);
        } catch (DuplicateKeyException dup) {
            Optional<WorkspaceEntitlement> current = entitlements.findByWorkspaceId(workspaceId);
            if (current.isPresent()) {
                if (licenseId.equals(current.get().getFsLicenseId())) {
                    return view(workspaceId); // lost an intra-workspace race to the same license
                }
                throw new BillingExceptions.ClaimRejectedException("already_upgraded");
            }
            throw new BillingExceptions.ClaimRejectedException("license_already_bound");
        }
        audit.billingLicenseClaimed(workspaceId, actorMemberId);
        log.info("billing license claimed {} {}",
            StructuredArguments.kv("workspaceId", workspaceId),
            StructuredArguments.kv("fsLicenseId", license.id()));
        return view(workspaceId);
    }

    private BillingLicense fetchForClaim(String licenseId) {
        try {
            return provider.fetchLicense(licenseId);
        } catch (BillingApiException ex) {
            if (ex.isNotFound()) {
                throw new BillingExceptions.ClaimRejectedException("invalid_license");
            }
            throw new BillingExceptions.ClaimUnavailableException();
        }
    }

    /** Webhook poke path (FR-010): unbound license ids are a no-op; bound ones re-fetch truth. */
    public void refreshByLicenseId(String licenseId) {
        entitlements.findByFsLicenseId(licenseId).ifPresent(this::refresh);
    }

    /**
     * Re-verify one entitlement against provider truth (FR-010/FR-011/FR-012). Throws
     * BillingApiException on provider failure -- the caller isolates; state is never changed on error.
     */
    public void refresh(WorkspaceEntitlement e) {
        BillingLicense license = provider.fetchLicense(e.getFsLicenseId());
        Instant now = Instant.now(clock);
        if (!props.getTeamPlanId().equals(license.planId())) {
            // Spec edge case: unknown plan id -> flag for the operator, never silently downgrade.
            log.warn("billing entitlement has unrecognized plan {} {}",
                StructuredArguments.kv("workspaceId", e.getWorkspaceId()),
                StructuredArguments.kv("fsPlanId", license.planId()));
            return;
        }
        EntitlementStatus status = license.cancelled() ? EntitlementStatus.CANCELLED : EntitlementStatus.ACTIVE;
        if (license.expiresAt() != null && !license.expiresAt().isAfter(now)) {
            status = EntitlementStatus.EXPIRED;
        }
        boolean changed = status != e.getStatus() || !Objects.equals(license.expiresAt(), e.getExpiresAt());
        mongo.findAndModify(
            Query.query(Criteria.where("_id").is(e.getId())),
            new Update().set("status", status).set("expiresAt", license.expiresAt())
                .set("lastVerifiedAt", now).set("updatedAt", now),
            FindAndModifyOptions.options().returnNew(true),
            WorkspaceEntitlement.class);
        if (changed) {
            audit.billingEntitlementUpdated(e.getWorkspaceId(), status.name().toLowerCase());
            log.info("billing entitlement updated {} {}",
                StructuredArguments.kv("workspaceId", e.getWorkspaceId()),
                StructuredArguments.kv("status", status.name()));
        }
    }
}
