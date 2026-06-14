package com.cadence.api;

import com.cadence.service.BrandingService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * Public candidate-facing branding (F03 US3, contracts/workspace-api.md). Mounted under the
 * {@code /api/public/**} permitAll chain (no session) because candidate pages have no session.
 * Exposes ONLY the two brand attributes (logo + colour) — never any setting, credentialSet, or the
 * configured/unconfigured state. The logo response carries security headers itself (the @Order(2)
 * chain adds none): X-Content-Type-Options: nosniff defeats MIME-sniffing, a sandbox CSP is
 * defense-in-depth, and a bounded Cache-Control keeps the single <=1 MB asset off the origin per
 * candidate page load (research D6 / SEC-BLOCKER-2).
 */
@RestController
@RequestMapping("/api/public/workspace")
public class PublicBrandingController {

    private final BrandingService branding;

    public PublicBrandingController(BrandingService branding) {
        this.branding = branding;
    }

    @GetMapping("/branding")
    public ResponseEntity<WorkspaceDtos.BrandingResponse> branding() {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(Duration.ofSeconds(300)).cachePublic())
            .body(branding.resolvePublicBranding());
    }

    @GetMapping("/logo")
    public ResponseEntity<byte[]> logo() {
        BrandingService.Logo logo = branding.resolvePublicLogo();
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(logo.contentType()))
            .header("X-Content-Type-Options", "nosniff")
            .header("Content-Disposition", "inline")
            .header("Content-Security-Policy", "default-src 'none'; sandbox")
            .cacheControl(CacheControl.maxAge(Duration.ofSeconds(300)).cachePublic())
            .body(logo.bytes());
    }
}
