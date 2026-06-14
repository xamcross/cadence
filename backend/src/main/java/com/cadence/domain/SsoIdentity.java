package com.cadence.domain;

import java.time.Instant;

/**
 * Embedded link between a Member and their stable identifier at the OIDC provider.
 * provider/subject are taken from a validated ID token at link time (SEC-10).
 */
public class SsoIdentity {

    private String provider;
    private String subject;
    private Instant linkedAt;

    public SsoIdentity() {}

    public SsoIdentity(String provider, String subject, Instant linkedAt) {
        this.provider = provider;
        this.subject = subject;
        this.linkedAt = linkedAt;
    }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public Instant getLinkedAt() { return linkedAt; }
    public void setLinkedAt(Instant linkedAt) { this.linkedAt = linkedAt; }
}
