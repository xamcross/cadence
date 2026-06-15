package com.cadence.domain;

/**
 * The GDPR lawful basis for contacting a candidate by email (F04, FR-003). A closed enumeration so
 * the consent record is structurally non-PII. The MVP's only candidate channel is email.
 */
public enum LawfulBasis {
    CONSENT,
    LEGITIMATE_INTEREST,
    CONTRACT
}
