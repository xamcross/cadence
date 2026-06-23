package com.cadence.service;

import com.cadence.domain.EmailMessageType;
import com.cadence.domain.MergeToken;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The per-{@link EmailMessageType} permitted merge-token subset + the token lexis (F21 D4/D5). A valid
 * token is exactly {@code \{\{[a-z_]+\}\}}; the "looks-like-a-token" scan {@code \{\{[^{}]*\}\}} catches
 * malformed/unknown tokens at save so a broken {@code {{token}}} can never persist (FR-004). Validation
 * messages are VALUE-FREE (field + rule, never the matched token text — which is user input).
 */
@Component
public class MergeTokenCatalogue {

    /** A complete, well-formed token. */
    public static final Pattern VALID_TOKEN = Pattern.compile("\\{\\{([a-z_]+)\\}\\}");
    /** "Looks like a token" — used to detect malformed/unknown tokens at save time. */
    public static final Pattern TOKEN_SCAN = Pattern.compile("\\{\\{[^{}]*\\}\\}");

    // PRIVACY_LINK is universal: every built-in candidate-facing message type flows through
    // EmailTemplateService.renderForSend, which injects the constant {{privacy_link}} value, so the
    // token must be permitted for every type or it would render the literal {{privacy_link}}
    // (GDPR Art. 14 reach, contract C-LINK-4 / research D8).
    private static final Set<MergeToken> UNIVERSAL =
        EnumSet.of(MergeToken.CANDIDATE_NAME, MergeToken.RECRUITER_NAME, MergeToken.WORKSPACE_NAME,
            MergeToken.PRIVACY_LINK);

    private final Map<EmailMessageType, Set<MergeToken>> permitted = new EnumMap<>(EmailMessageType.class);

    public MergeTokenCatalogue() {
        put(EmailMessageType.INVITATION, MergeToken.STAGE_NAME, MergeToken.SCHEDULING_LINK,
            MergeToken.TIME_ZONE, MergeToken.EXPECTED_DATE);
        Set<MergeToken> interview = EnumSet.of(MergeToken.STAGE_NAME, MergeToken.INTERVIEW_DATE,
            MergeToken.INTERVIEW_TIME, MergeToken.TIME_ZONE, MergeToken.LOCATION, MergeToken.RESCHEDULE_LINK);
        // F30: the CONFIRMATION carries the candidate status-page link (STATUS_LINK) so the confirmed candidate
        // can track their ongoing process. The reminders (below) intentionally do NOT — they prompt for confirm.
        Set<MergeToken> confirmation = EnumSet.copyOf(interview);
        confirmation.add(MergeToken.STATUS_LINK);
        put(EmailMessageType.CONFIRMATION, confirmation.toArray(new MergeToken[0]));
        // F23: the reminders carry the attendance-confirmation link (CONFIRM_LINK) in addition to the
        // interview tokens. CONFIRMATION keeps RESCHEDULE_LINK only (a confirmed booking is not a confirm prompt).
        Set<MergeToken> reminder = EnumSet.copyOf(interview);
        reminder.add(MergeToken.CONFIRM_LINK);
        put(EmailMessageType.REMINDER_24H, reminder.toArray(new MergeToken[0]));
        put(EmailMessageType.REMINDER_1H, reminder.toArray(new MergeToken[0]));
        put(EmailMessageType.HOLD_UPDATE, MergeToken.STATUS_LINK, MergeToken.EXPECTED_DATE);
        put(EmailMessageType.REJECTION, MergeToken.STATUS_LINK);
        put(EmailMessageType.FEEDBACK_REQUEST, MergeToken.STAGE_NAME, MergeToken.FEEDBACK_LINK);
        put(EmailMessageType.SLA_HOLDING, MergeToken.STATUS_LINK, MergeToken.EXPECTED_DATE);
        put(EmailMessageType.CANCELLATION); // F20: universal tokens only (candidate/workspace/recruiter name).
    }

    private void put(EmailMessageType type, MergeToken... extras) {
        Set<MergeToken> set = EnumSet.copyOf(UNIVERSAL);
        set.addAll(List.of(extras));
        permitted.put(type, set);
    }

    public boolean isPermitted(EmailMessageType type, MergeToken token) {
        Set<MergeToken> set = permitted.get(type);
        return set != null && set.contains(token);
    }

    public List<String> permittedTokenNames(EmailMessageType type) {
        Set<MergeToken> set = permitted.getOrDefault(type, Set.of());
        return set.stream().map(MergeToken::token).sorted().toList();
    }

    public int tokenCount(String text) {
        if (text == null) return 0;
        Matcher m = TOKEN_SCAN.matcher(text);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    /**
     * Validate every token occurrence in subject+body against the per-type allow-list. Returns a
     * VALUE-FREE field->message map (empty == valid). Never echoes the matched token text.
     */
    public Map<String, String> validateTokens(EmailMessageType type, String subject, String body) {
        Map<String, String> errors = new LinkedHashMap<>();
        checkField("subject", type, subject, errors);
        checkField("body", type, body, errors);
        return errors;
    }

    private void checkField(String field, EmailMessageType type, String text, Map<String, String> errors) {
        if (text == null) return;
        Matcher scan = TOKEN_SCAN.matcher(text);
        while (scan.find()) {
            String occurrence = scan.group();
            Matcher valid = VALID_TOKEN.matcher(occurrence);
            if (!valid.matches()) {
                errors.put(field, "Contains a malformed merge token (use {{snake_case}} with no spaces).");
                return;
            }
            MergeToken token = MergeToken.byToken(valid.group(1)).orElse(null);
            if (token == null) {
                errors.put(field, "Contains an unknown merge token.");
                return;
            }
            if (!isPermitted(type, token)) {
                errors.put(field, "Contains a merge token not allowed for this message type.");
                return;
            }
        }
    }
}
