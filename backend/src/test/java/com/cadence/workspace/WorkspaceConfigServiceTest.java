package com.cadence.workspace;

import com.cadence.api.WorkspaceDtos;
import com.cadence.api.WorkspaceExceptions;
import com.cadence.repository.WorkspaceConfigRepository;
import com.cadence.service.AuthAuditService;
import com.cadence.service.WorkspaceConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T018: parameterized validation-bound unit test (research D7 / SC-008). Pure Mockito — bad values
 * throw ValidationException BEFORE any DB write; accepted values reach a no-op mocked upsert.
 */
class WorkspaceConfigServiceTest {

    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final WorkspaceConfigRepository repo = mock(WorkspaceConfigRepository.class);
    private final AuthAuditService audit = mock(AuthAuditService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-14T12:00:00Z"), ZoneOffset.UTC);
    private final WorkspaceConfigService service =
        new WorkspaceConfigService(mongo, repo, audit, new com.cadence.config.NoShowProperties(), clock);

    private WorkspaceDtos.SetupRequest setup(String tz, LocalTime start, LocalTime end, Integer sla, Integer ret) {
        return new WorkspaceDtos.SetupRequest("Acme", tz,
            new WorkspaceDtos.WorkingHoursDto(start, end), sla, ret, true);
    }

    private void completeSetup(WorkspaceDtos.SetupRequest req) {
        when(repo.findByWorkspaceId(any())).thenReturn(Optional.empty());
        service.completeSetup("ws1", "admin", req);
    }

    private static final LocalTime NINE = LocalTime.of(9, 0);
    private static final LocalTime FIVE = LocalTime.of(17, 0);

    @ParameterizedTest
    @ValueSource(ints = {0, 31, -1, 100})
    void slaOutOfRange_rejected(int sla) {
        assertThatExceptionOfType(WorkspaceExceptions.ValidationException.class)
            .isThrownBy(() -> completeSetup(setup("Europe/London", NINE, FIVE, sla, 365)));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 30})
    void slaInRange_accepted(int sla) {
        assertThatCode(() -> completeSetup(setup("Europe/London", NINE, FIVE, sla, 365)))
            .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 29, 3651, -5})
    void retentionOutOfRange_rejected(int ret) {
        assertThatExceptionOfType(WorkspaceExceptions.ValidationException.class)
            .isThrownBy(() -> completeSetup(setup("Europe/London", NINE, FIVE, 5, ret)));
    }

    @ParameterizedTest
    @ValueSource(ints = {30, 3650, 365})
    void retentionInRange_accepted(int ret) {
        assertThatCode(() -> completeSetup(setup("Europe/London", NINE, FIVE, 5, ret)))
            .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Mars/Phobos", "NotAZone", "GMT+99", ""})
    void invalidTimeZone_rejected(String tz) {
        assertThatExceptionOfType(WorkspaceExceptions.ValidationException.class)
            .isThrownBy(() -> completeSetup(setup(tz, NINE, FIVE, 5, 365)));
    }

    @Test
    void validTimeZone_accepted() {
        assertThatCode(() -> completeSetup(setup("America/New_York", NINE, FIVE, 5, 365)))
            .doesNotThrowAnyException();
    }

    @Test
    void overnightOrEqualWorkingHours_rejected() {
        assertThatExceptionOfType(WorkspaceExceptions.ValidationException.class)
            .isThrownBy(() -> completeSetup(setup("Europe/London", FIVE, NINE, 5, 365))); // end before start
        assertThatExceptionOfType(WorkspaceExceptions.ValidationException.class)
            .isThrownBy(() -> completeSetup(setup("Europe/London", NINE, NINE, 5, 365))); // equal
    }

    @Test
    void missingAcknowledgment_rejected() {
        when(repo.findByWorkspaceId(any())).thenReturn(Optional.empty());
        WorkspaceDtos.SetupRequest req = new WorkspaceDtos.SetupRequest("Acme", "Europe/London",
            new WorkspaceDtos.WorkingHoursDto(NINE, FIVE), 5, 365, false);
        assertThatExceptionOfType(WorkspaceExceptions.RetentionNotAcknowledgedException.class)
            .isThrownBy(() -> service.completeSetup("ws1", "admin", req));
    }
}
