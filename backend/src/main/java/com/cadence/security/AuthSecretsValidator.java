package com.cadence.security;

import com.cadence.config.AuthProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;

/**
 * Fail-fast guard (BE-10): on the {@code prod} profile the application context MUST NOT start if any
 * auth secret is blank or still set to an insecure local-dev default. Fly.io runs with
 * {@code SPRING_PROFILES_ACTIVE=prod} and must supply real Fly secrets.
 */
@Component
@Profile("prod")
public class AuthSecretsValidator {

    private final AuthProperties props;

    public AuthSecretsValidator(AuthProperties props) {
        this.props = props;
    }

    @PostConstruct
    void validate() {
        List<String> bad = new ArrayList<>();
        check(bad, "JWT_SECRET", props.getSession().getSecret());
        check(bad, "PII_ENC_KEY", props.getCrypto().getPiiKey());
        check(bad, "PII_PEPPER", props.getCrypto().getPiiPepper());
        check(bad, "TOKEN_PEPPER", props.getCrypto().getTokenPepper());
        check(bad, "IP_PEPPER", props.getCrypto().getIpPepper());
        if (!bad.isEmpty()) {
            throw new IllegalStateException(
                "Refusing to start in prod: missing or insecure auth secrets -> " + bad
                    + ". Set them via `fly secrets set`.");
        }
    }

    private void check(List<String> bad, String name, String value) {
        if (value == null || value.isBlank() || value.startsWith("dev-")) {
            bad.add(name);
        }
    }
}
