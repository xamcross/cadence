package com.cadence.api;

import com.cadence.domain.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Request/response DTOs for the auth API. */
public final class AuthDtos {

    private AuthDtos() {}

    public record LoginRequest(
        @NotBlank String workspaceId,
        @NotBlank @Email String email,
        @NotBlank String password) {}

    public record ResetRequestRequest(
        @NotBlank String workspaceId,
        @NotBlank @Email String email) {}

    public record ResetConfirmRequest(
        @NotBlank String token,
        @NotBlank String newPassword) {}

    public record InviteCreateRequest(
        @NotBlank @Email String email,
        @NotNull Role role) {}

    public record InviteAcceptRequest(String password) {}

    public record MemberSummary(String memberId, String workspaceId, Role role, String displayName,
                                String email, boolean workspaceConfigured) {}

    public record InvitationView(String email, Role role, boolean needsPassword) {}
}
