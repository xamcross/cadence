package com.cadence.api;

import com.cadence.service.BrandingService;
import com.cadence.service.SessionService;
import com.cadence.service.WorkspaceConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * Admin-only workspace configuration (F03, contracts/workspace-api.md). The class-level
 * {@code @PreAuthorize("hasRole('ADMIN')")} is the single source of truth for the role and satisfies
 * the F02 RbacEndpointInventoryTest for every handler. Mounted under the internal (non-allow-listed)
 * prefix so a missing declaration would fail that test by design.
 */
@RestController
@RequestMapping("/api/internal/workspace")
@PreAuthorize("hasRole('ADMIN')")
public class WorkspaceConfigController {

    private final WorkspaceConfigService config;
    private final BrandingService branding;

    public WorkspaceConfigController(WorkspaceConfigService config, BrandingService branding) {
        this.config = config;
        this.branding = branding;
    }

    @GetMapping("/config")
    public ResponseEntity<WorkspaceDtos.WorkspaceConfigResponse> get(
            @AuthenticationPrincipal SessionService.Principal principal) {
        return ResponseEntity.ok(config.getConfig(principal.workspaceId()));
    }

    @PostMapping("/setup")
    public ResponseEntity<WorkspaceDtos.WorkspaceConfigResponse> setup(
            @AuthenticationPrincipal SessionService.Principal principal,
            @RequestBody WorkspaceDtos.SetupRequest req) {
        return ResponseEntity.ok(config.completeSetup(principal.workspaceId(), principal.memberId(), req));
    }

    @PatchMapping("/config")
    public ResponseEntity<WorkspaceDtos.WorkspaceConfigResponse> patch(
            @AuthenticationPrincipal SessionService.Principal principal,
            @RequestBody WorkspaceDtos.SettingsPatch patch) {
        return ResponseEntity.ok(config.updateSettings(principal.workspaceId(), principal.memberId(), patch));
    }

    @PutMapping("/branding")
    public ResponseEntity<WorkspaceDtos.WorkspaceConfigResponse> branding(
            @AuthenticationPrincipal SessionService.Principal principal,
            @RequestBody WorkspaceDtos.BrandingRequest req) {
        return ResponseEntity.ok(config.setBrandColor(principal.workspaceId(), principal.memberId(), req.brandColor()));
    }

    @PostMapping("/logo")
    public ResponseEntity<Map<String, Boolean>> uploadLogo(
            @AuthenticationPrincipal SessionService.Principal principal,
            @RequestParam("file") MultipartFile file) {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new WorkspaceExceptions.InvalidLogoException("The logo could not be read.");
        }
        branding.uploadLogo(principal.workspaceId(), principal.memberId(), bytes, file.getContentType());
        return ResponseEntity.ok(Map.of("hasLogo", true));
    }

    @DeleteMapping("/logo")
    public ResponseEntity<Void> deleteLogo(@AuthenticationPrincipal SessionService.Principal principal) {
        branding.deleteLogo(principal.workspaceId(), principal.memberId());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/email")
    public ResponseEntity<WorkspaceDtos.WorkspaceConfigResponse> email(
            @AuthenticationPrincipal SessionService.Principal principal,
            @RequestBody WorkspaceDtos.EmailConfigRequest req) {
        return ResponseEntity.ok(config.setEmailConfig(
            principal.workspaceId(), principal.memberId(), req.getSendingDomain(), req.getCredential()));
    }

    @DeleteMapping("/email/credential")
    public ResponseEntity<Void> deleteCredential(@AuthenticationPrincipal SessionService.Principal principal) {
        config.unsetCredential(principal.workspaceId(), principal.memberId());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/templates/{key}/lock")
    public ResponseEntity<WorkspaceDtos.WorkspaceConfigResponse> templateLock(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable String key,
            @RequestBody WorkspaceDtos.TemplateLockRequest req) {
        return ResponseEntity.ok(config.setTemplateLock(
            principal.workspaceId(), principal.memberId(), key, req.locked()));
    }
}
