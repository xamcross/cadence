package com.cadence.api;

import com.cadence.config.CalendarApiProperties;
import com.cadence.domain.CalendarProvider;
import com.cadence.domain.MemberAvailability;
import com.cadence.service.AvailabilityService;
import com.cadence.service.SessionService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;

/**
 * F10 self availability preview (research D11) — the constitution-§II end-to-end leg. Self-scoped to the
 * authenticated principal (NO memberId param), so a member can only preview their own calendar (FR-018).
 * Under the internal prefix so RbacEndpointInventoryTest enforces a method-security annotation. Live data
 * -> {@code Cache-Control: no-store}. The response carries ONLY busy intervals, never event content.
 */
@RestController
@RequestMapping("/api/internal/calendar/availability")
public class CalendarAvailabilityController {

    private final AvailabilityService availability;
    private final CalendarApiProperties props;
    private final Clock clock;

    public CalendarAvailabilityController(AvailabilityService availability, CalendarApiProperties props,
                                          Clock clock) {
        this.availability = availability;
        this.props = props;
        this.clock = clock;
    }

    @GetMapping("/preview")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<CalendarDtos.AvailabilityPreviewResponse> preview(
            @AuthenticationPrincipal SessionService.Principal principal) {
        Instant start = Instant.now(clock);
        Instant end = start.plus(props.getPreviewWindow());
        MemberAvailability a = availability.previewSelf(principal.workspaceId(), principal.memberId(), start, end);
        String provider = availability.providerFor(principal.workspaceId(), principal.memberId())
            .map(CalendarProvider::name).orElse(null);
        CalendarDtos.AvailabilityPreviewResponse body = new CalendarDtos.AvailabilityPreviewResponse(
            provider, a.status().name(), start, end, a.busy());
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(body);
    }
}
