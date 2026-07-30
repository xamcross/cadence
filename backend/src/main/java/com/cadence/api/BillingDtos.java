package com.cadence.api;

import com.cadence.domain.BillingPlan;
import com.cadence.domain.EntitlementStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** 032 -- billing API contracts. No license ids and no PII in responses. */
public final class BillingDtos {

    private BillingDtos() {}

    /** Plan view for the Billing page + gated-surface prompts. status/expiresAt/boundAt null on FREE. */
    public record EntitlementResponse(BillingPlan plan, EntitlementStatus status,
                                      Instant expiresAt, Instant boundAt) {}

    public record CheckoutSessionResponse(String checkoutUrl) {}

    public record ClaimRequest(@NotBlank @Size(max = 64) String licenseId) {}
}
