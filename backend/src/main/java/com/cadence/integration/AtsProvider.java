package com.cadence.integration;

/**
 * The set of supported Applicant Tracking System providers (F40). Greenhouse is the only MVP connector;
 * Lever (F41) reuses the {@link AtsConnector} contract and adds a second value. The value is the
 * always-non-null discriminator on the imported {@code Candidate} (the F11 provider-discriminator precedent).
 */
public enum AtsProvider {
    GREENHOUSE
}
