package com.cadence.repository;

import com.cadence.domain.EmailMessageType;
import com.cadence.domain.EmailTemplate;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/**
 * F21 email-template overrides. All reads/writes are workspace-scoped (FR-009). The base template uses
 * {@code stageKey="BASE"}; a per-stage variant uses an F12 interview-template id (D2).
 */
public interface EmailTemplateRepository extends MongoRepository<EmailTemplate, String> {

    Optional<EmailTemplate> findByWorkspaceIdAndMessageTypeAndStageKey(
        String workspaceId, EmailMessageType messageType, String stageKey);

    /** All overrides for a workspace at a given stage key (e.g. "BASE" — the base library). */
    List<EmailTemplate> findByWorkspaceIdAndStageKey(String workspaceId, String stageKey);

    /** All overrides (base + variants) of one message type in a workspace. */
    List<EmailTemplate> findByWorkspaceIdAndMessageType(String workspaceId, EmailMessageType messageType);

    /** Variant count for a type (everything except the base) — backs the per-type variant cap. */
    long countByWorkspaceIdAndMessageTypeAndStageKeyNot(
        String workspaceId, EmailMessageType messageType, String stageKey);
}
