package com.cadence.api;

import com.cadence.domain.CalendarConnection;
import com.cadence.domain.CalendarProvider;
import com.cadence.integration.UnsupportedProviderException;
import com.cadence.service.CalendarConnectionService;
import com.cadence.service.SessionService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * Member-self calendar connection surface (F01.1). Every handler is {@code isAuthenticated()} and acts
 * on the authenticated principal ONLY — no memberId appears in any path, so cross-member access is
 * structurally impossible (FR-018). Under the internal prefix so RbacEndpointInventoryTest enforces a
 * method-security annotation on every handler.
 */
@RestController
@RequestMapping("/api/internal/calendar/connections")
public class CalendarConnectionController {

    private final CalendarConnectionService service;

    public CalendarConnectionController(CalendarConnectionService service) {
        this.service = service;
    }

    /** US1: list the caller's own connections. PII (connectedAccount) -> Cache-Control: no-store. */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<CalendarDtos.ConnectionList> list(
            @AuthenticationPrincipal SessionService.Principal principal) {
        List<CalendarDtos.ConnectionRow> rows = service.list(principal.workspaceId(), principal.memberId())
            .stream().map(CalendarConnectionController::view).toList();
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(new CalendarDtos.ConnectionList(rows));
    }

    /** US1: begin a connection — returns the provider authorize URL for the SPA to navigate to. */
    @PostMapping("/{provider}/start")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<CalendarDtos.StartResponse> start(
            @AuthenticationPrincipal SessionService.Principal principal, @PathVariable String provider) {
        CalendarProvider p = CalendarProvider.fromPath(provider); // 400 unsupported_provider on unknown
        String url = service.start(principal.workspaceId(), principal.memberId(), p);
        return ResponseEntity.ok(new CalendarDtos.StartResponse(url));
    }

    /** US1: the provider callback. Always a 302 redirect to the SPA (never JSON; no token/code in URL). */
    @GetMapping("/{provider}/callback")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> callback(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable String provider,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error) {
        String redirect;
        try {
            CalendarProvider p = CalendarProvider.fromPath(provider);
            redirect = service.completeCallback(p, code, state, error,
                principal.workspaceId(), principal.memberId());
        } catch (UnsupportedProviderException e) {
            // Unsupported provider in the callback path -> error redirect, NOT a 500/400 (contracts §3).
            redirect = service.invalidStateRedirect();
        }
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(redirect)).build();
    }

    /** US3: disconnect one provider. Idempotent 204; best-effort revoke (FR-006). */
    @DeleteMapping("/{provider}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> disconnect(
            @AuthenticationPrincipal SessionService.Principal principal, @PathVariable String provider) {
        CalendarProvider p = CalendarProvider.fromPath(provider); // 400 unsupported_provider on unknown
        service.disconnect(principal.workspaceId(), principal.memberId(), p);
        return ResponseEntity.noContent().build();
    }

    private static CalendarDtos.ConnectionRow view(CalendarConnection c) {
        return new CalendarDtos.ConnectionRow(
            c.getProvider().name(), c.getStatus().name(), c.getProviderAccountId(), c.getConnectedAt());
    }
}
