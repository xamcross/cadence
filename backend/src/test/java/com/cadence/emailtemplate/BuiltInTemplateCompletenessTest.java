package com.cadence.emailtemplate;

import com.cadence.BaseIntegrationTest;
import com.cadence.domain.EmailMessageType;
import com.cadence.domain.TonePreset;
import com.cadence.service.BuiltInEmailTemplates;
import com.cadence.service.BuiltInEmailTemplates.Content;
import com.cadence.service.MergeTokenCatalogue;
import com.cadence.service.TonePresetCatalogue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SC-001: every message type has a non-empty built-in default and every (type x tone) preset is present;
 * and every shipped default/preset uses only tokens the catalogue permits for that type. Context-loaded
 * means the {@code @PostConstruct} completeness checks passed (fail-fast on a packaging gap).
 */
class BuiltInTemplateCompletenessTest extends BaseIntegrationTest {

    @Autowired BuiltInEmailTemplates builtins;
    @Autowired TonePresetCatalogue tones;
    @Autowired MergeTokenCatalogue catalogue;

    @Test
    void everyType_hasNonEmptyTokenValidDefault() {
        for (EmailMessageType type : EmailMessageType.values()) {
            Content c = builtins.forType(type);
            assertThat(c).as("default for %s", type).isNotNull();
            assertThat(c.subject()).as("subject for %s", type).isNotBlank();
            assertThat(c.body()).as("body for %s", type).isNotBlank();
            assertThat(catalogue.validateTokens(type, c.subject(), c.body()))
                .as("default %s uses only permitted tokens", type).isEmpty();
        }
    }

    @Test
    void everyTypeAndTone_hasNonEmptyTokenValidPreset() {
        for (EmailMessageType type : EmailMessageType.values()) {
            for (TonePreset tone : TonePreset.values()) {
                Content c = tones.forTypeAndTone(type, tone);
                assertThat(c).as("preset %s/%s", type, tone).isNotNull();
                assertThat(c.subject()).isNotBlank();
                assertThat(c.body()).isNotBlank();
                assertThat(catalogue.validateTokens(type, c.subject(), c.body()))
                    .as("preset %s/%s uses only permitted tokens", type, tone).isEmpty();
            }
        }
    }
}
