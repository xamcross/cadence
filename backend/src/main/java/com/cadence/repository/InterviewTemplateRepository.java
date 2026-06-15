package com.cadence.repository;

import com.cadence.domain.InterviewTemplate;
import com.cadence.domain.TemplateStatus;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/** Persistence for {@link InterviewTemplate} (F12). All reads are workspace-scoped (FR-006). */
public interface InterviewTemplateRepository extends MongoRepository<InterviewTemplate, String> {

    List<InterviewTemplate> findByWorkspaceId(String workspaceId);

    List<InterviewTemplate> findByWorkspaceIdAndStatus(String workspaceId, TemplateStatus status);

    /** Scoped read — empty for both "missing" and "other workspace" (indistinguishable not-found, FR-006). */
    Optional<InterviewTemplate> findByWorkspaceIdAndId(String workspaceId, String id);
}
