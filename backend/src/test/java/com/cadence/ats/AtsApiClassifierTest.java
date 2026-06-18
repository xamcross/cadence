package com.cadence.ats;

import com.cadence.integration.AtsApiClassifier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit: Greenhouse failure classification (T009). Network/429/5xx -> TRANSIENT; 401/403 -> AUTH; else FATAL. */
class AtsApiClassifierTest {

    @Test
    void networkAndThrottleAndServerErrorsAreTransient() {
        assertThat(AtsApiClassifier.classify(null)).isEqualTo(AtsApiClassifier.Outcome.TRANSIENT);
        assertThat(AtsApiClassifier.classify(429)).isEqualTo(AtsApiClassifier.Outcome.TRANSIENT);
        assertThat(AtsApiClassifier.classify(500)).isEqualTo(AtsApiClassifier.Outcome.TRANSIENT);
        assertThat(AtsApiClassifier.classify(503)).isEqualTo(AtsApiClassifier.Outcome.TRANSIENT);
    }

    @Test
    void authStatusesAreAuth() {
        assertThat(AtsApiClassifier.classify(401)).isEqualTo(AtsApiClassifier.Outcome.AUTH);
        assertThat(AtsApiClassifier.classify(403)).isEqualTo(AtsApiClassifier.Outcome.AUTH);
    }

    @Test
    void otherClientErrorsAreFatal() {
        assertThat(AtsApiClassifier.classify(400)).isEqualTo(AtsApiClassifier.Outcome.FATAL);
        assertThat(AtsApiClassifier.classify(404)).isEqualTo(AtsApiClassifier.Outcome.FATAL);
        assertThat(AtsApiClassifier.classify(422)).isEqualTo(AtsApiClassifier.Outcome.FATAL);
    }
}
