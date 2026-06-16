package com.cadence.service;

import com.cadence.domain.EmailMessageType;
import com.cadence.domain.MergeToken;
import com.cadence.domain.RenderedMessage;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * In-house {@code {{token}}} substitution + channel-appropriate neutralisation (F21 D3/D4/D12). NO
 * templating engine (also the SSTI control), NO message-transport dependency — rendering is pure and
 * side-effect-free (SC-010); dispatch is F22. Two DISTINCT transforms (never sharing an encoder):
 *
 * <ul>
 *   <li><b>subject</b> — substitute then STRIP control + line-separator chars
 *       ({@code U+0000-001F, U+007F-009F, U+2028, U+2029}); NOT HTML-escaped (a subject is plain text).
 *       Defeats SMTP-header / CRLF / Unicode-line-separator injection (FR-015).
 *   <li><b>body HTML</b> — normalise newlines, {@code HtmlUtils.htmlEscape(body,"UTF-8")} (UTF-8 overload
 *       preserves non-Latin names), convert the AUTHORED {@code \n -> <br>} BEFORE substitution (so a merge
 *       value's own newline can never become a {@code <br>} reintroduced into escaped/attribute content),
 *       then substitute (values escaped; URL-typed tokens as {@code href==text} anchors restricted to
 *       {@code http(s)}). A {@code <script>} value is inert; a recruiter cannot author a raw anchor, so no
 *       link spoofing (FR-016); a {@code javascript:} URL renders an inert {@code [[invalid_url:<token>]]} marker.
 * </ul>
 *
 * A token whose value is absent OR empty renders {@code [[missing:<token>]]} and is added to
 * {@code missingFields} in first-occurrence order (body then subject) — never a raw token, never a silent
 * blank (FR-014). Deterministic: identical inputs -> byte-identical output.
 */
@Component
public class MergeRenderer {

    /** Control + Unicode line-separator characters stripped from the subject line. */
    private static final String CONTROL_AND_SEPARATORS = "[\\u0000-\\u001F\\u007F-\\u009F\\u2028\\u2029]";

    private final MergeTokenCatalogue catalogue;

    public MergeRenderer(MergeTokenCatalogue catalogue) {
        this.catalogue = catalogue;
    }

    public RenderedMessage render(EmailMessageType type, String subject, String body, Map<String, String> values) {
        Set<String> missing = new LinkedHashSet<>();
        String bodyText = substitutePlain(body, type, values, missing);
        String bodyHtml = substituteHtml(body, type, values, missing);
        String renderedSubject = stripControl(substitutePlain(subject, type, values, missing));
        return new RenderedMessage(renderedSubject, bodyText, bodyHtml, new ArrayList<>(missing));
    }

    private String substitutePlain(String text, EmailMessageType type, Map<String, String> values, Set<String> missing) {
        if (text == null) return "";
        Matcher m = MergeTokenCatalogue.VALID_TOKEN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            MergeToken token = MergeToken.byToken(m.group(1)).orElse(null);
            String rep;
            if (token != null && catalogue.isPermitted(type, token)) {
                String value = values == null ? null : values.get(token.token());
                if (isMissing(value)) {
                    rep = missingMarker(token);
                    missing.add(token.token());
                } else {
                    rep = value;
                }
            } else {
                rep = m.group(); // defensive: a non-permitted token never survives save — leave literal
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String substituteHtml(String body, EmailMessageType type, Map<String, String> values, Set<String> missing) {
        if (body == null) return "";
        String normalised = body.replace("\r\n", "\n").replace("\r", "\n");
        // Escape, then convert the AUTHORED newlines to <br> BEFORE substitution — so a merge VALUE that
        // itself contains a newline can never become a <br> reintroduced into already-escaped/attribute
        // content (the value's own newline stays inert literal whitespace). {{ }} are not escaped, so
        // tokens survive both steps.
        String escaped = HtmlUtils.htmlEscape(normalised, "UTF-8").replace("\n", "<br>");
        Matcher m = MergeTokenCatalogue.VALID_TOKEN.matcher(escaped);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            MergeToken token = MergeToken.byToken(m.group(1)).orElse(null);
            String rep;
            if (token != null && catalogue.isPermitted(type, token)) {
                String value = values == null ? null : values.get(token.token());
                if (isMissing(value)) {
                    rep = missingMarker(token);
                    missing.add(token.token());
                } else if (token.isUrlTyped()) {
                    rep = isHttpUrl(value)
                        ? "<a href=\"" + esc(value) + "\">" + esc(value) + "</a>"
                        : "[[invalid_url:" + token.token() + "]]";
                } else {
                    rep = esc(value);
                }
            } else {
                rep = m.group();
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static boolean isMissing(String value) {
        return value == null || value.isEmpty();
    }

    private static String missingMarker(MergeToken token) {
        return "[[missing:" + token.token() + "]]";
    }

    private static boolean isHttpUrl(String value) {
        String low = value.toLowerCase(Locale.ROOT);
        return low.startsWith("http://") || low.startsWith("https://");
    }

    private static String esc(String value) {
        return HtmlUtils.htmlEscape(value, "UTF-8");
    }

    private static String stripControl(String s) {
        return s.replaceAll(CONTROL_AND_SEPARATORS, "");
    }
}
