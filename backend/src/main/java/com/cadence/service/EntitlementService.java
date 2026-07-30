package com.cadence.service;

import com.cadence.api.BillingExceptions;
import com.cadence.domain.BillingPlan;
import com.cadence.domain.GatedFeature;
import com.cadence.repository.WorkspaceEntitlementRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 032 -- plan resolution + feature gating (FR-001/FR-003/FR-013). Absence of an entitlement row,
 * or a row past its effective end, means FREE. The plan->features map is static: adding a future
 * plan is a map + enum change, never scattered conditionals. Callers gate INITIATION points only
 * (FR-004); candidate-facing paths must never call this (SC-007).
 */
@Service
public class EntitlementService {

    private static final Map<BillingPlan, Set<GatedFeature>> PLAN_FEATURES = Map.of(
        BillingPlan.FREE, Set.of(),
        BillingPlan.TEAM, EnumSet.allOf(GatedFeature.class));

    private final WorkspaceEntitlementRepository entitlements;
    private final Clock clock;

    public EntitlementService(WorkspaceEntitlementRepository entitlements, Clock clock) {
        this.entitlements = entitlements;
        this.clock = clock;
    }

    public BillingPlan planOf(String workspaceId) {
        Instant now = Instant.now(clock);
        return entitlements.findByWorkspaceId(workspaceId)
            .filter(e -> e.confersTeam(now))
            .map(e -> BillingPlan.TEAM)
            .orElse(BillingPlan.FREE);
    }

    public boolean hasFeature(String workspaceId, GatedFeature feature) {
        return PLAN_FEATURES.get(planOf(workspaceId)).contains(feature);
    }

    /** Throws the 402 upgrade_required refusal (FR-013) when the workspace lacks the feature. */
    public void requireFeature(String workspaceId, GatedFeature feature) {
        if (!hasFeature(workspaceId, feature)) {
            throw new BillingExceptions.UpgradeRequiredException();
        }
    }
}
