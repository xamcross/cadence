package com.cadence.integration;

/**
 * The set of supported Applicant Tracking System providers (F40/F41). Greenhouse and Lever both reuse the
 * {@link AtsConnector} contract. The value is the always-non-null discriminator on the imported
 * {@code Candidate}, on {@code AtsWriteBack} (the routing key), and on {@code AtsSyncRun} (per-provider status)
 * — the F11 provider-discriminator precedent. A workspace may hold one connection per provider (F41).
 */
public enum AtsProvider {
    GREENHOUSE,
    LEVER
}
