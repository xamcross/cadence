package com.cadence.repository;

import com.cadence.domain.AtsSyncRun;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

/**
 * F40 sync-run audit store. {@code findFirstByWorkspaceIdOrderByStartedAtDesc} backs the
 * "last successful sync" status surface (newest-first, bounded read; index
 * {@code {workspaceId,startedAt:-1}}, ChangeUnit018).
 */
public interface AtsSyncRunRepository extends MongoRepository<AtsSyncRun, String> {

    Optional<AtsSyncRun> findFirstByWorkspaceIdOrderByStartedAtDesc(String workspaceId);
}
