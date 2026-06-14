package com.cadence.api;

import com.cadence.domain.MemberStatus;
import com.cadence.domain.ResourceType;
import com.cadence.domain.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Request/response DTOs for the F02 RBAC API (contracts/rbac-api.md). */
public final class RbacDtos {

    private RbacDtos() {}

    /** Member directory row (Admin view). displayName is PII returned only to the authorized Admin. */
    public record MemberRow(String memberId, String displayName, Role role, MemberStatus status) {}

    /** Role change body — bound to the Role enum so a non-canonical value fails as 400 (FR-031). */
    public record RoleChangeRequest(@NotNull Role role) {}

    public record RoleChangeResponse(String memberId, Role role) {}

    public record AssignmentCreateRequest(@NotNull ResourceType resourceType, @NotBlank String resourceId) {}

    public record AssignmentView(String assignmentId, ResourceType resourceType, String resourceId, String memberId) {}
}
