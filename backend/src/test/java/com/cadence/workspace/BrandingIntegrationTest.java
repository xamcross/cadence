package com.cadence.workspace;

import com.cadence.api.WorkspaceDtos;
import com.cadence.domain.AuthAuditEvent;
import com.cadence.domain.AuthEventType;
import com.cadence.domain.Member;
import com.cadence.domain.Role;
import com.cadence.service.WorkspaceConfigService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** T031 (US3): logo validation (size/magic/SVG/dims/corrupt), colour regex, per-attribute default,
 *  unset+audit, and the public branding reads. */
class BrandingIntegrationTest extends WorkspaceItBase {

    @Autowired WorkspaceConfigService service;

    private Cookie configuredAdmin() {
        Member admin = member("admin@x.com", Role.ADMIN);
        service.completeSetup("ws1", admin.getId(), new WorkspaceDtos.SetupRequest("Acme", "Europe/London",
            new WorkspaceDtos.WorkingHoursDto(LocalTime.of(9, 0), LocalTime.of(17, 0)), 5, 365, true));
        return cookie(admin);
    }

    private static byte[] png(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private MockMultipartHttpServletRequestBuilder upload(byte[] bytes, String filename, String contentType) {
        MockMultipartHttpServletRequestBuilder b = multipart("/api/internal/workspace/logo");
        b.file(new MockMultipartFile("file", filename, contentType, bytes));
        return b;
    }

    @Test
    void validPng_accepted() throws Exception {
        Cookie admin = configuredAdmin();
        mvc.perform(upload(png(64, 64), "logo.png", "image/png").cookie(admin).with(csrf()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.hasLogo").value(true));
        assertThat(logos.findByWorkspaceId("ws1")).isPresent();
        assertThat(configs.findByWorkspaceId("ws1").orElseThrow().isHasLogo()).isTrue();
    }

    @Test
    void svg_rejected() throws Exception {
        Cookie admin = configuredAdmin();
        byte[] svg = "<svg xmlns='http://www.w3.org/2000/svg'><script>1</script></svg>"
            .getBytes(StandardCharsets.UTF_8);
        mvc.perform(upload(svg, "logo.svg", "image/svg+xml").cookie(admin).with(csrf()))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_logo"));
        assertThat(logos.findByWorkspaceId("ws1")).isEmpty();
    }

    @Test
    void svgRenamedAsPng_rejectedByMagicByte() throws Exception {
        Cookie admin = configuredAdmin();
        byte[] svg = "<svg xmlns='http://www.w3.org/2000/svg'></svg>".getBytes(StandardCharsets.UTF_8);
        mvc.perform(upload(svg, "logo.png", "image/png").cookie(admin).with(csrf()))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_logo"));
        assertThat(logos.findByWorkspaceId("ws1")).isEmpty();
    }

    @Test
    void oversize_rejected() throws Exception {
        Cookie admin = configuredAdmin();
        byte[] big = new byte[1024 * 1024 + 10];
        // PNG magic prefix so it passes the type gate but fails the size gate first.
        byte[] sig = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(sig, 0, big, 0, sig.length);
        mvc.perform(upload(big, "logo.png", "image/png").cookie(admin).with(csrf()))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_logo"));
    }

    @Test
    void corruptButValidMagic_rejected() throws Exception {
        Cookie admin = configuredAdmin();
        byte[] sig = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4, 5, 6};
        mvc.perform(upload(sig, "logo.png", "image/png").cookie(admin).with(csrf()))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_logo"));
        assertThat(logos.findByWorkspaceId("ws1")).isEmpty();
    }

    @Test
    void oversizeDimensions_rejectedBeforeDecode() throws Exception {
        Cookie admin = configuredAdmin();
        // 3000x10 real PNG: width > 2048 -> rejected by the header-only dimension gate (D6).
        mvc.perform(upload(png(3000, 10), "logo.png", "image/png").cookie(admin).with(csrf()))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_logo"));
        assertThat(logos.findByWorkspaceId("ws1")).isEmpty();
    }

    @Test
    void brandColor_regexValidated() throws Exception {
        Cookie admin = configuredAdmin();
        mvc.perform(put("/api/internal/workspace/branding").cookie(admin).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"brandColor\":\"red\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(put("/api/internal/workspace/branding").cookie(admin).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"brandColor\":\"#AABBCC\"}"))
            .andExpect(status().isOk());
    }

    @Test
    void publicBranding_returnsDefaultPerAttribute_whenUnset() throws Exception {
        // No session, nothing configured -> default colour + logo URL, no setting/credential leak.
        mvc.perform(get("/api/public/workspace/branding"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.brandColor").value("#1F2937"))
            .andExpect(jsonPath("$.logoUrl").value("/api/public/workspace/logo"))
            .andExpect(jsonPath("$.credentialSet").doesNotExist())
            .andExpect(jsonPath("$.configured").doesNotExist());
        mvc.perform(get("/api/public/workspace/logo")).andExpect(status().isOk())
            .andExpect(status().isOk());
    }

    @Test
    void unsetLogo_returnsPlaceholder_andAudited() throws Exception {
        Cookie admin = configuredAdmin();
        mvc.perform(upload(png(48, 48), "logo.png", "image/png").cookie(admin).with(csrf()))
            .andExpect(status().isOk());
        mvc.perform(delete("/api/internal/workspace/logo").cookie(admin).with(csrf()))
            .andExpect(status().isNoContent());
        assertThat(logos.findByWorkspaceId("ws1")).isEmpty();
        assertThat(configs.findByWorkspaceId("ws1").orElseThrow().isHasLogo()).isFalse();
        mvc.perform(get("/api/public/workspace/logo")).andExpect(status().isOk()); // placeholder
        long logoAudits = mongoTemplate.findAll(AuthAuditEvent.class).stream()
            .filter(a -> a.getEventType() == AuthEventType.WORKSPACE_CONFIG_CHANGED
                && "logo".equals(a.getOutcome())).count();
        assertThat(logoAudits).isGreaterThanOrEqualTo(2); // upload + unset
    }
}
