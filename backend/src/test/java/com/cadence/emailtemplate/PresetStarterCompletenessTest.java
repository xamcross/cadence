package com.cadence.emailtemplate;

import com.cadence.domain.EmailMessageType;
import com.cadence.service.BuiltInEmailTemplates;
import com.cadence.service.BuiltInEmailTemplates.Content;
import com.cadence.service.InterviewTemplatePresetCatalogue;
import com.cadence.service.InterviewTemplatePresetCatalogue.Preset;
import com.cadence.service.MergeTokenCatalogue;
import com.cadence.service.PresetEmailStarterCatalogue;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every (preset, type) pair declared by the interview catalogue has a starter that is non-empty,
 * token-valid for its message type, and keeps the built-in privacy footer; no starter exists for an
 * undeclared pair. Starters derive from the built-in of the same type + a token-free paragraph, so
 * these properties hold by construction - this test keeps them from regressing.
 */
class PresetStarterCompletenessTest {

    private static final String PRIVACY = "View our Privacy Notice:";

    private final BuiltInEmailTemplates builtins = new BuiltInEmailTemplates();
    private final PresetEmailStarterCatalogue starters = new PresetEmailStarterCatalogue(builtins);
    private final InterviewTemplatePresetCatalogue presets = new InterviewTemplatePresetCatalogue();
    private final MergeTokenCatalogue tokens = new MergeTokenCatalogue();

    @Test
    void everyDeclaredPair_hasTokenValidStarter_keepingThePrivacyFooter() {
        for (Preset p : presets.all()) {
            for (EmailMessageType type : p.starterEmailTypes()) {
                Content c = starters.forPresetAndType(p.key(), type);
                assertThat(c).as(p.key() + "/" + type).isNotNull();
                assertThat(c.subject()).as(p.key() + "/" + type).isNotBlank();
                assertThat(c.body()).as(p.key() + "/" + type).isNotBlank();
                if (builtins.forType(type).body().contains(PRIVACY)) {
                    assertThat(c.body()).as("privacy footer " + p.key() + "/" + type).contains(PRIVACY);
                }
                assertThat(tokens.validateTokens(type, c.subject(), c.body()))
                    .as("merge tokens " + p.key() + "/" + type).isEmpty();
            }
        }
    }

    @Test
    void noStarterExists_forAnUndeclaredPair() {
        for (Preset p : presets.all()) {
            for (EmailMessageType type : EmailMessageType.values()) {
                if (!p.starterEmailTypes().contains(type)) {
                    assertThat(starters.forPresetAndType(p.key(), type)).as(p.key() + "/" + type).isNull();
                }
            }
        }
    }

    @Test
    void starterBody_containsThePresetSpecificParagraph() {
        Content c = starters.forPresetAndType(
            com.cadence.domain.InterviewPresetKey.TECH_DEEP_DIVE, EmailMessageType.INVITATION);
        assertThat(c.body()).contains("development environment");
    }
}
