package com.cadence.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Binds the {@code auth.*} configuration block (research D1/D5/D12). */
@ConfigurationProperties(prefix = "auth")
public class AuthProperties {

    private String spaBaseUrl = "http://localhost:4200";
    private final Session session = new Session();
    private final Lockout lockout = new Lockout();
    private final Ttl invitation = new Ttl(Duration.ofHours(72));
    private final Ttl passwordReset = new Ttl(Duration.ofHours(1));
    private final Crypto crypto = new Crypto();
    private final Rbac rbac = new Rbac();

    public String getSpaBaseUrl() { return spaBaseUrl; }
    public void setSpaBaseUrl(String spaBaseUrl) { this.spaBaseUrl = spaBaseUrl; }
    public Session getSession() { return session; }
    public Lockout getLockout() { return lockout; }
    public Ttl getInvitation() { return invitation; }
    public Ttl getPasswordReset() { return passwordReset; }
    public Crypto getCrypto() { return crypto; }
    public Rbac getRbac() { return rbac; }

    /** F02 RBAC settings (research D8). */
    public static class Rbac {
        /** Min interval between AUTHORIZATION_DENIED audits for the same (member,event) — anti-amplification. */
        private Duration deniedAuditWindow = Duration.ofMinutes(1);

        public Duration getDeniedAuditWindow() { return deniedAuditWindow; }
        public void setDeniedAuditWindow(Duration deniedAuditWindow) { this.deniedAuditWindow = deniedAuditWindow; }
    }

    public static class Session {
        private String cookieName = "cad_session";
        private Duration absoluteTtl = Duration.ofHours(8);
        private Duration idleTtl = Duration.ofMinutes(30);
        private Duration clockSkew = Duration.ofSeconds(60);
        private String secret = "";
        private String previousSecret = "";
        /** Secure flag on the session cookie. True in prod (HTTPS); set false for local http dev. */
        private boolean secureCookie = true;

        public boolean isSecureCookie() { return secureCookie; }
        public void setSecureCookie(boolean secureCookie) { this.secureCookie = secureCookie; }

        public String getCookieName() { return cookieName; }
        public void setCookieName(String cookieName) { this.cookieName = cookieName; }
        public Duration getAbsoluteTtl() { return absoluteTtl; }
        public void setAbsoluteTtl(Duration absoluteTtl) { this.absoluteTtl = absoluteTtl; }
        public Duration getIdleTtl() { return idleTtl; }
        public void setIdleTtl(Duration idleTtl) { this.idleTtl = idleTtl; }
        public Duration getClockSkew() { return clockSkew; }
        public void setClockSkew(Duration clockSkew) { this.clockSkew = clockSkew; }
        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
        public String getPreviousSecret() { return previousSecret; }
        public void setPreviousSecret(String previousSecret) { this.previousSecret = previousSecret; }
    }

    public static class Lockout {
        private int maxAttempts = 5;
        private Duration window = Duration.ofMinutes(15);

        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
        public Duration getWindow() { return window; }
        public void setWindow(Duration window) { this.window = window; }
    }

    public static class Ttl {
        private Duration ttl;
        public Ttl() {}
        public Ttl(Duration ttl) { this.ttl = ttl; }
        public Duration getTtl() { return ttl; }
        public void setTtl(Duration ttl) { this.ttl = ttl; }
    }

    public static class Crypto {
        private String piiKey = "";
        private String piiPepper = "";
        private String tokenPepper = "";
        private String ipPepper = "";

        public String getPiiKey() { return piiKey; }
        public void setPiiKey(String piiKey) { this.piiKey = piiKey; }
        public String getPiiPepper() { return piiPepper; }
        public void setPiiPepper(String piiPepper) { this.piiPepper = piiPepper; }
        public String getTokenPepper() { return tokenPepper; }
        public void setTokenPepper(String tokenPepper) { this.tokenPepper = tokenPepper; }
        public String getIpPepper() { return ipPepper; }
        public void setIpPepper(String ipPepper) { this.ipPepper = ipPepper; }
    }
}
