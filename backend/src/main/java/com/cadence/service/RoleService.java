package com.cadence.service;

import com.cadence.api.RbacExceptions;
import com.cadence.domain.Member;
import com.cadence.domain.MemberStatus;
import com.cadence.domain.Role;
import com.cadence.repository.MemberRepository;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

/**
 * Role administration (F02 US1). Enforces:
 * - validation against the closed role set (FR-031; the controller binds the body to the Role enum,
 *   so a non-canonical value never reaches here);
 * - no self-elevation (FR-006);
 * - the last-Administrator guard (FR-005) via a broker-free flip -> recount -> conditional-rollback
 *   sequence (research D4) that can never strand a workspace with zero active Admins under
 *   concurrency;
 * - a non-PII ROLE_CHANGED audit entry (FR-028).
 *
 * guardedDeactivate(...) is the F03-binding primitive: it applies the same last-Admin guard to a
 * status flip BEFORE revoking sessions, so a refused deactivation produces no partial state.
 */
@Service
public class RoleService {

    private final MongoTemplate mongo;
    private final MemberRepository members;
    private final SessionService sessions;
    private final AuthAuditService audit;
    private final Clock clock;

    public RoleService(MongoTemplate mongo, MemberRepository members, SessionService sessions,
                       AuthAuditService audit, Clock clock) {
        this.mongo = mongo;
        this.members = members;
        this.sessions = sessions;
        this.audit = audit;
        this.clock = clock;
    }

    public Member changeRole(String workspaceId, String actorMemberId, String targetMemberId, Role newRole) {
        Member target = members.findById(targetMemberId)
            .filter(m -> workspaceId.equals(m.getWorkspaceId()))
            .orElseThrow(RbacExceptions.ScopedNotFoundException::new); // 404, no cross-workspace leak
        Role oldRole = target.getRole();

        // No self-elevation (FR-006): a member may not raise their own privilege. The only elevation
        // is gaining ADMIN; non-Admins cannot reach this endpoint (ADMIN-only @PreAuthorize), so this
        // is defensive depth verified by the unit test.
        if (actorMemberId.equals(targetMemberId) && isElevation(oldRole, newRole)) {
            throw new RbacExceptions.SelfElevationException();
        }
        if (oldRole == newRole) {
            return target; // no-op, no audit
        }
        if (oldRole == Role.ADMIN) {
            // Removing ADMIN from the target — guard against last-Admin lockout.
            Instant now = Instant.now(clock);
            guardedFlipAdmin(target, new Update().set("role", newRole).set("updatedAt", now));
            target.setRole(newRole);
            target.setUpdatedAt(now); // keep the returned object consistent with the persisted doc
        } else {
            target.setRole(newRole);
            target.setUpdatedAt(Instant.now(clock));
            members.save(target);
        }
        audit.roleChanged(workspaceId, actorMemberId, targetMemberId, oldRole, newRole);
        return target;
    }

    /**
     * Deactivate a member, applying the last-Admin guard atomically before any session revocation
     * (FR-005). Shipped by F02; the deactivation endpoint is owned by F03 (forward contract).
     */
    public void guardedDeactivate(String workspaceId, String memberId) {
        Member target = members.findById(memberId)
            .filter(m -> workspaceId.equals(m.getWorkspaceId()))
            .orElseThrow(RbacExceptions.ScopedNotFoundException::new);
        if (target.getStatus() == MemberStatus.DEACTIVATED) {
            return; // already deactivated, idempotent
        }
        if (target.getRole() == Role.ADMIN) {
            guardedFlipAdmin(target, new Update()
                .set("status", MemberStatus.DEACTIVATED).set("updatedAt", Instant.now(clock)));
        } else {
            target.setStatus(MemberStatus.DEACTIVATED);
            target.setUpdatedAt(Instant.now(clock));
            members.save(target);
        }
        // Only reached on success (the guard throws on a last-Admin trip, before this line).
        sessions.revokeAllForMember(memberId);
    }

    /**
     * Flip an ACTIVE ADMIN's document (role or status) only if at least one OTHER active Admin
     * remains afterwards. Broker-free and race-safe (research D4):
     *   1. conditional findAndModify on the target (atomic on the single doc);
     *   2. recount active Admins in the workspace (sees any concurrent flip, which is durable);
     *   3. if zero remain, atomically roll the target back and throw LastAdminException.
     * For two simultaneous last-two-Admin demotions, the only interleaving where both flips precede
     * both recounts makes BOTH roll back (two Admins remain); every other interleaving yields exactly
     * one success. The workspace can never reach zero active Admins.
     */
    private void guardedFlipAdmin(Member target, Update flip) {
        Query onlyIfActiveAdmin = new Query(Criteria.where("_id").is(target.getId())
            .and("workspaceId").is(target.getWorkspaceId())
            .and("role").is(Role.ADMIN).and("status").is(MemberStatus.ACTIVE));
        Member flipped = mongo.findAndModify(
            onlyIfActiveAdmin, flip, FindAndModifyOptions.options().returnNew(true), Member.class);
        if (flipped == null) {
            return; // target was not an active Admin (already demoted/deactivated) — nothing to guard
        }
        long remaining = mongo.count(new Query(Criteria.where("workspaceId").is(target.getWorkspaceId())
            .and("role").is(Role.ADMIN).and("status").is(MemberStatus.ACTIVE)), Member.class);
        if (remaining == 0) {
            mongo.findAndModify(
                new Query(Criteria.where("_id").is(target.getId())),
                new Update().set("role", Role.ADMIN).set("status", MemberStatus.ACTIVE)
                    .set("updatedAt", Instant.now(clock)),
                Member.class);
            throw new RbacExceptions.LastAdminException();
        }
    }

    /** The only elevation in this non-hierarchical role set is gaining ADMIN. */
    private boolean isElevation(Role oldRole, Role newRole) {
        return newRole == Role.ADMIN && oldRole != Role.ADMIN;
    }
}
