package com.cadence.integration;

import java.time.Instant;

/** 032 -- the minimized license projection (explicit fields only; provider free-text never binds). */
public record BillingLicense(String id, String planId, String userId, Instant expiresAt, boolean cancelled) {}
