package com.cadence.feedback;

import com.cadence.api.WorkspaceDtos.SettingsPatch;
import com.cadence.api.WorkspaceExceptions;
import com.cadence.domain.WorkspaceConfig;
import com.cadence.service.WorkspaceConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SC-018 (settings): the per-workspace feedback deadline + reminder interval persist via the existing settings
 * path, and a non-positive value is rejected (prior value unchanged). Admin-gating + audit are the pre-existing
 * F03 settings behaviour.
 */
class FeedbackSettingsIT extends FeedbackItBase {

    @Autowired WorkspaceConfigService settings;

    private SettingsPatch feedbackPatch(Duration deadline, Duration interval) {
        return new SettingsPatch(null, null, null, null, null, null, null, deadline, interval);
    }

    @Test
    void persistsFeedbackDurations() {
        configuredWorkspace();
        settings.updateSettings(WS, "admin1", feedbackPatch(Duration.ofHours(48), Duration.ofHours(12)));
        WorkspaceConfig c = mongoTemplate.findById(
            mongoTemplate.findAll(WorkspaceConfig.class).get(0).getId(), WorkspaceConfig.class);
        assertThat(c.getFeedbackSubmissionDeadline()).isEqualTo(Duration.ofHours(48));
        assertThat(c.getFeedbackReminderInterval()).isEqualTo(Duration.ofHours(12));
    }

    @Test
    void rejectsNonPositiveDuration() {
        configuredWorkspace();
        assertThatThrownBy(() -> settings.updateSettings(WS, "admin1", feedbackPatch(Duration.ZERO, null)))
            .isInstanceOf(WorkspaceExceptions.ValidationException.class);
        WorkspaceConfig c = mongoTemplate.findAll(WorkspaceConfig.class).get(0);
        assertThat(c.getFeedbackSubmissionDeadline()).isNull(); // unchanged
    }
}
