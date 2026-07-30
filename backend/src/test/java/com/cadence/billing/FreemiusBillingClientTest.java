package com.cadence.billing;

import com.cadence.config.AuthProperties;
import com.cadence.config.BillingProperties;
import com.cadence.integration.BillingApiException;
import com.cadence.integration.BillingApiRetry;
import com.cadence.integration.BillingLicense;
import com.cadence.integration.FreemiusBillingClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** 032 Task 2 -- pure-unit adapter test against the JVM-lifetime StubFreemius (the LeverAtsClientTest pattern). */
class FreemiusBillingClientTest {

    private static final StubFreemius stub = new StubFreemius(); // JVM-lifetime; never stopped (dead-port footgun)

    private final BillingProperties props = props();
    private final AuthProperties auth = auth();
    private final FreemiusBillingClient client =
        new FreemiusBillingClient(new BillingApiRetry(props), props, auth);

    private static BillingProperties props() {
        BillingProperties p = new BillingProperties();
        p.setBaseUrl(stub.baseUrl());
        p.setCheckoutBaseUrl("https://checkout.example.test");
        p.setProductId("1001");
        p.setTeamPlanId("2002");
        p.setApiBearer("test-billing-bearer");
        p.setRetryBaseBackoff(Duration.ZERO);
        return p;
    }

    private static AuthProperties auth() {
        AuthProperties a = new AuthProperties();
        a.setSpaBaseUrl("https://app.example.test");
        return a;
    }

    @BeforeEach
    void reset() {
        stub.reset();
    }

    @Test
    void fetchLicense_parsesExplicitFields_andSendsBearer() {
        stub.programLicense("777", "{\"id\":\"777\",\"plan_id\":\"2002\",\"user_id\":\"55\","
            + "\"expiration\":\"2027-01-15 10:30:00\",\"is_cancelled\":false,"
            + "\"secret_key\":\"SENTINEL-NEVER-PARSED\",\"user_email\":\"SENTINEL@pii.test\"}");
        BillingLicense l = client.fetchLicense("777");
        assertThat(l.id()).isEqualTo("777");
        assertThat(l.planId()).isEqualTo("2002");
        assertThat(l.userId()).isEqualTo("55");
        assertThat(l.cancelled()).isFalse();
        assertThat(l.expiresAt()).isEqualTo(Instant.parse("2027-01-15T10:30:00Z"));
        assertThat(stub.lastAuthHeader()).isEqualTo("Bearer test-billing-bearer");
    }

    @Test
    void fetchLicense_nullExpiration_isLifetime() {
        stub.programLicense("778", "{\"id\":\"778\",\"plan_id\":\"2002\",\"user_id\":\"55\","
            + "\"expiration\":null,\"is_cancelled\":true}");
        BillingLicense l = client.fetchLicense("778");
        assertThat(l.expiresAt()).isNull();
        assertThat(l.cancelled()).isTrue();
    }

    @Test
    void errors_classify_notFound_auth_transient_malformed() {
        stub.programStatus(404);
        BillingApiException notFound =
            catchThrowableOfType(() -> client.fetchLicense("x"), BillingApiException.class);
        assertThat(notFound.isNotFound()).isTrue();
        assertThat(notFound.isTransient()).isFalse();

        stub.reset();
        stub.programStatus(401);
        BillingApiException authErr =
            catchThrowableOfType(() -> client.fetchLicense("x"), BillingApiException.class);
        assertThat(authErr.isAuth()).isTrue();

        stub.reset();
        stub.programStatus(500);
        BillingApiException transientErr =
            catchThrowableOfType(() -> client.fetchLicense("x"), BillingApiException.class);
        assertThat(transientErr.isTransient()).isTrue();

        stub.reset();
        stub.programLicense("779", "not-json{{");
        assertThatThrownBy(() -> client.fetchLicense("779"))
            .isInstanceOf(BillingApiException.class);
    }

    @Test
    void checkoutUrl_isHosted_withEncodedEmail_andReturnUrl() {
        String url = client.checkoutUrl("admin+x@corp.test");
        assertThat(url).startsWith("https://checkout.example.test/product/1001/plan/2002/?");
        assertThat(url).contains("user_email=admin%2Bx%40corp.test");
        assertThat(url).contains("readonly_user=true");
        assertThat(url).contains("return_url=https%3A%2F%2Fapp.example.test%2Fadmin%2Fbilling");
    }
}
