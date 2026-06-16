package com.cadence.emailtemplate;

import com.cadence.domain.EmailMessageType;
import com.cadence.domain.RenderedMessage;
import com.cadence.service.MergeRenderer;
import com.cadence.service.MergeTokenCatalogue;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SC-002/003/006: the renderer's safety guarantees, as pure deterministic unit tests (no Spring).
 * substitute-all-occurrences, absent/empty -> marker, byte-identical determinism, body HTML escaping
 * (UTF-8), subject control/line-separator stripping, URL-token anchor + scheme guard, and the
 * hostile-surrounding-markup spoof case.
 */
class MergeRendererTest {

    private final MergeRenderer renderer = new MergeRenderer(new MergeTokenCatalogue());
    private static final EmailMessageType T = EmailMessageType.INVITATION;

    private static Map<String, String> values(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void substitutesEveryOccurrence_noResidualTokens() {
        RenderedMessage r = renderer.render(T, "Hi {{candidate_name}}",
            "Hi {{candidate_name}}, again {{candidate_name}}", values("candidate_name", "Dana"));
        assertThat(r.bodyText()).isEqualTo("Hi Dana, again Dana");
        assertThat(r.bodyText()).doesNotContain("{{");
        assertThat(r.subject()).isEqualTo("Hi Dana");
        assertThat(r.missingFields()).isEmpty();
    }

    @Test
    void absentAndEmptyValue_renderMissingMarker() {
        RenderedMessage absent = renderer.render(T, "S", "Hi {{candidate_name}}", values());
        assertThat(absent.bodyText()).isEqualTo("Hi [[missing:candidate_name]]");
        assertThat(absent.bodyHtml()).contains("[[missing:candidate_name]]");
        assertThat(absent.missingFields()).containsExactly("candidate_name");

        RenderedMessage empty = renderer.render(T, "S", "Hi {{candidate_name}}", values("candidate_name", ""));
        assertThat(empty.bodyText()).isEqualTo("Hi [[missing:candidate_name]]");
        assertThat(empty.missingFields()).containsExactly("candidate_name");
    }

    @Test
    void render_isByteIdenticalAcrossRuns() {
        Map<String, String> v = values("candidate_name", "Dana", "scheduling_link", "https://x/abc");
        RenderedMessage a = renderer.render(T, "Hi {{candidate_name}}", "Go {{scheduling_link}}", v);
        RenderedMessage b = renderer.render(T, "Hi {{candidate_name}}", "Go {{scheduling_link}}", v);
        assertThat(a.subject()).isEqualTo(b.subject());
        assertThat(a.bodyText()).isEqualTo(b.bodyText());
        assertThat(a.bodyHtml()).isEqualTo(b.bodyHtml());
        assertThat(a.missingFields()).isEqualTo(b.missingFields());
    }

    @Test
    void bodyHtml_escapesScript_andPreservesNonLatin() {
        RenderedMessage r = renderer.render(T, "S", "Hi {{candidate_name}}",
            values("candidate_name", "<script>alert(1)</script> Jose Li 李"));
        assertThat(r.bodyHtml()).contains("&lt;script&gt;");
        assertThat(r.bodyHtml()).doesNotContain("<script>");
        assertThat(r.bodyHtml()).contains("李"); // non-Latin char preserved (UTF-8 escape overload)
    }

    @Test
    void subject_stripsCrlfAndUnicodeLineSeparators() {
        // CR/LF (SMTP-header injection) AND the Unicode line separators htmlEscape passes through:
        // U+2028, U+2029, U+0085 (NEL), plus a control char (U+0007).
        int[] cps = {0x2028, 0x2029, 0x0085, 0x000D, 0x000A, 0x0007};
        String seps = new String(cps, 0, cps.length);
        RenderedMessage r = renderer.render(T, "Hi {{candidate_name}}", "B",
            values("candidate_name", "A" + seps + "BCD"));
        assertThat(r.subject()).isEqualTo("Hi ABCD");
        for (int cp : cps) {
            assertThat(r.subject()).doesNotContain(new String(new int[]{cp}, 0, 1));
        }
    }

    @Test
    void bodyValueWithNewline_doesNotProduceMarkup() {
        // A merge VALUE newline must NOT become a <br> in escaped content; only AUTHORED newlines do.
        RenderedMessage r = renderer.render(T, "S", "Name: {{candidate_name}}",
            values("candidate_name", "Line1\nLine2"));
        assertThat(r.bodyHtml()).isEqualTo("Name: Line1\nLine2");
    }

    @Test
    void urlToken_rendersHrefEqualsText_andRejectsNonHttpScheme() {
        RenderedMessage ok = renderer.render(T, "S", "Go {{scheduling_link}}",
            values("scheduling_link", "https://cadence.app/s/abc"));
        assertThat(ok.bodyHtml()).contains("<a href=\"https://cadence.app/s/abc\">https://cadence.app/s/abc</a>");

        RenderedMessage bad = renderer.render(T, "S", "Go {{scheduling_link}}",
            values("scheduling_link", "javascript:alert(1)"));
        assertThat(bad.bodyHtml()).contains("[[invalid_url:scheduling_link]]");
        assertThat(bad.bodyHtml()).doesNotContain("<a href");
    }

    @Test
    void hostileAuthoredMarkupAroundUrlToken_isEscapedToInertText() {
        RenderedMessage r = renderer.render(T, "S",
            "Click {{scheduling_link}} <a href=\"http://evil\">free</a>",
            values("scheduling_link", "https://cadence.app/s/abc"));
        // the system token renders as a proper href==text anchor...
        assertThat(r.bodyHtml()).contains("<a href=\"https://cadence.app/s/abc\">https://cadence.app/s/abc</a>");
        // ...while the recruiter-authored anchor is escaped to inert text (no second live href)
        assertThat(r.bodyHtml()).contains("&lt;a href=&quot;http://evil&quot;&gt;");
        assertThat(r.bodyHtml()).doesNotContain("<a href=\"http://evil\">");
    }

    @Test
    void missingFields_orderedByFirstOccurrence_bodyThenSubject() {
        RenderedMessage r = renderer.render(T,
            "Zone {{time_zone}}",
            "Stage {{stage_name}} for {{candidate_name}}",
            values());
        assertThat(r.missingFields()).containsExactly("stage_name", "candidate_name", "time_zone");
    }

    @Test
    void newlinesBecomeBrInHtml_butPreservedInText() {
        RenderedMessage r = renderer.render(T, "S", "Line1\nLine2", values());
        assertThat(r.bodyHtml()).isEqualTo("Line1<br>Line2");
        assertThat(r.bodyText()).isEqualTo("Line1\nLine2");
    }
}
