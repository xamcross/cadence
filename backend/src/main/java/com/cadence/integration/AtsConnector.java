package com.cadence.integration;

/**
 * The provider-agnostic ATS connector contract (F40, constitution Dependency Policy / FR-026). All
 * Greenhouse-specific access lives behind this interface so workspace/business logic depends only on the
 * contract, never on a provider client — enabling F41 (Lever) to add a second implementation and a provider
 * to be swapped by changing only the bean wiring (SC-009). The credential is passed in (already decrypted
 * by the service from the {@code atsConnections} row); the connector never reads it from storage.
 */
public interface AtsConnector {

    /** Which provider this connector serves (used to select from {@code Map<AtsProvider,AtsConnector>}). */
    AtsProvider provider();

    /**
     * Authenticated liveness/credential check. Returns normally if the credential is valid; throws
     * {@link AtsApiException} with {@code needsReauth} on a rejected credential (401/403).
     */
    void verifyCredential(String workspaceId, String apiKey);

    /**
     * Pull candidates updated since {@code cursor} (null = full). Returns ONLY the minimized field set
     * (FR-029); no provider types leak. Throws {@link AtsApiException} (transient/needs-reauth/fatal).
     */
    AtsFetchResult fetchCandidates(String workspaceId, String apiKey, String cursor);

    /**
     * Write one activity to the candidate's ATS timeline (addressed by {@code externalRef}); returns the
     * opaque provider activity id. Throws {@link AtsApiException} on failure.
     */
    String pushActivity(String workspaceId, String apiKey, String externalRef, AtsActivity activity);
}
