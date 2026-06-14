package com.cadence.api;

import com.cadence.domain.Member;
import com.cadence.repository.MemberRepository;
import com.cadence.service.RoleService;
import com.cadence.service.SessionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Member administration (F02 US1, contracts/rbac-api.md). Admin-only: list members + change a
 * member's role. The role change is governed on the target's NEXT request (D3) and audited (FR-028).
 */
@RestController
public class MemberAdminController {

    private final MemberRepository members;
    private final RoleService roles;

    public MemberAdminController(MemberRepository members, RoleService roles) {
        this.members = members;
        this.roles = roles;
    }

    @GetMapping("/api/internal/members")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<RbacDtos.MemberRow>> list(
            @AuthenticationPrincipal SessionService.Principal principal) {
        List<RbacDtos.MemberRow> rows = members.findByWorkspaceId(principal.workspaceId()).stream()
            .map(m -> new RbacDtos.MemberRow(m.getId(), m.getDisplayName(), m.getRole(), m.getStatus()))
            .toList();
        return ResponseEntity.ok(rows);
    }

    @PatchMapping("/api/internal/members/{memberId}/role")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<RbacDtos.RoleChangeResponse> changeRole(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable String memberId,
            @Valid @RequestBody RbacDtos.RoleChangeRequest req) {
        Member updated = roles.changeRole(
            principal.workspaceId(), principal.memberId(), memberId, req.role());
        return ResponseEntity.ok(new RbacDtos.RoleChangeResponse(updated.getId(), updated.getRole()));
    }
}
