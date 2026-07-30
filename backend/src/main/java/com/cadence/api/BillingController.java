package com.cadence.api;

import com.cadence.service.BillingService;
import com.cadence.service.SessionService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 032 -- billing endpoints (spec US1/FR-005/FR-006/FR-014). Plan view is readable by every
 * authenticated member (gated-surface prompts need it); checkout + claim are Admin-only. The
 * workspace is always the session principal's -- never a path variable (house rule). Authorization
 * reads the persisted member role via the session filter.
 */
@RestController
@RequestMapping("/api/internal/billing")
public class BillingController {

    private final BillingService billing;

    public BillingController(BillingService billing) {
        this.billing = billing;
    }

    @GetMapping("/entitlement")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<BillingDtos.EntitlementResponse> entitlement(
            @AuthenticationPrincipal SessionService.Principal principal) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(billing.view(principal.workspaceId()));
    }

    @PostMapping("/checkout-session")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BillingDtos.CheckoutSessionResponse> checkoutSession(
            @AuthenticationPrincipal SessionService.Principal principal) {
        String url = billing.checkoutUrl(principal.workspaceId(), principal.memberId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new BillingDtos.CheckoutSessionResponse(url));
    }

    @PostMapping("/claim")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BillingDtos.EntitlementResponse> claim(
            @AuthenticationPrincipal SessionService.Principal principal,
            @Valid @RequestBody BillingDtos.ClaimRequest req) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(billing.claim(principal.workspaceId(), req.licenseId(), principal.memberId()));
    }
}
