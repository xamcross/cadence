package com.cadence.service;

import com.cadence.domain.EmailMessageType;
import com.cadence.domain.TonePreset;
import com.cadence.service.BuiltInEmailTemplates.Content;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Code-shipped starter wording per {@code (EmailMessageType, TonePreset)} (F21 D10). Applying a preset
 * replaces the override's subject+body (then it stays editable). Each tone reuses the built-in default's
 * token-safe core and only varies the greeting/sign-off, so token-validity holds across all tones. A
 * {@code @PostConstruct} completeness check fails fast if any (type,tone) pair is missing (SC-001).
 */
@Component
public class TonePresetCatalogue {

    private final BuiltInEmailTemplates builtins;
    private final Map<String, Content> presets = new HashMap<>();

    public TonePresetCatalogue(BuiltInEmailTemplates builtins) {
        this.builtins = builtins;
    }

    private static String key(EmailMessageType type, TonePreset tone) {
        return type.name() + "|" + tone.name();
    }

    @PostConstruct
    void build() {
        for (EmailMessageType type : EmailMessageType.values()) {
            Content base = builtins.forType(type);
            for (TonePreset tone : TonePreset.values()) {
                presets.put(key(type, tone), flavour(base, tone));
            }
        }
        for (EmailMessageType type : EmailMessageType.values()) {
            for (TonePreset tone : TonePreset.values()) {
                Content c = presets.get(key(type, tone));
                if (c == null || c.subject() == null || c.subject().isBlank()
                    || c.body() == null || c.body().isBlank()) {
                    throw new IllegalStateException("Missing tone preset for " + type.name() + "/" + tone.name());
                }
            }
        }
    }

    /** Wrap the token-safe core body with tone-specific greeting/sign-off; never touches tokens. */
    private Content flavour(Content base, TonePreset tone) {
        return switch (tone) {
            case FORMAL -> new Content(base.subject(),
                "Dear {{candidate_name}},\n\n" + stripGreeting(base.body()));
            case FRIENDLY -> new Content(base.subject(),
                "Hi {{candidate_name}}! 👋\n\n" + stripGreeting(base.body()));
            case CONCISE -> new Content(base.subject(), concise(base.body()));
        };
    }

    /** Drop the built-in "Hi {{candidate_name}}," opening line so the tone greeting replaces it. */
    private String stripGreeting(String body) {
        int nl = body.indexOf("\n\n");
        return nl >= 0 ? body.substring(nl + 2) : body;
    }

    private String concise(String body) {
        // Keep the substantive lines (those containing a token or a link), drop pleasantries.
        return body.replace("\n\n", "\n").trim();
    }

    public Content forTypeAndTone(EmailMessageType type, TonePreset tone) {
        return presets.get(key(type, tone));
    }
}
