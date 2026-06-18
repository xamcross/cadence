package com.cadence.repository;

import com.cadence.domain.AtsSyncRun;
import com.cadence.integration.AtsProvider;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

/**
 * F40/F41 sync-run audit store. {@code findFirstByWorkspaceIdOrderByStartedAtDesc} backs the legacy
 * workspace-wide read; {@code findFirstByWorkspaceIdAndProviderOrderByStartedAtDesc} backs the F41
 * per-provider "last successful sync" status surface (newest-first, bounded read; index
 * {@code {workspaceId,provider,startedAt:-1}}, ChangeUnit019).
 */
public interface AtsSyncRunRepository extends MongoRepository<AtsSyncRun, String> {

    Optional<AtsSyncRun> findFirstByWorkspaceIdOrderByStartedAtDesc(String workspaceId);

    Optional<AtsSyncRun> findFirstByWorkspaceIdAndProviderOrderByStartedAtDesc(String workspaceId, AtsProvider provider);
}
