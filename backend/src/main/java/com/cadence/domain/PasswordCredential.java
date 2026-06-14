package com.cadence.domain;

/**
 * Embedded BCrypt password credential for a fallback (email+password) member.
 * Stores only the BCrypt hash — never plaintext, never logged (FR-004/FR-022).
 */
public class PasswordCredential {

    private String bcryptHash;

    public PasswordCredential() {}

    public PasswordCredential(String bcryptHash) {
        this.bcryptHash = bcryptHash;
    }

    public String getBcryptHash() { return bcryptHash; }
    public void setBcryptHash(String bcryptHash) { this.bcryptHash = bcryptHash; }
}
