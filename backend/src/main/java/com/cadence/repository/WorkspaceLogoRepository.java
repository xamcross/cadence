package com.cadence.repository;

import com.cadence.domain.WorkspaceLogo;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface WorkspaceLogoRepository extends MongoRepository<WorkspaceLogo, String> {

    Optional<WorkspaceLogo> findByWorkspaceId(String workspaceId);

    void deleteByWorkspaceId(String workspaceId);
}
