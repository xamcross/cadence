package com.cadence.integration;

/**
 * 032 -- the provider-agnostic billing seam (FR-018, constitution Dependency Policy). All Freemius
 * access lives behind this interface so service/scheduler code never references the concrete client
 * or the freemius.com hosts (enforced by BillingNoSdkStructuralTest).
 */
public interface BillingProvider {

    /**
     * Fetch one license by id. Throws {@link BillingApiException} classified transient (429/5xx/
     * network), not-found (404), auth (401/403 -- operator misconfig), or malformed.
     */
    BillingLicense fetchLicense(String licenseId);

    /**
     * Build the hosted-checkout URL for the Team plan with the buyer email prefilled read-only and
     * the return URL pointing at the SPA billing page (FR-005). Pure URL construction -- no HTTP.
     */
    String checkoutUrl(String userEmail);
}
