package com.cadence.api;

import com.cadence.domain.InterestRequestStatus;
import com.cadence.domain.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** F70 wire shapes (contracts/interest-api.md). */
public final class InterestDtos {

    private InterestDtos() {}

    /**
     * Public submit body. {@code website} is the honeypot (MUST be empty); {@code formRenderedAtMillis} is the
     * optional client-side render timestamp for the min-fill heuristic (absent -> heuristic skipped). Validation
     * mirrors FR-003: name required <= 200; email required, valid format, <= 254; organization <= 200; message
     * <= 2000.
     */
    public record SubmitRequest(
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Email @Size(max = 254) String email,
        @Size(max = 200) String organization,
        @Size(max = 2000) String message,
        String website,
        Long formRenderedAtMillis) {}

    /** Public confirmation — byte-identical for every valid submission (no oracle). */
    public record SubmitResponse(String status) {}

    /** One admin queue row. {@code emailUnverified}/{@code organizationUnverified} are constant-true labels. */
    public record InterestRequestItem(
        String id,
        String name,
        String email,
        boolean emailUnverified,
        String organization,
        boolean organizationUnverified,
        String message,
        InterestRequestStatus status,
        Instant submittedAt) {}

    public record InterestRequestListResponse(List<InterestRequestItem> requests) {}

    /** Invite body — the role to grant; never from submitter input (FR-014). */
    public record InviteRequest(Role role) {}
}
