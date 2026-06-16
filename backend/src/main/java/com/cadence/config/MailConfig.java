package com.cadence.config;

import com.cadence.domain.WorkspaceConfig;
import com.cadence.repository.WorkspaceConfigRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds/caches a {@link JavaMailSender} for the F22 transport (research D2). Two sender sources:
 *
 * <ul>
 *   <li>the <b>app-level default</b> from {@link EmailDeliveryProperties} — sends member/operational mail
 *       (invites/resets/system-alert, which have no workspace credential) and is the candidate-send
 *       fallback;</li>
 *   <li>a <b>per-workspace</b> sender built from the F03 {@link WorkspaceConfig} decrypted
 *       {@code emailProviderCredential} (the credential is read only here, never logged).</li>
 * </ul>
 *
 * <p>Senders are cached per credential identity (host/port/username/credential) so a credential rotation
 * yields a fresh sender. When neither a workspace credential nor the app-level default password is
 * present for a candidate send, {@link MailSenderSelector#forWorkspace} returns an
 * {@link MailSenderSelector.Selection} with a null sender (the {@code NO_PROVIDER_CONFIG} signal, FR-004)
 * rather than throwing.
 */
@Configuration
public class MailConfig {

    /** The shared app-level default sender (member/operational mail + candidate fallback). */
    @Bean
    public JavaMailSender appDefaultMailSender(EmailDeliveryProperties props) {
        return build(props.getSmtp().getHost(), props.getSmtp().getPort(),
            props.getSmtp().getUsername(), props.getSmtp().getPassword(), props.getReadTimeout().toMillis());
    }

    @Bean
    public MailSenderSelector mailSenderSelector(EmailDeliveryProperties props,
                                                 JavaMailSender appDefaultMailSender,
                                                 WorkspaceConfigRepository configs) {
        return new MailSenderSelector(props, appDefaultMailSender, configs);
    }

    static JavaMailSender build(String host, int port, String username, String password, long readTimeoutMillis) {
        JavaMailSenderImpl impl = new JavaMailSenderImpl();
        impl.setHost(host);
        impl.setPort(port);
        if (username != null && !username.isBlank()) {
            impl.setUsername(username);
        }
        if (password != null && !password.isBlank()) {
            impl.setPassword(password);
        }
        Properties p = impl.getJavaMailProperties();
        p.put("mail.transport.protocol", "smtp");
        p.put("mail.smtp.auth", String.valueOf(password != null && !password.isBlank()));
        p.put("mail.smtp.starttls.enable", "true");
        p.put("mail.smtp.timeout", String.valueOf(readTimeoutMillis));
        p.put("mail.smtp.connectiontimeout", String.valueOf(readTimeoutMillis));
        p.put("mail.smtp.writetimeout", String.valueOf(readTimeoutMillis));
        return impl;
    }

    /**
     * Selects the {@link JavaMailSender} for a given workspace (per-workspace F03 credential, else the
     * app-level default). Caches by credential identity. Returns a null sender (NO_PROVIDER_CONFIG)
     * rather than throwing when no provider is configured for a candidate send.
     */
    public static class MailSenderSelector {

        private final EmailDeliveryProperties props;
        private final JavaMailSender appDefault;
        private final WorkspaceConfigRepository configs;
        private final Map<String, JavaMailSender> cache = new ConcurrentHashMap<>();

        MailSenderSelector(EmailDeliveryProperties props, JavaMailSender appDefault,
                           WorkspaceConfigRepository configs) {
            this.props = props;
            this.appDefault = appDefault;
            this.configs = configs;
        }

        /** Whether the app-level default password is configured (member/operational mail depends on it). */
        private boolean appDefaultConfigured() {
            String pw = props.getSmtp().getPassword();
            return pw != null && !pw.isBlank();
        }

        /** The app-level default sender for member/operational mail (returns null if unconfigured). */
        public JavaMailSender appDefault() {
            return appDefaultConfigured() ? appDefault : null;
        }

        /**
         * The sender for a workspace candidate send: the per-workspace F03 credential if set, else the
         * app-level default, else a {@code present=false} selection (NO_PROVIDER_CONFIG).
         */
        public Selection forWorkspace(String workspaceId) {
            WorkspaceConfig cfg = configs.findByWorkspaceId(workspaceId).orElse(null);
            if (cfg != null && cfg.getEmailProviderCredential() != null
                    && !cfg.getEmailProviderCredential().isBlank()) {
                // getEmailProviderCredential() is converter-decrypted plaintext (read-only here, never logged).
                String credential = cfg.getEmailProviderCredential();
                // Discriminate on a SHA-256 of the credential (collision-resistant; never the raw hashCode).
                // The digest is a cache key only — neither the credential nor its hash is ever logged.
                String cacheKey = workspaceId + "|" + props.getSmtp().getHost() + "|"
                    + props.getSmtp().getPort() + "|" + props.getSmtp().getUsername() + "|" + sha256Hex(credential);
                JavaMailSender s = cache.computeIfAbsent(cacheKey, k -> build(
                    props.getSmtp().getHost(), props.getSmtp().getPort(),
                    props.getSmtp().getUsername(), credential, props.getReadTimeout().toMillis()));
                return new Selection(s);
            }
            if (appDefaultConfigured()) {
                return new Selection(appDefault);
            }
            return new Selection(null);
        }

        /**
         * SHA-256 hex of the credential, used only as a collision-resistant cache discriminator so a
         * credential rotation yields a distinct key (and a fresh sender). The digest is never logged.
         */
        private static String sha256Hex(String credential) {
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(credential.getBytes(StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder(digest.length * 2);
                for (byte b : digest) {
                    sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
                }
                return sb.toString();
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 unavailable", e); // every JRE ships SHA-256
            }
        }

        /** A resolved sender, or {@code present()==false} meaning NO_PROVIDER_CONFIG (no sender available). */
        public record Selection(JavaMailSender sender) {
            public boolean present() { return sender != null; }
        }
    }
}
