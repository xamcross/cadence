package com.cadence.repository;

import com.cadence.domain.AtsConnection;
import com.cadence.domain.AtsConnectionStatus;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/**
 * F40 ATS connection store. {@code findByWorkspaceId} resolves the single per-workspace connection
 * (unique index, ChangeUnit018); {@code findByStatus} backs the poll's CONNECTED-workspace iteration.
 */
public interface AtsConnectionRepository extends MongoRepository<AtsConnection, String> {

    Optional<AtsConnection> findByWorkspaceId(String workspaceId);

    List<AtsConnection> findByStatus(AtsConnectionStatus status);
}
