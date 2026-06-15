package com.cadence.calendar;

import com.cadence.integration.OAuthTokenException;
import com.cadence.service.OAuthFailureClassifier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** T016: pure unit truth table for the token-failure classifier (research D6). */
class OAuthFailureClassifierTest {

    private final OAuthFailureClassifier classifier = new OAuthFailureClassifier();

    private OAuthFailureClassifier.Classification classify(String error, Integer status) {
        return classifier.classify(new OAuthTokenException(error, status, "x", null));
    }

    @Test
    void invalidGrant_isPermanent() {
        assertThat(classify("invalid_grant", 400)).isEqualTo(OAuthFailureClassifier.Classification.PERMANENT);
        assertThat(classify("invalid_grant", null)).isEqualTo(OAuthFailureClassifier.Classification.PERMANENT);
    }

    @Test
    void rateLimitAnd5xxAndNetwork_areTransient() {
        assertThat(classify(null, 429)).isEqualTo(OAuthFailureClassifier.Classification.TRANSIENT);
        assertThat(classify(null, 500)).isEqualTo(OAuthFailureClassifier.Classification.TRANSIENT);
        assertThat(classify("temporarily_unavailable", 503)).isEqualTo(OAuthFailureClassifier.Classification.TRANSIENT);
        assertThat(classify(null, null)).isEqualTo(OAuthFailureClassifier.Classification.TRANSIENT); // network
    }

    @Test
    void otherClientErrors_areFatal() {
        assertThat(classify("invalid_client", 401)).isEqualTo(OAuthFailureClassifier.Classification.FATAL);
        assertThat(classify("invalid_request", 400)).isEqualTo(OAuthFailureClassifier.Classification.FATAL);
    }
}
