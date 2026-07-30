package com.cadence.repository;

import com.cadence.domain.BillingWebhookEvent;
import org.springframework.data.mongodb.repository.MongoRepository;

/** 032 -- webhook idempotency ledger; insert + existsByEventId only (FR-009). */
public interface BillingWebhookEventRepository extends MongoRepository<BillingWebhookEvent, String> {

    boolean existsByEventId(String eventId);
}
