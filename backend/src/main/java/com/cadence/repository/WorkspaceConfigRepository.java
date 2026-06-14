package com.cadence.repository;

import com.cadence.domain.WorkspaceConfig;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

/**
 * Read-only access to the singleton {@link WorkspaceConfig} (research D4 invariant: reads NEVER
 * get-or-create — the wizard upsert in WorkspaceConfigService is the only inserter).
 */
public interface WorkspaceConfigRepository extends MongoRepository<WorkspaceConfig, String> {

    Optional<WorkspaceConfig> findByWorkspaceId(String workspaceId);

    boolean existsByWorkspaceIdAndConfiguredAtNotNull(String workspaceId);
}
