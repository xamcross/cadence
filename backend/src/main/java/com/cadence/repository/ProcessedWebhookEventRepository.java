package com.cadence.repository;

import com.cadence.domain.ProcessedWebhookEvent;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * F22 processed-webhook-event store (T040). The unique {@code eventId} index (ChangeUnit011) is the
 * idempotency guarantee — an {@code insert} that hits a {@code DuplicateKeyException} means already-processed.
 */
public interface ProcessedWebhookEventRepository extends MongoRepository<ProcessedWebhookEvent, String> {
}
